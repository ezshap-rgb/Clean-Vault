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
    GOOGLE_DRIVE
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
                if (isGoogleDriveConnected()) CloudSyncMode.GOOGLE_DRIVE else CloudSyncMode.LOCAL_ONLY
            }
        }
        return if (isGoogleDriveConnected()) CloudSyncMode.GOOGLE_DRIVE else CloudSyncMode.LOCAL_ONLY
    }

    fun setActiveCloudMode(mode: CloudSyncMode) {
        prefs.edit().putString("active_cloud_mode", mode.name).apply()
    }

    fun isGoogleDriveConnected(): Boolean {
        return getGoogleEmail().isNotBlank()
    }

    fun hasCloudConnection(): Boolean {
        return getActiveCloudMode() == CloudSyncMode.GOOGLE_DRIVE && isGoogleDriveConnected()
    }

    private fun syncStatusKey(): String = when (getActiveCloudMode()) {
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
            editor.putString("active_cloud_mode", CloudSyncMode.LOCAL_ONLY.name)
        } else {
            editor.putString("google_account_email", email.trim())
            if (!folderId.isNullOrBlank()) {
                editor.putString("google_drive_folder_id", folderId.trim())
            }
            editor.putString("active_cloud_mode", CloudSyncMode.GOOGLE_DRIVE.name)
        }
        editor.apply()
    }

    fun getGoogleFolderId(): String {
        return prefs.getString("google_drive_folder_id", "") ?: ""
    }

    fun setGoogleFolderId(folderId: String) {
        prefs.edit().putString("google_drive_folder_id", folderId.trim()).apply()
    }

    fun getCabinetOwnerEmail(): String {
        return prefs.getString("cabinet_owner_email", "") ?: ""
    }

    fun setCabinetOwnerEmail(email: String) {
        val trimmed = email.trim()
        val old = getCabinetOwnerEmail()
        if (old != trimmed) {
            prefs.edit().putString("cabinet_owner_email", trimmed).apply()
            setGoogleFolderId("")
        }
    }

    suspend fun shareGoogleDriveCabinet(partnerEmail: String): Result<String> = withContext(Dispatchers.IO) {
        val email = getGoogleEmail()
        if (email.isBlank()) return@withContext Result.failure(Exception("Please sign into Google Drive first."))
        val account = Account(email, "com.google")
        val token = googleDriveService.getAccessToken(account)
            ?: return@withContext Result.failure(Exception("Could not obtain Google Drive access token. Please re-sign in."))

        var folderId = getGoogleFolderId()
        if (folderId.isBlank()) {
            val folderRes = googleDriveService.findOrCreateAppFolder(token)
            if (folderRes.isFailure) return@withContext Result.failure(folderRes.exceptionOrNull() ?: Exception("Folder error"))
            folderId = folderRes.getOrThrow()
            setGoogleFolderId(folderId)
        }

        val shareRes = googleDriveService.shareFolder(token, folderId, partnerEmail.trim())
        if (shareRes.isSuccess) {
            Result.success(folderId)
        } else {
            Result.failure(shareRes.exceptionOrNull() ?: Exception("Failed to share folder"))
        }
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
        if (getActiveCloudMode() != CloudSyncMode.GOOGLE_DRIVE) return null
        val path = downloadGoogleDriveFile(fileId, doc.fileName) ?: return null
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

    suspend fun syncWithCloud(): Result<SyncSummary> = cloudMutex.withLock {
        withContext(Dispatchers.IO) {
            val mode = getActiveCloudMode()
            val key = syncStatusKey()
            val result = when (mode) {
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

            // 1. Get or Create "Clean Filing" folder (optionally under a linked owner's cabinet)
            val targetOwner = getCabinetOwnerEmail().trim().ifBlank { null }
            val folderResult = googleDriveService.findOrCreateAppFolder(token, targetOwner)
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


    suspend fun downloadGoogleDriveFile(fileId: String, fileName: String): String? = withContext(Dispatchers.IO) {
        val email = getGoogleEmail()
        if (email.isBlank()) return@withContext null
        val token = googleDriveService.getAccessToken(Account(email, "com.google")) ?: return@withContext null
        val name = File(fileName.ifBlank { "document.bin" }).name
        val target = File(docsDir, "Vault_${UUID.randomUUID()}_$name")
        val res = googleDriveService.downloadFile(token, fileId, target)
        if (res.isSuccess) target.absolutePath else null
    }
}

data class SyncSummary(
    val downloaded: Int,
    val uploaded: Int,
    val total: Int,
    val failed: Int
)
