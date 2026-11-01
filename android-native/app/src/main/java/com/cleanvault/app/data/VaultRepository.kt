package com.cleanvault.app.data

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
    val isSynced: Boolean = false
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

    fun getScriptUrl(): String {
        return prefs.getString("apps_script_url", "") ?: ""
    }

    fun setScriptUrl(url: String) {
        prefs.edit().putString("apps_script_url", url.trim()).apply()
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
                    isSynced = obj.optBoolean("isSynced", false)
                )
            )
        }
        return list.sortedWith(compareByDescending<VaultDocument> { it.isPinned }.thenByDescending { it.timestamp })
    }

    private fun saveDocuments(docs: List<VaultDocument>) {
        val array = JSONArray()
        docs.forEach { doc ->
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
            }
            array.put(obj)
        }
        prefs.edit().putString("documents_list", array.toString()).apply()
    }

    fun saveFileToVault(name: String, notes: String, isPinned: Boolean, sourceFile: File, mimeType: String): VaultDocument {
        val ext = sourceFile.extension.ifEmpty { if (mimeType.contains("pdf")) "pdf" else "jpg" }
        val targetFile = File(docsDir, "Vault_${System.currentTimeMillis()}.$ext")
        sourceFile.copyTo(targetFile, overwrite = true)

        val doc = VaultDocument(
            name = name.ifBlank { targetFile.nameWithoutExtension },
            notes = notes,
            fileName = targetFile.name,
            filePath = targetFile.absolutePath,
            mimeType = mimeType,
            isPinned = isPinned,
            isSynced = false
        )

        val current = getDocuments().toMutableList()
        current.add(0, doc)
        saveDocuments(current)
        return doc
    }

    fun togglePin(docId: String): List<VaultDocument> {
        val list = getDocuments().map {
            if (it.id == docId) it.copy(isPinned = !it.isPinned) else it
        }
        saveDocuments(list)
        return list
    }

    fun renameDocument(docId: String, newName: String, newNotes: String): List<VaultDocument> {
        val list = getDocuments().map {
            if (it.id == docId) it.copy(name = newName.ifBlank { it.name }, notes = newNotes) else it
        }
        saveDocuments(list)
        return list
    }

    fun deleteDocument(docId: String): List<VaultDocument> {
        val list = getDocuments().toMutableList()
        val target = list.find { it.id == docId }
        if (target != null) {
            try {
                File(target.filePath).delete()
            } catch (_: Exception) {}
            list.remove(target)
            saveDocuments(list)
        }
        return list
    }

    // --- Google Drive & Apps Script Sync Logic ---

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
        val scriptUrl = getScriptUrl()
        if (scriptUrl.isBlank()) {
            return@withContext Result.failure(Exception("Apps Script URL not set. Tap ⚙️ to configure shared Google Drive."))
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
            val remoteMap = mutableMapOf<String, JSONObject>()
            for (i in 0 until records.length()) {
                val r = records.getJSONObject(i)
                val rawDocId = r.optString("docId").ifBlank { r.optString("docID") }
                val remoteFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                val effectiveDocId = rawDocId.ifBlank {
                    if (remoteFileId.isNotBlank()) "file_$remoteFileId" else "row_${r.optInt("rowId")}"
                }
                remoteMap[effectiveDocId] = r
            }

            var localList = getDocuments().toMutableList()
            var addedCount = 0
            var uploadedCount = 0
            var updatedCount = 0

            // 2. Download or update remote records into local cabinet
            for ((docId, r) in remoteMap) {
                val remoteFileId = r.optString("fileId").ifBlank { r.optString("fileID") }
                val remoteVendor = r.optString("name").ifBlank { r.optString("vendor", "Document") }
                val remoteNotes = r.optString("labels").ifBlank { r.optString("notes", "") }
                val remoteDate = r.optString("date", "")
                val remoteFileUrl = r.optString("driveFileUrl").ifBlank { r.optString("driveFileURL").ifBlank { r.optString("fileUrl") } }
                val remotePinned = r.optBoolean("pinned", false)

                val existingIndex = localList.indexOfFirst {
                    it.id == docId ||
                    (it.driveFileId != null && remoteFileId.isNotBlank() && it.driveFileId == remoteFileId) ||
                    (it.name.isNotBlank() && it.name.equals(remoteVendor, ignoreCase = true) && it.date == remoteDate)
                }

                if (existingIndex >= 0) {
                    val existing = localList[existingIndex]
                    val fileOnDisk = File(existing.filePath)
                    // If file is missing locally, attempt download; never erase valid existing filePath!
                    val actualPath = if ((!fileOnDisk.exists() || existing.filePath.isBlank()) && remoteFileId.isNotBlank()) {
                        downloadAndSaveFile(scriptUrl, remoteFileId, existing.fileName) ?: existing.filePath
                    } else {
                        existing.filePath
                    }

                    localList[existingIndex] = existing.copy(
                        id = if (existing.id.startsWith("row_") && !docId.startsWith("row_")) docId else existing.id,
                        name = remoteVendor,
                        notes = remoteNotes,
                        isPinned = remotePinned,
                        driveFileId = remoteFileId.ifBlank { existing.driveFileId },
                        driveFileUrl = remoteFileUrl.ifBlank { existing.driveFileUrl },
                        filePath = actualPath,
                        isSynced = true
                    )
                    updatedCount++
                } else {
                    // Document exists remotely but not on this phone (e.g. spouse added it)
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
                        date = remoteDate.ifBlank { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()) },
                        isPinned = remotePinned,
                        driveFileId = remoteFileId,
                        driveFileUrl = remoteFileUrl,
                        isSynced = true
                    )
                    localList.add(newDoc)
                    addedCount++
                }
            }

            // 3. Handle local docs not in remote records
            val updatedLocalList = mutableListOf<VaultDocument>()
            for (doc in localList) {
                val isInCloud = remoteMap.containsKey(doc.id) ||
                                (doc.driveFileId != null && remoteMap.values.any { (it.optString("fileId").ifBlank { it.optString("fileID") }) == doc.driveFileId })

                if (isInCloud) {
                    updatedLocalList.add(doc)
                } else if (!doc.isSynced) {
                    // Local document was created on this phone and hasn't been uploaded yet
                    val uploadedDoc = uploadDocumentToCloud(scriptUrl, doc)
                    if (uploadedDoc != null) {
                        updatedLocalList.add(uploadedDoc)
                        uploadedCount++
                    } else {
                        updatedLocalList.add(doc) // Keep offline for next retry
                    }
                } else {
                    // Local document has a valid file on disk; keep it safe and never delete!
                    val fileOnDisk = File(doc.filePath)
                    if (fileOnDisk.exists() && fileOnDisk.length() > 0) {
                        // Re-upload to cloud in case it was missed
                        val uploadedDoc = uploadDocumentToCloud(scriptUrl, doc)
                        updatedLocalList.add(uploadedDoc ?: doc)
                    }
                }
            }

            saveDocuments(updatedLocalList)
            Result.success(SyncSummary(addedCount, uploadedCount, 0, updatedLocalList.size))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadDocAsync(doc: VaultDocument) = withContext(Dispatchers.IO) {
        val scriptUrl = getScriptUrl()
        if (scriptUrl.isBlank()) return@withContext
        val uploaded = uploadDocumentToCloud(scriptUrl, doc)
        if (uploaded != null) {
            val list = getDocuments().map { if (it.id == doc.id) uploaded else it }
            saveDocuments(list)
        }
    }

    suspend fun deleteDocRemoteAsync(docId: String) = withContext(Dispatchers.IO) {
        val scriptUrl = getScriptUrl()
        if (scriptUrl.isBlank()) return@withContext
        try {
            val jsonPayload = JSONObject().apply {
                put("action", "delete_receipt")
                put("docId", docId)
            }
            val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(scriptUrl).post(body).build()
            httpClient.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    suspend fun togglePinRemoteAsync(docId: String) = withContext(Dispatchers.IO) {
        val scriptUrl = getScriptUrl()
        if (scriptUrl.isBlank()) return@withContext
        try {
            val jsonPayload = JSONObject().apply {
                put("action", "toggle_pin")
                put("docId", docId)
            }
            val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(scriptUrl).post(body).build()
            httpClient.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    suspend fun updateDocRemoteAsync(docId: String, newName: String, newNotes: String) = withContext(Dispatchers.IO) {
        val scriptUrl = getScriptUrl()
        if (scriptUrl.isBlank()) return@withContext
        try {
            val jsonPayload = JSONObject().apply {
                put("action", "update_receipt")
                put("docId", docId)
                put("name", newName)
                put("labels", newNotes)
            }
            val body = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder().url(scriptUrl).post(body).build()
            httpClient.newCall(req).execute().close()
        } catch (_: Exception) {}
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
                put("notes", doc.notes)
                put("labels", doc.notes)
                put("pinned", doc.isPinned)
                put("date", doc.date)
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
