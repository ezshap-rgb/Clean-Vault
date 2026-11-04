package com.cleanvault.app.data

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.GoogleAuthUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class GoogleDriveService(private val context: Context) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.file"

    suspend fun getAccessToken(account: Account): String? = withContext(Dispatchers.IO) {
        try {
            GoogleAuthUtil.getToken(context, account, DRIVE_SCOPE)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun findOrCreateAppFolder(token: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Search for existing folder named "Clean Filing" (either owned or shared with user)
            val query = "name = 'Clean Filing' and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
            val searchUrl = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,owners)&spaces=drive"

            val searchReq = Request.Builder()
                .url(searchUrl)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(searchReq).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val json = JSONObject(body)
                    val files = json.optJSONArray("files") ?: JSONArray()
                    if (files.length() > 0) {
                        val folderId = files.getJSONObject(0).getString("id")
                        return@withContext Result.success(folderId)
                    }
                }
            }

            // Create new "Clean Filing" folder if not found
            val createUrl = "https://www.googleapis.com/drive/v3/files"
            val metaJson = JSONObject().apply {
                put("name", "Clean Filing")
                put("mimeType", "application/vnd.google-apps.folder")
                put("description", "Clean Filing Document Storage")
            }

            val createReq = Request.Builder()
                .url(createUrl)
                .addHeader("Authorization", "Bearer $token")
                .post(metaJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            httpClient.newCall(createReq).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val folderId = JSONObject(body).getString("id")
                    Result.success(folderId)
                } else {
                    Result.failure(Exception("Failed to create folder: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun uploadDocument(
        token: String,
        folderId: String,
        file: File,
        docName: String,
        notes: String,
        mimeType: String,
        docId: String,
        isPinned: Boolean
    ): Result<Pair<String, String>> = withContext(Dispatchers.IO) {
        try {
            val uploadUrl = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,webViewLink"

            val metadata = JSONObject().apply {
                put("name", docName.ifBlank { file.name })
                put("parents", JSONArray().put(folderId))
                put("description", notes)
                put("properties", JSONObject().apply {
                    put("docId", docId)
                    put("notes", notes)
                    put("isPinned", isPinned.toString())
                    put("client", "CleanFiling")
                })
            }

            val multipartBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "metadata",
                    null,
                    metadata.toString().toRequestBody("application/json; charset=UTF-8".toMediaType())
                )
                .addFormDataPart(
                    "file",
                    file.name,
                    file.asRequestBody(mimeType.toMediaType())
                )
                .build()

            val req = Request.Builder()
                .url(uploadUrl)
                .addHeader("Authorization", "Bearer $token")
                .post(multipartBody)
                .build()

            httpClient.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val json = JSONObject(body)
                    val id = json.getString("id")
                    val link = json.optString("webViewLink", "https://drive.google.com/file/d/$id/view")
                    Result.success(Pair(id, link))
                } else {
                    Result.failure(Exception("Upload failed: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateFileContent(token: String, fileId: String, file: File, mimeType: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.googleapis.com/upload/drive/v3/files/$fileId?uploadType=media"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .patch(file.asRequestBody(mimeType.toMediaType()))
                .build()

            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) {
                    Result.success(true)
                } else {
                    val body = res.body?.string() ?: ""
                    Result.failure(Exception("Update failed: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateFileProperties(token: String, fileId: String, properties: Map<String, String>): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId?fields=id,properties"
            val json = JSONObject().apply {
                val propObj = JSONObject()
                properties.forEach { (k, v) -> propObj.put(k, v) }
                put("properties", propObj)
            }
            val body = json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .patch(body)
                .build()

            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful) Result.success(true) else Result.failure(Exception("Property update failed: ${res.body?.string()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun listDocuments(token: String, folderId: String): Result<List<JSONObject>> = withContext(Dispatchers.IO) {
        try {
            val query = "'$folderId' in parents and trashed = false and mimeType != 'application/vnd.google-apps.folder'"
            val url = "https://www.googleapis.com/drive/v3/files?q=${java.net.URLEncoder.encode(query, "UTF-8")}&fields=files(id,name,mimeType,description,properties,webViewLink,createdTime,modifiedTime)&pageSize=200"

            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { res ->
                val body = res.body?.string() ?: ""
                if (res.isSuccessful) {
                    val json = JSONObject(body)
                    val filesArray = json.optJSONArray("files") ?: JSONArray()
                    val result = mutableListOf<JSONObject>()
                    for (i in 0 until filesArray.length()) {
                        result.add(filesArray.getJSONObject(i))
                    }
                    Result.success(result)
                } else {
                    Result.failure(Exception("Failed to list files: $body"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadFile(token: String, fileId: String, destFile: File): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            httpClient.newCall(req).execute().use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Download failed: HTTP ${res.code}"))
                }
                res.body?.byteStream()?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(destFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteFile(token: String, fileId: String): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.googleapis.com/drive/v3/files/$fileId"
            val req = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            httpClient.newCall(req).execute().use { res ->
                if (res.isSuccessful || res.code == 404) {
                    Result.success(true)
                } else {
                    Result.failure(Exception("Delete failed: HTTP ${res.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

