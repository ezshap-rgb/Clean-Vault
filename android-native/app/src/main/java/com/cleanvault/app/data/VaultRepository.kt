package com.cleanvault.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class VaultDocument(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val notes: String = "",
    val fileName: String,
    val filePath: String,
    val mimeType: String,
    val date: String = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()),
    val isPinned: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

class VaultRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("clean_vault_meta", Context.MODE_PRIVATE)
    private val docsDir = File(context.filesDir, "vault_documents").apply { mkdirs() }

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
                    timestamp = obj.optLong("timestamp", 0L)
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
            }
            array.put(obj)
        }
        prefs.edit().putString("documents_list", array.toString()).apply()
    }

    fun saveFileToVault(name: String, notes: String, isPinned: Boolean, sourceFile: File, mimeType: String): VaultDocument {
        val ext = sourceFile.extension.ifEmpty { "jpg" }
        val targetFile = File(docsDir, "Vault_${System.currentTimeMillis()}.$ext")
        sourceFile.copyTo(targetFile, overwrite = true)

        val doc = VaultDocument(
            name = name.ifBlank { targetFile.nameWithoutExtension },
            notes = notes,
            fileName = targetFile.name,
            filePath = targetFile.absolutePath,
            mimeType = mimeType,
            isPinned = isPinned
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
}

