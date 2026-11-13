package com.cleanvault.app.data

import android.accounts.Account
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

enum class CloudSyncMode {
    LOCAL_ONLY,
    GOOGLE_DRIVE,
    SHARED_CABINET
}

data class VaultDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val date: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
    val isPinned: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val driveFileId: String? = null,
    val driveFileUrl: String? = null,
    val isSynced: Boolean = false,
    val lastModified: Long = System.currentTimeMillis(),
    val contentPending: Boolean = false
)

data class TrashedDocument(val document: VaultDocument, val trashedAt: Long)

data class CloudSyncStatus(val lastSuccess: Long, val needsRetry: Boolean)

class VaultRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("clean_vault_meta", Context.MODE_PRIVATE)
    private val docsDir = File(context.filesDir, "vault_documents").apply { mkdirs() }
    private val cloudMutex = Mutex()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val googleDriveService = GoogleDriveService(context)

    fun getActiveCloudMode(): CloudSyncMode {
        val prefMode = prefs.getString("active_cloud_mode", null)
        if (prefMode != null) {
            return try {
                CloudSyncMode.valueOf(prefMode)
            } catch (_: Exception) {
                CloudSyncMode.LOCAL_ONLY
            }
        }
        return when {
            getScriptUrl().isNotBlank() -> CloudSyncMode.SHARED_CABINET
            isGoogleDriveConnected() -> CloudSyncMode.GOOGLE_DRIVE
            else -> CloudSyncMode.LOCAL_ONLY
        }
    }

    fun setActiveCloudMode(mode: CloudSyncMode) {
        prefs.edit().putString("active_cloud_mode", mode.name).apply()
    }

    fun isGoogleDriveConnected(): Boolean {
        return getGoogleEmail().isNotBlank()
    }

    fun hasCloudConnection(): Boolean {
        return getActiveCloudMode() != CloudSyncMode.LOCAL_ONLY
    }

    private fun syncStatusKey(): String = when (getActiveCloudMode()) {
        CloudSyncMode.SHARED_CABINET -> "sync_shared:${getScriptUrl()}"
        CloudSyncMode.GOOGLE_DRIVE -> "sync_drive:${getGoogleEmail()}"
        CloudSyncMode.LOCAL_ONLY -> "sync_local"
    }

    fun getCloudSyncStatus(): CloudSyncStatus {
        val key = syncStatusKey()
        return CloudSyncStatus(prefs.getLong("$key:last_success", 0L), prefs.getBoolean("$key:retry", false))
    }

    fun getGoogleEmail(): String {
        return prefs.getString("google_account_email", "") ?: ""
    }

    fun setGoogleAccount(email: String?, folderId: String? = null) {
        val editor = prefs.edit()
        if (email.isNullOrBlank()) {
            editor.remove("google_account_email")
            editor.remove("google_drive_folder_id")
            if (getActiveCloudMode() == CloudSyncMode.GOOGLE_DRIVE) {
                editor.putString("active_cloud_mode", if (getScriptUrl().isNotBlank()) CloudSyncMode.SHARED_CABINET.name else CloudSyncMode.LOCAL_ONLY.name)
            }
        } else {
            editor.putString("google_account_email", email.trim())
            if (!folderId.isNullOrBlank()) {
                editor.putString("google_drive_folder_id", folderId.trim())
            }
            if (prefs.getString("active_cloud_mode", null) == null && getScriptUrl().isBlank()) {
                editor.putString("active_cloud_mode", CloudSyncMode.GOOGLE_DRIVE.name)
            }
        }
        editor.apply()
    }

    fun getGoogleFolderId(): String {
        return prefs.getString("google_drive_folder_id", "") ?: ""
    }

    fun setGoogleFolderId(folderId: String) {
        prefs.edit().putString("google_drive_folder_id", folderId.trim()).apply()
    }

    fun getScriptUrl(): String {
        return prefs.getString("apps_script_url", "") ?: ""
    }

    fun setScriptUrl(url: String) {
        val trimmed = url.trim()
        val editor = prefs.edit().putString("apps_script_url", trimmed)
        if (trimmed.isNotBlank()) {
            editor.putString("active_cloud_mode", CloudSyncMode.SHARED_CABINET.name)
        } else if (getActiveCloudMode() == CloudSyncMode.SHARED_CABINET) {
            editor.putString("active_cloud_mode", if (isGoogleDriveConnected()) CloudSyncMode.GOOGLE_DRIVE.name else CloudSyncMode.LOCAL_ONLY.name)
        }
        editor.apply()
    }

    fun getDeletedIds(): Set<String> {
        return prefs.getStringSet("deleted_doc_ids", emptySet()) ?: emptySet()
    }

    fun parseTimestamp(raw: Any?, fallbackDate: Any? = null): Long {
        if (raw is Number) return raw.toLong()
        val str = raw?.toString()?.trim() ?: ""
        if (str.isNotBlank()) {
            val asLong = str.toLongOrNull()
            if (asLong != null && asLong > 1000000000L) return asLong
            try {
                val sdfIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
                val d = sdfIso.parse(str)
                if (d != null) return d.time
            } catch (_: Exception) {}
            try {
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val d = sdfDate.parse(str)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        val dateStr = fallbackDate?.toString()?.trim() ?: ""
        if (dateStr.isNotBlank()) {
            try {
                val sdfDate = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                val d = sdfDate.parse(dateStr)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return System.currentTimeMillis()
    }

    private fun deduplicateList(rawList: List<VaultDocument>): List<VaultDocument> {
        val deleted = getDeletedIds()
        val valid = rawList.filter { !deleted.contains(it.id) && (it.driveFileId == null || !deleted.contains(it.driveFileId)) }

        val dedupedMap = LinkedHashMap<String, VaultDocument>()
        for (doc in valid) {
            val key = if (!doc.driveFileId.isNullOrBlank()) {
                "file_${doc.driveFileId}"
            } else if (doc.filePath.isNotBlank()) {
                "path_${doc.filePath}"
            } else {
                "id_${doc.id}"
            }

            val existing = dedupedMap[key]
            if (existing == null) {
                dedupedMap[key] = doc
            } else {
                val winner = if (doc.lastModified >= existing.lastModified) doc else existing
                val loser = if (doc.lastModified >= existing.lastModified) existing else doc
                val merged = winner.copy(
                    isPinned = if (winner.isSynced != loser.isSynced) {
                        if (!winner.isSynced) winner.isPinned else loser.isPinned
                    } else winner.isPinned,
                    driveFileId = winner.driveFileId ?: loser.driveFileId,
                    driveFileUrl = winner.driveFileUrl ?: loser.driveFileUrl,
                    filePath = if (winner.filePath.isNotBlank() && File(winner.filePath).exists()) winner.filePath else loser.filePath,
                    timestamp = if (winner.timestamp > 0) winner.timestamp else loser.timestamp
                )
                dedupedMap[key] = merged
            }
        }
        return dedupedMap.values.toList()
    }

    suspend fun clearAllDocuments() = cloudMutex.withLock {
        val current = getDocuments()
        for (doc in current.filter { it.isSynced }) {
            try { File(doc.filePath).delete() } catch (_: Exception) {}
        }
        saveDocuments(current.filterNot { it.isSynced })
    }

    fun getDocuments(): List<VaultDocument> {
        val jsonStr = prefs.getString("documents_list", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<VaultDocument>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(decodeDocument(obj))
        }
        return deduplicateList(list).sortedWith(
            compareByDescending<VaultDocument> { if (it.timestamp > 0) it.timestamp else it.lastModified }
                .thenByDescending { it.date }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun decodeDocument(obj: JSONObject): VaultDocument =
        VaultDocument(
            id = obj.getString("id"),
            name = obj.getString("name"),
            notes = obj.optString("notes", ""),
            fileName = obj.getString("fileName"),
            filePath = obj.getString("filePath"),
            mimeType = obj.optString("mimeType", "image/jpeg"),
            date = obj.optString("date", ""),
            isPinned = obj.optBoolean("isPinned", false),
            timestamp = obj.optLong("timestamp", 0L),
            driveFileId = obj.optString("driveFileId").takeIf { !it.isNullOrBlank() },
            driveFileUrl = obj.optString("driveFileUrl").takeIf { !it.isNullOrBlank() },
            isSynced = obj.optBoolean("isSynced", false),
            lastModified = obj.optLong("lastModified", obj.optLong("timestamp", 0L)),
            contentPending = obj.optBoolean("contentPending", false)
        )

    private fun saveDocuments(docs: List<VaultDocument>) {
        prefs.edit().putString("documents_list", encodeDocuments(deduplicateList(docs))).apply()
    }

    private fun encodeDocuments(docs: List<VaultDocument>): String {
        val array = JSONArray()
        docs.forEach { doc -> array.put(encodeDocument(doc)) }
        return array.toString()
    }

    private fun encodeDocument(doc: VaultDocument): JSONObject = JSONObject().apply {
        put("id", doc.id)
        put("name", doc.name)
        put("notes", doc.notes)
        put("fileName", doc.fileName)
        put("filePath", doc.filePath)
        put("mimeType", doc.mimeType)
        put("date", doc.date)
        put("isPinned", doc.isPinned)
        put("timestamp", doc.timestamp)
        put("driveFileId", doc.driveFileId ?: "")
        put("driveFileUrl", doc.driveFileUrl ?: "")
        put("isSynced", doc.isSynced)
        put("lastModified", doc.lastModified)
        put("contentPending", doc.contentPending)
    }

    suspend fun saveFileToVault(name: String, notes: String, isPinned: Boolean, sourceFile: File, mimeType: String): VaultDocument = cloudMutex.withLock {
        val ext = sourceFile.extension.ifEmpty { if (mimeType.contains("pdf")) "pdf" else if (mimeType.contains("text")) "txt" else "jpg" }
        val targetFile = File(docsDir, "Vault_${UUID.randomUUID()}.$ext")
        sourceFile.copyTo(targetFile, overwrite = true)

        val now = System.currentTimeMillis()
        val doc = VaultDocument(
            name = name.ifBlank { targetFile.nameWithoutExtension },
            notes = notes,
            fileName = targetFile.name,
            filePath = targetFile.absolutePath,
            mimeType = mimeType,
            isPinned = isPinned,
            isSynced = false,
            timestamp = now,
            lastModified = now
        )

        val current = getDocuments().toMutableList()
        current.add(0, doc)
        saveDocuments(current)
        doc
    }

    suspend fun togglePin(docId: String): List<VaultDocument> = cloudMutex.withLock {
        val list = getDocuments().map {
            if (it.id == docId) it.copy(isPinned = !it.isPinned, isSynced = false, lastModified = System.currentTimeMillis()) else it
        }
        saveDocuments(list)
        list
    }

    suspend fun renameDocument(docId: String, newName: String, newNotes: String): List<VaultDocument> = cloudMutex.withLock {
        val list = getDocuments().map {
            if (it.id == docId) {
                it.copy(
                    name = newName.ifBlank { it.name },
                    notes = newNotes,
                    isSynced = false,
                    lastModified = System.currentTimeMillis()
                )
            } else it
        }
        saveDocuments(list)
        list
    }

    suspend fun updateNote(docId: String, name: String, notes: String, newContent: String): VaultDocument? = cloudMutex.withLock {
        val list = getDocuments().toMutableList()
        val index = list.indexOfFirst { it.id == docId }
        if (index == -1) return@withLock null
        val target = list[index]
        val file = File(target.filePath)
        file.writeText(newContent)
        val updated = target.copy(
            name = name,
            notes = notes,
            isSynced = false,
            contentPending = true,
            lastModified = System.currentTimeMillis()
        )
        list[index] = updated
        saveDocuments(list)
        updated
    }

    fun getTrash(): List<TrashedDocument> {
        val array = JSONArray(prefs.getString("trashed_documents", "[]"))
        return (0 until array.length()).map { index ->
            val entry = array.getJSONObject(index)
            TrashedDocument(decodeDocument(entry), entry.getLong("trashedAt"))
        }.sortedByDescending { it.trashedAt }
    }

    suspend fun moveToTrash(docId: String): Boolean = cloudMutex.withLock {
        val documents = getDocuments()
        val current = documents.find { it.id == docId } ?: return@withLock false
        // Keep a recoverable local copy before sync removes the remote copy.
        val target = withContext(Dispatchers.IO) { cacheForTrash(current) } ?: return@withLock false
        val stableId = target.id.takeUnless { it.startsWith("row_") && target.driveFileId != null }
        val deleted = getDeletedIds() + listOfNotNull(stableId, target.driveFileId)
        saveTrashState(
            getDocuments().filterNot { it.id == docId },
            getTrash() + TrashedDocument(target, System.currentTimeMillis()),
            deleted
        )
        true
    }

    private suspend fun cacheForTrash(doc: VaultDocument): VaultDocument? {
        if (File(doc.filePath).isFile) return doc
        val fileId = doc.driveFileId ?: return null
        val path = when (getActiveCloudMode()) {
            CloudSyncMode.SHARED_CABINET -> downloadAndSaveFile(getScriptUrl(), fileId, doc.fileName)
            CloudSyncMode.GOOGLE_DRIVE -> {
                val email = getGoogleEmail().ifBlank { return null }
                val token = googleDriveService.getAccessToken(Account(email, "com.google")) ?: return null
                val target = File(docsDir, "Vault_${UUID.randomUUID()}_${File(doc.fileName).name}")
                val file = googleDriveService.downloadFile(token, fileId, target).getOrNull()
                if (file == null) target.delete()
                file?.absolutePath
            }
            CloudSyncMode.LOCAL_ONLY -> null
        } ?: return null
        return doc.copy(filePath = path)
    }

    suspend fun restoreFromTrash(docId: String): Boolean = cloudMutex.withLock {
        val trash = getTrash()
        val target = trash.find { it.document.id == docId }?.document ?: return@withLock false
        if (!File(target.filePath).isFile) return@withLock false
        val deleted = getDeletedIds() - listOfNotNull(target.id, target.driveFileId).toSet()
        val restored = target.copy(
            id = if (target.id.startsWith("row_")) UUID.randomUUID().toString() else target.id,
            isSynced = false,
            lastModified = System.currentTimeMillis()
        )
        saveTrashState(getDocuments() + restored, trash.filterNot { it.document.id == docId }, deleted)
        true
    }

    private fun saveTrashState(
        documents: List<VaultDocument>,
        trash: List<TrashedDocument>,
        deleted: Set<String>
    ) {
        val entries = JSONArray()
        trash.forEach { entries.put(encodeDocument(it.document).put("trashedAt", it.trashedAt)) }
        prefs.edit()
            .putString("documents_list", encodeDocuments(documents))
            .putString("trashed_documents", entries.toString())
            .putStringSet("deleted_doc_ids", deleted)
            .apply()
    }

    // --- Cloud Sync Logic ---

    suspend fun testConnection(url: String): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext Pair(false, "URL is blank")
        try {
            val req = Request.Builder()
                .url("$url?action=ping&_t=${System.currentTimeMillis()}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { response ->
                val body = response.body?.string() ?: ""
                if (response.isSuccessful && body.contains("success")) {
                    Pair(true, "Connected successfully to Google Apps Script!")
                } else {
                    Pair(false, "Server response: $body")
                }
            }
        } catch (e: Exception) {
            Pair(false, "Connection error: ${e.message}")
        }
    }

    suspend fun syncWithCloud(): Result<SyncSummary> = cloudMutex.withLock {
        withContext(Dispatchers.IO) {
            val mode = getActiveCloudMode()
            val key = syncStatusKey()
            val result = when (mode) {
                CloudSyncMode.SHARED_CABINET -> syncAppsScriptCloud()
                CloudSyncMode.GOOGLE_DRIVE -> syncDirectGoogleDrive()
                CloudSyncMode.LOCAL_ONLY -> Result.failure(Exception("Cloud sync is disabled (Local Only mode)."))
            }
            if (mode != CloudSyncMode.LOCAL_ONLY) {
                val successful = result.getOrNull()?.failed == 0
                prefs.edit().apply {
                    putBoolean("$key:retry", !successful)
                    if (successful) putLong("$key:last_success", System.currentTimeMillis())
                }.apply()
            }
            result
        }
    }

    private suspend fun syncDirectGoogleDrive(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        val email = getGoogleEmail()
        if (email.isBlank()) {
            return@withContext Result.failure(Exception("Not signed into Google Drive. Please tap Sign in with Google in Settings."))
        }

        try {
            val account = Account(email, "com.google")
            val token = googleDriveService.getAccessToken(account)
                ?: return@withContext Result.failure(Exception("Could not obtain Google Drive access token. Please re-sign in."))

            // 1. Get or Create "Clean Filing" folder
            val folderResult = googleDriveService.findOrCreateAppFolder(token)
            if (folderResult.isFailure) {
                return@withContext Result.failure(folderResult.exceptionOrNull() ?: Exception("Folder error"))
            }
            val folderId = folderResult.getOrThrow()
            setGoogleFolderId(folderId)

            // 2. Fetch remote files in the folder
            val listResult = googleDriveService.listDocuments(token, folderId)
            if (listResult.isFailure) {
                return@withContext Result.failure(listResult.exceptionOrNull() ?: Exception("List error"))
            }
            val remoteFiles = listResult.getOrThrow()
            val deletedIds = getDeletedIds()

            // Map remote files by ID and docId property
            val remoteMap = mutableMapOf<String, JSONObject>()
            for (rf in remoteFiles) {
                val fId = rf.getString("id")
                val props = rf.optJSONObject("properties")
                val propDocId = props?.optString("docId", "") ?: ""
                val effectiveKey = if (propDocId.isNotBlank()) propDocId else fId

                // If marked deleted, remove from Drive and skip
                if (deletedIds.contains(fId) || (propDocId.isNotBlank() && deletedIds.contains(propDocId))) {
                    googleDriveService.deleteFile(token, fId).getOrThrow()
                    continue
                }
                remoteMap[effectiveKey] = rf
            }

            var localList = getDocuments().toMutableList()
            var addedCount = 0
            var uploadedCount = 0
            val failed = mutableSetOf<String>()

            // 3. Download any remote file that doesn't exist locally
            for ((key, rf) in remoteMap) {
                val fId = rf.getString("id")
                val rName = rf.optString("name", "Document")
                val rDesc = rf.optString("description", "")
                val rMime = rf.optString("mimeType", "application/pdf")
                val rUrl = rf.optString("webViewLink", "https://drive.google.com/file/d/$fId/view")
                val props = rf.optJSONObject("properties")
                val propDocId = props?.optString("docId", "").takeIf { !it.isNullOrBlank() } ?: key
                val propNotes = props?.optString("notes", rDesc) ?: rDesc
                val propPinned = props?.optString("isPinned", "false")?.toBoolean() ?: false
                val remoteTimestamp = parseTimestamp(rf.optString("createdTime", ""), rf.optString("modifiedTime", ""))

                val existingIndex = localList.indexOfFirst {
                    it.driveFileId == fId || it.id == propDocId || (it.driveFileUrl?.isNotBlank() == true && it.driveFileUrl == rUrl)
                }

                if (existingIndex >= 0) {
                    val existing = localList[existingIndex]
                    val fileOnDisk = File(existing.filePath)
                    val actualPath = if (!fileOnDisk.exists() || existing.filePath.isBlank()) {
                        val ext = if (rMime.contains("pdf")) "pdf" else if (rMime.contains("text")) "txt" else "jpg"
                        val target = File(docsDir, "Vault_${System.currentTimeMillis()}_${existing.fileName.ifBlank { "doc.$ext" }}")
                        val dlResult = googleDriveService.downloadFile(token, fId, target)
                        if (dlResult.isSuccess) target.absolutePath else {
                            target.delete()
                            failed.add(existing.id)
                            existing.filePath
                        }
                    } else {
                        existing.filePath
                    }

                    val hasUnsyncedLocalEdits = !existing.isSynced
                    val finalPinned = if (hasUnsyncedLocalEdits) existing.isPinned else propPinned
                    val remoteName = props?.optString("vaultName").orEmpty().ifBlank {
                        if (rName == existing.fileName) existing.name else rName.substringBeforeLast('.')
                    }
                    val finalName = if (hasUnsyncedLocalEdits) existing.name else remoteName
                    val finalNotes = if (hasUnsyncedLocalEdits) existing.notes else propNotes
                    val metadataSynced = !hasUnsyncedLocalEdits ||
                        (finalName == remoteName && finalNotes == propNotes && finalPinned == propPinned) ||
                        googleDriveService.updateFileProperties(
                            token, fId, finalName,
                            mapOf("vaultName" to finalName, "notes" to finalNotes, "isPinned" to finalPinned.toString())
                        ).isSuccess
                    val contentSynced = !existing.contentPending || (fileOnDisk.isFile &&
                        googleDriveService.updateFileContent(token, fId, fileOnDisk, existing.mimeType).isSuccess)
                    if (!metadataSynced || !contentSynced) failed.add(existing.id)

                    localList[existingIndex] = existing.copy(
                        name = finalName,
                        notes = finalNotes,
                        isPinned = finalPinned,
                        driveFileId = fId,
                        driveFileUrl = rUrl,
                        filePath = actualPath,
                        isSynced = metadataSynced && contentSynced,
                        contentPending = !contentSynced,
                        timestamp = if (remoteTimestamp > 0) remoteTimestamp else existing.timestamp
                    )
                } else {
                    val ext = if (rMime.contains("pdf")) "pdf" else if (rMime.contains("text")) "txt" else "jpg"
                    val target = File(docsDir, "Vault_${System.currentTimeMillis()}_$rName.$ext")
                    val dlResult = googleDriveService.downloadFile(token, fId, target)
                    val localPath = if (dlResult.isSuccess) target.absolutePath else ""
                    if (dlResult.isFailure) {
                        target.delete()
                        failed.add(propDocId)
                    }

                    val newDoc = VaultDocument(
                        id = propDocId,
                        name = props?.optString("vaultName").orEmpty().ifBlank { rName.substringBeforeLast('.') },
                        notes = propNotes,
                        fileName = target.name,
                        filePath = localPath,
                        mimeType = rMime,
                        isPinned = propPinned,
                        driveFileId = fId,
                        driveFileUrl = rUrl,
                        isSynced = true,
                        timestamp = remoteTimestamp,
                        lastModified = remoteTimestamp
                    )
                    localList.add(newDoc)
                    addedCount++
                }
            }

            // 4. Upload any local document that isn't on Google Drive yet
            val finalDocs = mutableListOf<VaultDocument>()
            for (doc in localList) {
                if (deletedIds.contains(doc.id) || (!doc.driveFileId.isNullOrBlank() && deletedIds.contains(doc.driveFileId))) {
                    try { File(doc.filePath).delete() } catch (_: Exception) {}
                    continue
                }

                val existsInDrive = remoteMap.values.any { rf ->
                    val fId = rf.getString("id")
                    val props = rf.optJSONObject("properties")
                    val propDocId = props?.optString("docId", "")
                    doc.driveFileId == fId || (propDocId != null && propDocId == doc.id)
                }

                if (!existsInDrive) {
                    val localFile = File(doc.filePath)
                    if (localFile.isFile) {
                        val uploadResult = googleDriveService.uploadDocument(
                            token = token,
                            folderId = folderId,
                            file = localFile,
                            docName = doc.name,
                            notes = doc.notes,
                            mimeType = doc.mimeType,
                            docId = doc.id,
                            isPinned = doc.isPinned
                        )
                        if (uploadResult.isSuccess) {
                            val (fId, link) = uploadResult.getOrThrow()
                            finalDocs.add(doc.copy(driveFileId = fId, driveFileUrl = link, isSynced = true, contentPending = false))
                            uploadedCount++
                        } else {
                            failed.add(doc.id)
                            finalDocs.add(doc.copy(isSynced = false))
                        }
                    } else {
                        failed.add(doc.id)
                        finalDocs.add(doc.copy(isSynced = false))
                    }
                } else {
                    finalDocs.add(doc)
                }
            }

            saveDocuments(finalDocs)
            Result.success(SyncSummary(addedCount, uploadedCount, finalDocs.size, failed.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun syncAppsScriptCloud(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        val scriptUrl = getScriptUrl()
        if (scriptUrl.isBlank()) {
            return@withContext Result.failure(Exception("Shared Cabinet URL is blank. Please enter your shared link in Settings."))
        }

        try {
            // 1. Fetch remote records
            val getUrl = "$scriptUrl?action=get_records&_t=${System.currentTimeMillis()}"
            val req = Request.Builder().url(getUrl).get().build()
            val responseBody = httpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) throw Exception("Failed to fetch records: HTTP ${response.code}")
                response.body?.string() ?: throw Exception("Empty response from server")
            }

            val json = JSONObject(responseBody)
            if (json.optString("status") != "success") {
                throw Exception("Server returned error: ${json.optString("message", "Unknown error")}")
            }

            val records = json.optJSONArray("records") ?: JSONArray()
            val deletedIds = getDeletedIds()

            // Process pending deletions before merging active records.
            val rawRemoteList = mutableListOf<JSONObject>()
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                val rawDocId = r.optString("docId").ifBlank { r.optString("docID") }
                val remoteFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                val effectiveDocId = rawDocId.ifBlank {
                    if (remoteFileId.isNotBlank()) "file_$remoteFileId" else "row_${r.optInt("rowId")}"
                }

                if ((deletedIds.contains(effectiveDocId) && !effectiveDocId.startsWith("row_")) ||
                    (remoteFileId.isNotBlank() && deletedIds.contains(remoteFileId))) {
                    deleteSharedRecord(scriptUrl, effectiveDocId, remoteFileId)
                    continue
                }
                rawRemoteList.add(r)
            }

            // Map remote records: never auto-delete rows from cloud during fetch
            val remoteMap = LinkedHashMap<String, JSONObject>()
            for (r in rawRemoteList) {
                val rawDocId = r.optString("docId").ifBlank { r.optString("docID") }
                val remoteFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                val effectiveDocId = rawDocId.ifBlank {
                    if (remoteFileId.isNotBlank()) "file_$remoteFileId" else "row_${r.optInt("rowId")}"
                }
                remoteMap[effectiveDocId] = r
            }

            // Start with current local documents
            var localList = getDocuments().toMutableList()
            var addedCount = 0
            var uploadedCount = 0
            val failed = mutableSetOf<String>()
            val matchedLocalIds = mutableSetOf<String>()

            // 2. Merge each remote record with matching local document
            for ((docId, r) in remoteMap) {
                val remoteFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                val remoteVendor = r.optString("name").ifBlank { r.optString("vendor", "Document") }
                val remoteNotes = r.optString("labels").ifBlank { r.optString("notes", "") }
                val remoteDate = r.optString("date", "")
                val remoteFileUrl = r.optString("driveFileUrl").ifBlank { r.optString("driveFileURL").ifBlank { r.optString("fileUrl") } }
                val remotePinned = r.optBoolean("pinned", false)
                val remoteTimestamp = parseTimestamp(r.opt("timestamp"), remoteDate)

                // Display names can be shared by distinct documents.
                val existingIndex = localList.indexOfFirst {
                    (it.id == docId && (!docId.startsWith("row_") || remoteFileId.isBlank())) ||
                    (remoteFileId.isNotBlank() && it.driveFileId == remoteFileId) ||
                    (remoteFileUrl.isNotBlank() && it.driveFileUrl == remoteFileUrl)
                }

                if (existingIndex >= 0) {
                    val existing = localList[existingIndex]
                    val fileOnDisk = File(existing.filePath)
                    val needsDownload = !fileOnDisk.isFile ||
                        (existing.isSynced && remoteFileId != existing.driveFileId)
                    val downloadedPath = if (needsDownload && remoteFileId.isNotBlank()) {
                        downloadAndSaveFile(scriptUrl, remoteFileId, existing.fileName).also {
                            if (it == null) failed.add(existing.id)
                        }
                    } else null
                    val adoptedRemoteFile = !needsDownload || downloadedPath != null
                    val actualPath = downloadedPath ?: existing.filePath

                    // Conflict resolution:
                    // If local has unsynced user edits (!existing.isSynced), local wins and updates remote.
                    // Otherwise, remote cloud state wins!
                    val hasUnsyncedLocalEdits = !existing.isSynced
                    val finalName = if (hasUnsyncedLocalEdits && existing.name.isNotBlank()) existing.name else remoteVendor
                    val finalNotes = if (hasUnsyncedLocalEdits) existing.notes else remoteNotes
                    val finalPinned = if (hasUnsyncedLocalEdits) existing.isPinned else remotePinned

                    val metadataSynced = !hasUnsyncedLocalEdits || syncSharedMetadata(
                        scriptUrl, docId, remoteFileId, finalName, finalNotes, finalPinned,
                        finalName != remoteVendor || finalNotes != remoteNotes, finalPinned != remotePinned
                    )
                    val merged = existing.copy(
                        id = if (existing.id.startsWith("row_") && !docId.startsWith("row_")) docId else existing.id,
                        name = finalName,
                        notes = finalNotes,
                        isPinned = finalPinned,
                        driveFileId = if (adoptedRemoteFile || existing.driveFileId.isNullOrBlank()) {
                            remoteFileId.ifBlank { existing.driveFileId }
                        } else existing.driveFileId,
                        driveFileUrl = remoteFileUrl.ifBlank { existing.driveFileUrl },
                        filePath = actualPath,
                        isSynced = metadataSynced,
                        timestamp = if (remoteTimestamp > 0) remoteTimestamp else existing.timestamp
                    )
                    val updated = if (existing.contentPending && fileOnDisk.isFile) {
                        uploadDocumentToCloud(scriptUrl, merged)
                    } else null
                    if (!metadataSynced || (existing.contentPending && updated == null)) failed.add(existing.id)
                    localList[existingIndex] = updated ?: merged.copy(isSynced = metadataSynced && !existing.contentPending)
                    matchedLocalIds.add(localList[existingIndex].id)
                } else {
                    var localFilePath = ""
                    var localFileName = "Vault_Doc_${System.currentTimeMillis()}.jpg"
                    var mimeType = "image/jpeg"

                    if (remoteFileId.isNotBlank()) {
                        val downloaded = downloadFileContent(scriptUrl, remoteFileId)
                        if (downloaded != null) {
                            localFileName = downloaded.name
                            mimeType = downloaded.mimeType
                            val targetFile = File(docsDir, "Vault_${System.currentTimeMillis()}_${downloaded.name}")
                            targetFile.writeBytes(downloaded.bytes)
                            localFilePath = targetFile.absolutePath
                        } else {
                            failed.add(docId)
                        }
                    }

                    val newDoc = VaultDocument(
                        id = docId,
                        name = remoteVendor,
                        notes = remoteNotes,
                        fileName = localFileName,
                        filePath = localFilePath,
                        mimeType = mimeType,
                        date = remoteDate.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(remoteTimestamp)) },
                        isPinned = remotePinned,
                        driveFileId = remoteFileId,
                        driveFileUrl = remoteFileUrl,
                        isSynced = true,
                        timestamp = remoteTimestamp,
                        lastModified = remoteTimestamp
                    )
                    localList.add(newDoc)
                    addedCount++
                }
            }

            // 3. Handle local docs:
            // - If local doc was already synced (isSynced == true) but is missing from remoteMap, it was deleted from cloud! Delete it locally!
            // - If local doc has unsynced local changes (!isSynced), upload it to cloud!
            val finalDocs = mutableListOf<VaultDocument>()
            for (doc in localList) {
                if (deletedIds.contains(doc.id) || (!doc.driveFileId.isNullOrBlank() && deletedIds.contains(doc.driveFileId))) {
                    try { File(doc.filePath).delete() } catch (_: Exception) {}
                    continue
                }

                val existsRemotely = doc.id in matchedLocalIds || remoteMap.values.any { r ->
                    val rFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                    val rDocId = r.optString("docId").ifBlank { r.optString("docID") }
                    val rUrl = r.optString("driveFileUrl").ifBlank { r.optString("fileUrl") }
                    (doc.id.isNotBlank() && doc.id == rDocId &&
                        (!doc.id.startsWith("row_") || doc.driveFileId.isNullOrBlank())) ||
                    (!doc.driveFileId.isNullOrBlank() && doc.driveFileId == rFileId) ||
                    (!doc.driveFileUrl.isNullOrBlank() && doc.driveFileUrl == rUrl)
                }

                if (!existsRemotely) {
                    if (!doc.isSynced) {
                        // Brand-new document created on this phone that needs upload
                        val uploadedDoc = uploadDocumentToCloud(scriptUrl, doc)
                        if (uploadedDoc != null) {
                            finalDocs.add(uploadedDoc)
                            uploadedCount++
                        } else {
                            failed.add(doc.id)
                            finalDocs.add(doc)
                        }
                    } else {
                        // This file was previously synced, but NO LONGER EXISTS in the cloud!
                        // It was deleted from the shared cabinet, so purge it locally!
                        try { File(doc.filePath).delete() } catch (_: Exception) {}
                    }
                } else {
                    finalDocs.add(doc)
                }
            }

            saveDocuments(finalDocs)
            Result.success(SyncSummary(addedCount, uploadedCount, finalDocs.size, failed.size))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun deleteSharedRecord(scriptUrl: String, docId: String, fileId: String) {
        val payload = JSONObject().apply {
            put("action", "delete_receipt")
            if (fileId.isNotBlank()) put("fileId", fileId) else put("docId", docId)
        }
        val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(scriptUrl).post(body).build()
        httpClient.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Could not sync document removal (HTTP ${response.code})" }
            val result = JSONObject(response.body?.string().orEmpty())
            check(result.optString("status") == "success") {
                result.optString("message", "Could not sync document removal")
            }
        }
    }

    private fun syncSharedMetadata(
        scriptUrl: String,
        docId: String,
        fileId: String,
        name: String,
        notes: String,
        pinned: Boolean,
        updateDetails: Boolean,
        updatePin: Boolean
    ): Boolean {
        fun send(action: String, fields: JSONObject.() -> Unit): Boolean {
            val payload = JSONObject().apply {
                put("action", action)
                if (fileId.isNotBlank()) put("fileId", fileId) else put("docId", docId)
                fields()
            }
            return try {
                val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder().url(scriptUrl).post(body).build()
                httpClient.newCall(request).execute().use { response ->
                    response.isSuccessful &&
                        JSONObject(response.body?.string().orEmpty()).optString("status") == "success"
                }
            } catch (_: Exception) {
                false
            }
        }

        val detailsSaved = !updateDetails || send("update_receipt") {
            put("name", name)
            put("notes", notes)
        }
        val pinSaved = !updatePin || send("toggle_pin") { put("pinned", pinned) }
        return detailsSaved && pinSaved
    }

    private fun uploadDocumentToCloud(scriptUrl: String, doc: VaultDocument): VaultDocument? {
        return try {
            val file = File(doc.filePath)
            var base64Data = ""
            if (file.isFile) {
                val bytes = file.readBytes()
                base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
            } else if (doc.filePath.isNotBlank() || doc.driveFileId.isNullOrBlank()) {
                return null
            }

            val metaObj = JSONObject().apply {
                put("docId", doc.id)
                put("vendor", doc.name)
                put("name", doc.name)
                put("notes", doc.notes)
                put("labels", doc.notes)
                put("pinned", doc.isPinned)
                put("date", doc.date)
                put("timestamp", if (doc.timestamp > 0) doc.timestamp else System.currentTimeMillis())
            }

            val payload = JSONObject().apply {
                put("action", "save_receipt")
                put("fileBase64", base64Data)
                put("mimeType", doc.mimeType)
                put("fileName", doc.fileName)
                put("metadata", metaObj)
            }

            val reqBody = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(scriptUrl).post(reqBody).build()
            httpClient.newCall(req).execute().use { res ->
                val bodyStr = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val resJson = JSONObject(bodyStr)
                    if (resJson.optString("status") != "success") return null
                    val returnedFileId = resJson.optString("fileId").ifBlank { resJson.optString("fileID") }
                    val returnedFileUrl = resJson.optString("fileUrl").ifBlank { resJson.optString("driveFileUrl") }
                    doc.copy(
                        driveFileId = returnedFileId.takeIf { it.isNotBlank() } ?: doc.driveFileId,
                        driveFileUrl = returnedFileUrl.takeIf { it.isNotBlank() } ?: doc.driveFileUrl,
                        isSynced = true,
                        contentPending = false
                    )
                } else {
                    null
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private data class DownloadedFileData(val name: String, val mimeType: String, val bytes: ByteArray)

    private fun downloadFileContent(scriptUrl: String, fileId: String): DownloadedFileData? {
        return try {
            val url = "$scriptUrl?action=download_file&fileId=$fileId&_t=${System.currentTimeMillis()}"
            val req = Request.Builder().url(url).get().build()
            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val bodyStr = res.body?.string() ?: return null
                val json = JSONObject(bodyStr)
                if (json.optString("status") != "success") return null
                val base64 = json.getString("base64")
                val name = json.optString("name", "Document_$fileId.jpg")
                val mimeType = json.optString("mimeType", "image/jpeg")
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                DownloadedFileData(name, mimeType, bytes)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun downloadAndSaveFile(scriptUrl: String, fileId: String, fileName: String): String? {
        val downloaded = downloadFileContent(scriptUrl, fileId) ?: return null
        return try {
            val name = File(downloaded.name.ifBlank { fileName }).name
            val targetFile = File(docsDir, "Vault_${UUID.randomUUID()}_$name")
            targetFile.writeBytes(downloaded.bytes)
            targetFile.absolutePath
        } catch (_: Exception) {
            null
        }
    }
}

data class SyncSummary(
    val downloaded: Int,
    val uploaded: Int,
    val total: Int,
    val failed: Int
)
