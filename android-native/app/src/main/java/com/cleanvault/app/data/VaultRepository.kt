package com.cleanvault.app.data

import android.accounts.Account
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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
    val lastModified: Long = System.currentTimeMillis()
)

class VaultRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("clean_vault_meta", Context.MODE_PRIVATE)
    private val docsDir = File(context.filesDir, "vault_documents").apply { mkdirs() }

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

    private fun addDeletedId(id: String) {
        if (id.isBlank()) return
        val current = getDeletedIds().toMutableSet()
        current.add(id.trim())
        prefs.edit().putStringSet("deleted_doc_ids", current).apply()
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
        val genericNames = setOf("document", "doc", "unknown", "receipt", "scan", "vaultdocument", "")
        for (doc in valid) {
            val cleanName = doc.name.trim().lowercase()
            val isGeneric = genericNames.contains(cleanName) || cleanName.matches(Regex("document \\d+.*"))
            val key = if (cleanName.isNotBlank() && !isGeneric) {
                "name_$cleanName"
            } else if (!doc.driveFileId.isNullOrBlank()) {
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
                if (loser.filePath != winner.filePath && loser.filePath.isNotBlank()) {
                    try { File(loser.filePath).delete() } catch (_: Exception) {}
                }
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

        fun clearAllDocuments() {
        val current = getDocuments()
        for (doc in current) {
            try { File(doc.filePath).delete() } catch (_: Exception) {}
        }
        prefs.edit().putString("documents_list", "[]").apply()
    }

    fun getDocuments(): List<VaultDocument> {
        val jsonStr = prefs.getString("documents_list", "[]") ?: "[]"
        val array = JSONArray(jsonStr)
        val list = mutableListOf<VaultDocument>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            list.add(
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
                    lastModified = obj.optLong("lastModified", obj.optLong("timestamp", 0L))
                )
            )
        }
        val deduped = deduplicateList(list)
        return deduped.sortedWith(
            compareByDescending<VaultDocument> { if (it.timestamp > 0) it.timestamp else it.lastModified }
                .thenByDescending { it.date }
                .thenBy { it.name.lowercase() }
        )
    }

    private fun saveDocuments(docs: List<VaultDocument>) {
        val deduped = deduplicateList(docs)
        val array = JSONArray()
        deduped.forEach { doc ->
            val obj = JSONObject().apply {
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
            }
            array.put(obj)
        }
        prefs.edit().putString("documents_list", array.toString()).apply()
    }

    fun saveFileToVault(name: String, notes: String, isPinned: Boolean, sourceFile: File, mimeType: String): VaultDocument {
        val ext = sourceFile.extension.ifEmpty { if (mimeType.contains("pdf")) "pdf" else if (mimeType.contains("text")) "txt" else "jpg" }
        val targetFile = File(docsDir, "Vault_${System.currentTimeMillis()}.$ext")
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
        return doc
    }

    fun togglePin(docId: String): List<VaultDocument> {
        val list = getDocuments().map {
            if (it.id == docId) it.copy(isPinned = !it.isPinned, isSynced = false, lastModified = System.currentTimeMillis()) else it
        }
        saveDocuments(list)
        return list
    }

    fun renameDocument(docId: String, newName: String, newNotes: String): List<VaultDocument> {
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
        return list
    }

    fun updateNoteContent(docId: String, newContent: String): VaultDocument? {
        val list = getDocuments().toMutableList()
        val index = list.indexOfFirst { it.id == docId }
        if (index == -1) return null
        val target = list[index]
        val file = File(target.filePath)
        try {
            file.writeText(newContent)
        } catch (_: Exception) {}
        val updated = target.copy(
            isSynced = false,
            lastModified = System.currentTimeMillis()
        )
        list[index] = updated
        saveDocuments(list)
        return updated
    }

    fun deleteDocument(docId: String): List<VaultDocument> {
        val list = getDocuments().toMutableList()
        val target = list.find { it.id == docId }
        if (target != null) {
            addDeletedId(target.id)
            if (!target.driveFileId.isNullOrBlank()) {
                addDeletedId(target.driveFileId!!)
            }
            try {
                File(target.filePath).delete()
            } catch (_: Exception) {}
            list.remove(target)
            saveDocuments(list)
        }
        return list
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

    suspend fun syncWithCloud(): Result<SyncSummary> = withContext(Dispatchers.IO) {
        when (getActiveCloudMode()) {
            CloudSyncMode.SHARED_CABINET -> syncAppsScriptCloud()
            CloudSyncMode.GOOGLE_DRIVE -> syncDirectGoogleDrive()
            CloudSyncMode.LOCAL_ONLY -> Result.failure(Exception("Cloud sync is disabled (Local Only mode)."))
        }
    }

    suspend fun syncDirectGoogleDrive(): Result<SyncSummary> = withContext(Dispatchers.IO) {
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
                    googleDriveService.deleteFile(token, fId)
                    continue
                }
                remoteMap[effectiveKey] = rf
            }

            var localList = getDocuments().toMutableList()
            var addedCount = 0
            var uploadedCount = 0

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
                    it.driveFileId == fId || it.id == propDocId || (it.driveFileUrl?.isNotBlank() == true && it.driveFileUrl == rUrl) ||
                    (it.name.isNotBlank() && it.name.trim().equals(rName.trim(), ignoreCase = true))
                }

                if (existingIndex >= 0) {
                    val existing = localList[existingIndex]
                    val fileOnDisk = File(existing.filePath)
                    val actualPath = if (!fileOnDisk.exists() || existing.filePath.isBlank()) {
                        val ext = if (rMime.contains("pdf")) "pdf" else if (rMime.contains("text")) "txt" else "jpg"
                        val target = File(docsDir, "Vault_${System.currentTimeMillis()}_${existing.fileName.ifBlank { "doc.$ext" }}")
                        val dlResult = googleDriveService.downloadFile(token, fId, target)
                        if (dlResult.isSuccess) target.absolutePath else existing.filePath
                    } else {
                        existing.filePath
                    }

                    val hasUnsyncedLocalEdits = !existing.isSynced
                    val finalPinned = if (hasUnsyncedLocalEdits) existing.isPinned else propPinned
                    val finalName = if (hasUnsyncedLocalEdits && existing.name.isNotBlank()) existing.name else existing.name.ifBlank { rName }
                    val finalNotes = if (hasUnsyncedLocalEdits && existing.notes.isNotBlank()) existing.notes else if (existing.notes.isBlank()) propNotes else existing.notes

                    localList[existingIndex] = existing.copy(
                        name = finalName,
                        notes = finalNotes,
                        isPinned = finalPinned,
                        driveFileId = fId,
                        driveFileUrl = rUrl,
                        filePath = actualPath,
                        isSynced = true,
                        timestamp = if (remoteTimestamp > 0) remoteTimestamp else existing.timestamp
                    )
                } else {
                    val ext = if (rMime.contains("pdf")) "pdf" else if (rMime.contains("text")) "txt" else "jpg"
                    val target = File(docsDir, "Vault_${System.currentTimeMillis()}_$rName.$ext")
                    val dlResult = googleDriveService.downloadFile(token, fId, target)
                    val localPath = if (dlResult.isSuccess) target.absolutePath else ""

                    val newDoc = VaultDocument(
                        id = propDocId,
                        name = rName.substringBeforeLast('.'),
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

                val existsInDrive = remoteFiles.any { rf ->
                    val fId = rf.getString("id")
                    val props = rf.optJSONObject("properties")
                    val propDocId = props?.optString("docId", "")
                    doc.driveFileId == fId || (propDocId != null && propDocId == doc.id)
                }

                if (!existsInDrive) {
                    val localFile = File(doc.filePath)
                    if (localFile.exists() && localFile.length() > 0) {
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
                            finalDocs.add(doc.copy(driveFileId = fId, driveFileUrl = link, isSynced = true))
                            uploadedCount++
                        } else {
                            finalDocs.add(doc)
                        }
                    } else {
                        finalDocs.add(doc)
                    }
                } else {
                    finalDocs.add(doc.copy(isSynced = true))
                }
            }

            saveDocuments(finalDocs)
            Result.success(SyncSummary(addedCount, uploadedCount, 0, finalDocs.size))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun syncAppsScriptCloud(): Result<SyncSummary> = withContext(Dispatchers.IO) {
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

            // Group remote records by normalized document name to eliminate duplicate rows in Google Sheet
            val rawRemoteList = mutableListOf<JSONObject>()
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                val rawDocId = r.optString("docId").ifBlank { r.optString("docID") }
                val remoteFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                val remoteName = r.optString("name").ifBlank { r.optString("vendor", "") }
                val effectiveDocId = rawDocId.ifBlank {
                    if (remoteFileId.isNotBlank()) "file_$remoteFileId" else "row_${r.optInt("rowId")}"
                }

                if (deletedIds.contains(effectiveDocId) || (remoteFileId.isNotBlank() && deletedIds.contains(remoteFileId))) {
                    deleteDocRemoteAsync(effectiveDocId, remoteFileId, remoteName)
                    continue
                }
                rawRemoteList.add(r)
            }

            // Map remote records: never auto-delete rows from cloud during fetch
            val remoteMap = LinkedHashMap<String, JSONObject>()
            val genericNames = setOf("document", "doc", "unknown", "receipt", "scan", "vaultdocument", "")
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

            // 2. Merge each remote record with matching local document
            for ((docId, r) in remoteMap) {
                val remoteFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                val remoteVendor = r.optString("name").ifBlank { r.optString("vendor", "Document") }
                val remoteNotes = r.optString("labels").ifBlank { r.optString("notes", "") }
                val remoteDate = r.optString("date", "")
                val remoteFileUrl = r.optString("driveFileUrl").ifBlank { r.optString("driveFileURL").ifBlank { r.optString("fileUrl") } }
                val remotePinned = r.optBoolean("pinned", false)
                val remoteTimestamp = parseTimestamp(r.opt("timestamp"), remoteDate)

                // Match by ID, File ID, Drive URL, OR Document Name (case-insensitive)
                val existingIndex = localList.indexOfFirst {
                    it.id == docId ||
                    (remoteFileId.isNotBlank() && it.driveFileId == remoteFileId) ||
                    (remoteFileUrl.isNotBlank() && it.driveFileUrl == remoteFileUrl) ||
                    (it.name.isNotBlank() && remoteVendor.isNotBlank() && it.name.trim().equals(remoteVendor.trim(), ignoreCase = true))
                }

                if (existingIndex >= 0) {
                    val existing = localList[existingIndex]
                    val fileOnDisk = File(existing.filePath)
                    val actualPath = if ((!fileOnDisk.exists() || existing.filePath.isBlank()) && remoteFileId.isNotBlank()) {
                        downloadAndSaveFile(scriptUrl, remoteFileId, existing.fileName) ?: existing.filePath
                    } else {
                        existing.filePath
                    }

                    // Conflict resolution:
                    // If local has unsynced user edits (!existing.isSynced), local wins and updates remote.
                    // Otherwise, remote cloud state wins!
                    val hasUnsyncedLocalEdits = !existing.isSynced
                    val finalName = if (hasUnsyncedLocalEdits && existing.name.isNotBlank()) existing.name else remoteVendor
                    val finalNotes = if (hasUnsyncedLocalEdits) existing.notes else remoteNotes
                    val finalPinned = if (hasUnsyncedLocalEdits) existing.isPinned else remotePinned

                    if (hasUnsyncedLocalEdits) {
                        if (existing.name != remoteVendor || existing.notes != remoteNotes) {
                            updateDocRemoteAsync(existing.copy(driveFileId = remoteFileId.ifBlank { existing.driveFileId }), finalName, finalNotes)
                        }
                        if (existing.isPinned != remotePinned) {
                            togglePinRemoteAsync(existing.copy(isPinned = finalPinned, driveFileId = remoteFileId.ifBlank { existing.driveFileId }))
                        }
                    }

                    localList[existingIndex] = existing.copy(
                        id = if (existing.id.startsWith("row_") && !docId.startsWith("row_")) docId else existing.id,
                        name = finalName,
                        notes = finalNotes,
                        isPinned = finalPinned,
                        driveFileId = remoteFileId.ifBlank { existing.driveFileId },
                        driveFileUrl = remoteFileUrl.ifBlank { existing.driveFileUrl },
                        filePath = actualPath,
                        isSynced = true,
                        timestamp = if (remoteTimestamp > 0) remoteTimestamp else existing.timestamp
                    )
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

                val existsRemotely = remoteMap.values.any { r ->
                    val rFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                    val rDocId = r.optString("docId").ifBlank { r.optString("docID") }
                    val rUrl = r.optString("driveFileUrl").ifBlank { r.optString("fileUrl") }
                    val rName = r.optString("name").ifBlank { r.optString("vendor") }
                    (doc.id.isNotBlank() && doc.id == rDocId) ||
                    (!doc.driveFileId.isNullOrBlank() && doc.driveFileId == rFileId) ||
                    (!doc.driveFileUrl.isNullOrBlank() && doc.driveFileUrl == rUrl) ||
                    (doc.name.isNotBlank() && doc.name.trim().equals(rName.trim(), ignoreCase = true))
                }

                if (!existsRemotely) {
                    if (!doc.isSynced) {
                        // Brand-new document created on this phone that needs upload
                        val uploadedDoc = uploadDocumentToCloud(scriptUrl, doc)
                        if (uploadedDoc != null) {
                            finalDocs.add(uploadedDoc)
                            uploadedCount++
                        } else {
                            finalDocs.add(doc)
                        }
                    } else {
                        // This file was previously synced, but NO LONGER EXISTS in the cloud!
                        // It was deleted from the shared cabinet, so purge it locally!
                        try { File(doc.filePath).delete() } catch (_: Exception) {}
                    }
                } else {
                    finalDocs.add(doc.copy(isSynced = true))
                }
            }

            saveDocuments(finalDocs)
            Result.success(SyncSummary(addedCount, uploadedCount, 0, finalDocs.size))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadDocAsync(doc: VaultDocument): VaultDocument? = withContext(Dispatchers.IO) {
        when (getActiveCloudMode()) {
            CloudSyncMode.SHARED_CABINET -> {
                val scriptUrl = getScriptUrl()
                if (scriptUrl.isBlank()) return@withContext null
                val uploaded = uploadDocumentToCloud(scriptUrl, doc)
                if (uploaded != null) {
                    val list = getDocuments().map { if (it.id == doc.id) uploaded else it }
                    saveDocuments(list)
                }
                uploaded
            }
            CloudSyncMode.GOOGLE_DRIVE -> {
                try {
                    val email = getGoogleEmail()
                    val account = Account(email, "com.google")
                    val token = googleDriveService.getAccessToken(account) ?: return@withContext null
                    var folderId = getGoogleFolderId()
                    if (folderId.isBlank()) {
                        folderId = googleDriveService.findOrCreateAppFolder(token).getOrNull() ?: return@withContext null
                        setGoogleFolderId(folderId)
                    }
                    val localFile = File(doc.filePath)
                    if (!localFile.exists()) return@withContext null

                    val res = googleDriveService.uploadDocument(
                        token = token,
                        folderId = folderId,
                        file = localFile,
                        docName = doc.name,
                        notes = doc.notes,
                        mimeType = doc.mimeType,
                        docId = doc.id,
                        isPinned = doc.isPinned
                    )
                    if (res.isSuccess) {
                        val (fId, link) = res.getOrThrow()
                        val updated = doc.copy(driveFileId = fId, driveFileUrl = link, isSynced = true)
                        val list = getDocuments().map { if (it.id == doc.id) updated else it }
                        saveDocuments(list)
                        return@withContext updated
                    }
                } catch (_: Exception) {}
                null
            }
            CloudSyncMode.LOCAL_ONLY -> null
        }
    }

    suspend fun deleteDocRemoteAsync(docId: String, fileId: String? = null, name: String? = null) = withContext(Dispatchers.IO) {
        when (getActiveCloudMode()) {
            CloudSyncMode.SHARED_CABINET -> {
                val scriptUrl = getScriptUrl()
                if (scriptUrl.isBlank()) return@withContext
                try {
                    val jsonPayload = JSONObject().apply {
                        put("action", "delete_receipt")
                        put("docId", docId)
                        if (!fileId.isNullOrBlank()) put("fileId", fileId)
                        if (!name.isNullOrBlank()) put("name", name)
                        if (!name.isNullOrBlank()) put("vendor", name)
                    }
                    val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val req = Request.Builder().url(scriptUrl).post(body).build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}
            }
            CloudSyncMode.GOOGLE_DRIVE -> {
                if (!fileId.isNullOrBlank()) {
                    try {
                        val email = getGoogleEmail()
                        val account = Account(email, "com.google")
                        val token = googleDriveService.getAccessToken(account) ?: return@withContext
                        googleDriveService.deleteFile(token, fileId)
                    } catch (_: Exception) {}
                }
            }
            CloudSyncMode.LOCAL_ONLY -> {}
        }
    }

    suspend fun togglePinRemoteAsync(doc: VaultDocument) = withContext(Dispatchers.IO) {
        when (getActiveCloudMode()) {
            CloudSyncMode.SHARED_CABINET -> {
                val scriptUrl = getScriptUrl()
                if (scriptUrl.isBlank()) return@withContext
                try {
                    val jsonPayload = JSONObject().apply {
                        put("action", "toggle_pin")
                        put("docId", doc.id)
                        put("name", doc.name)
                        put("vendor", doc.name)
                        if (!doc.driveFileId.isNullOrBlank()) put("fileId", doc.driveFileId)
                        if (!doc.driveFileUrl.isNullOrBlank()) put("driveFileUrl", doc.driveFileUrl)
                        put("pinned", doc.isPinned)
                    }
                    val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val req = Request.Builder().url(scriptUrl).post(body).build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}
            }
            CloudSyncMode.GOOGLE_DRIVE -> {
                if (!doc.driveFileId.isNullOrBlank()) {
                    try {
                        val email = getGoogleEmail()
                        val account = Account(email, "com.google")
                        val token = googleDriveService.getAccessToken(account) ?: return@withContext
                        googleDriveService.updateFileProperties(token, doc.driveFileId, mapOf("isPinned" to doc.isPinned.toString()))
                    } catch (_: Exception) {}
                }
            }
            CloudSyncMode.LOCAL_ONLY -> {}
        }
    }

    suspend fun updateDocRemoteAsync(doc: VaultDocument, newName: String, newNotes: String) = withContext(Dispatchers.IO) {
        when (getActiveCloudMode()) {
            CloudSyncMode.SHARED_CABINET -> {
                val scriptUrl = getScriptUrl()
                if (scriptUrl.isBlank()) return@withContext
                try {
                    val jsonPayload = JSONObject().apply {
                        put("action", "update_receipt")
                        put("docId", doc.id)
                        if (!doc.driveFileId.isNullOrBlank()) put("fileId", doc.driveFileId)
                        if (!doc.driveFileUrl.isNullOrBlank()) put("driveFileUrl", doc.driveFileUrl)
                        put("name", newName)
                        put("labels", newNotes)
                        put("notes", newNotes)
                    }
                    val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                    val req = Request.Builder().url(scriptUrl).post(body).build()
                    httpClient.newCall(req).execute().close()
                } catch (_: Exception) {}
            }
            CloudSyncMode.GOOGLE_DRIVE -> {
                if (!doc.driveFileId.isNullOrBlank()) {
                    try {
                        val email = getGoogleEmail()
                        val account = Account(email, "com.google")
                        val token = googleDriveService.getAccessToken(account) ?: return@withContext
                        googleDriveService.updateFileProperties(token, doc.driveFileId, mapOf("notes" to newNotes))
                    } catch (_: Exception) {}
                }
            }
            CloudSyncMode.LOCAL_ONLY -> {}
        }
    }

    suspend fun updateNoteRemoteAsync(doc: VaultDocument, newContent: String) = withContext(Dispatchers.IO) {
        val file = File(doc.filePath)
        try {
            file.writeText(newContent)
        } catch (_: Exception) {}

        when (getActiveCloudMode()) {
            CloudSyncMode.SHARED_CABINET -> {
                val scriptUrl = getScriptUrl()
                if (scriptUrl.isNotBlank()) {
                    uploadDocumentToCloud(scriptUrl, doc)
                }
            }
            CloudSyncMode.GOOGLE_DRIVE -> {
                if (!doc.driveFileId.isNullOrBlank()) {
                    try {
                        val email = getGoogleEmail()
                        val account = Account(email, "com.google")
                        val token = googleDriveService.getAccessToken(account) ?: return@withContext
                        googleDriveService.updateFileContent(token, doc.driveFileId!!, file, "text/plain")
                    } catch (_: Exception) {}
                }
            }
            CloudSyncMode.LOCAL_ONLY -> {}
        }
    }

    private fun uploadDocumentToCloud(scriptUrl: String, doc: VaultDocument): VaultDocument? {
        return try {
            val file = File(doc.filePath)
            var base64Data = ""
            if (file.exists() && file.length() > 0) {
                val bytes = file.readBytes()
                base64Data = Base64.encodeToString(bytes, Base64.NO_WRAP)
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
                if (res.isSuccessful && bodyStr.contains("success")) {
                    val resJson = JSONObject(bodyStr)
                    val returnedFileId = resJson.optString("fileId").ifBlank { resJson.optString("fileID") }
                    val returnedFileUrl = resJson.optString("fileUrl").ifBlank { resJson.optString("driveFileUrl") }
                    doc.copy(
                        driveFileId = returnedFileId.takeIf { it.isNotBlank() } ?: doc.driveFileId,
                        driveFileUrl = returnedFileUrl.takeIf { it.isNotBlank() } ?: doc.driveFileUrl,
                        isSynced = true
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
            val targetFile = File(docsDir, "Vault_${System.currentTimeMillis()}_${downloaded.name.ifBlank { fileName }}")
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
    val deleted: Int,
    val total: Int
)
