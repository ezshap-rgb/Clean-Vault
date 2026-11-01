package com.cleanvault.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import coil.compose.AsyncImage
import com.cleanvault.app.data.VaultDocument
import com.cleanvault.app.data.VaultRepository
import com.cleanvault.app.theme.*
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CleanVaultTheme {
                MainScreen()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val repository = remember { VaultRepository(context) }
    var documents by remember { mutableStateOf(repository.getDocuments()) }

    var selectedTempFile by remember { mutableStateOf<File?>(null) }
    var selectedMimeType by remember { mutableStateOf("image/jpeg") }

    var docNameText by remember { mutableStateOf("") }
    var notesText by remember { mutableStateOf("") }
    var isPinned by remember { mutableStateOf(false) }

    var showCabinetModal by remember { mutableStateOf(false) }
    var cabinetSearchQuery by remember { mutableStateOf("") }

    // Speech recognition launchers
    val speechLauncherName = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                docNameText = if (docNameText.isBlank()) spoken else "$docNameText $spoken"
            }
        }
    }

    val speechLauncherNotes = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                notesText = if (notesText.isBlank()) spoken else "$notesText $spoken"
            }
        }
    }

    fun startSpeech(isForName: Boolean) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (isForName) "Speak Document Name" else "Speak Notes / Labels")
        }
        try {
            if (isForName) speechLauncherName.launch(intent) else speechLauncherNotes.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    // Camera Capture
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraTempFile != null) {
            selectedTempFile = cameraTempFile
            selectedMimeType = "image/jpeg"
        }
    }

    // File Picker (Photos, PDFs, Docs)
    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            try {
                val type = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val ext = if (type.contains("pdf")) "pdf" else if (type.contains("png")) "png" else "jpg"
                val temp = File(context.cacheDir, "picked_file_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                selectedTempFile = temp
                selectedMimeType = type
                if (docNameText.isBlank()) {
                    docNameText = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: ""
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Could not open file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val gradientBrush = Brush.linearGradient(listOf(AccentColor, AccentMid, WarningColor))
    val fileCardBrush = Brush.linearGradient(listOf(Color(0xFF101530), Color(0xFF0C1024)))
    val retrieveCardBrush = Brush.linearGradient(listOf(Color(0xFF1C1028), Color(0xFF120B1C)))

    Scaffold(
        containerColor = BgBase,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceColor)
                    .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.size(36.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = AccentColor
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    }
                    Text(text = "Clean Vault", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = TextMain)
                }
                Surface(
                    shape = CircleShape,
                    color = EmeraldGreen,
                    modifier = Modifier.size(8.dp)
                ) {}
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Spacer(modifier = Modifier.height(4.dp))

            // CARD 1: FILE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(fileCardBrush)
                    .border(1.dp, AccentColor.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Folder, contentDescription = null, tint = AmberGold, modifier = Modifier.size(18.dp))
                    Text("File", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
                }

                // Camera Tap Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(SurfaceColor.copy(alpha = 0.6f))
                        .border(1.dp, BorderHighlight, RoundedCornerShape(16.dp))
                        .clickable {
                            val temp = File(context.cacheDir, "camera_snap_${System.currentTimeMillis()}.jpg")
                            cameraTempFile = temp
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
                            cameraTempUri = uri
                            cameraLauncher.launch(uri)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedTempFile != null) {
                        if (selectedMimeType.contains("pdf")) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Filled.Description, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
                                Text(selectedTempFile?.name ?: "Document.pdf", color = TextMain, fontSize = 13.sp)
                            }
                        } else {
                            AsyncImage(
                                model = selectedTempFile,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Remove button
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(8.dp)
                                .size(28.dp),
                            shape = CircleShape,
                            color = BgBase.copy(alpha = 0.85f),
                            onClick = { selectedTempFile = null }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = RoseRed, modifier = Modifier.size(16.dp))
                            }
                        }
                    } else {
                        Surface(
                            modifier = Modifier.size(50.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = AccentColor
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                            }
                        }
                    }
                }

                // Choose file button
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Choose file (Photo, PDF, Doc)", fontSize = 13.sp)
                }

                // Document Name with Dictation Mic
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceRaised)
                        .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { startSpeech(isForName = true) }) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = AmberGold)
                    }
                    TextField(
                        value = docNameText,
                        onValueChange = { docNameText = it },
                        placeholder = { Text("Document name (dictate or type)...", color = TextFaint, fontSize = 14.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Notes / Labels with Dictation Mic
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(SurfaceRaised)
                        .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { startSpeech(isForName = false) }) {
                        Icon(Icons.Filled.Mic, contentDescription = null, tint = AccentMid)
                    }
                    TextField(
                        value = notesText,
                        onValueChange = { notesText = it },
                        placeholder = { Text("Notes / labels (e.g. taxes, car)...", color = TextFaint, fontSize = 14.sp) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        modifier = Modifier.weight(1f)
                    )
                }

                // Streamlined Save Row with Discreet Pin
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isPinned) AmberGold.copy(alpha = 0.2f) else SurfaceRaised,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isPinned) AmberGold else BorderColor),
                        onClick = { isPinned = !isPinned }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("📌", fontSize = 16.sp)
                        }
                    }

                    Button(
                        onClick = {
                            if (selectedTempFile == null) {
                                Toast.makeText(context, "Please snap or choose a file first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            repository.saveFileToVault(
                                name = docNameText,
                                notes = notesText,
                                isPinned = isPinned,
                                sourceFile = selectedTempFile!!,
                                mimeType = selectedMimeType
                            )
                            documents = repository.getDocuments()
                            selectedTempFile = null
                            docNameText = ""
                            notesText = ""
                            isPinned = false
                            Toast.makeText(context, "Saved to File Cabinet! 🗄️", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
                    ) {
                        Text("Save to Vault", fontWeight = FontWeight.Bold, color = Color.White)
                    }
                }
            }

            // CARD 2: RETRIEVE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(retrieveCardBrush)
                    .border(1.dp, WarningColor.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = AmberGold, modifier = Modifier.size(18.dp))
                    Text("Retrieve", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextMain)
                }

                // File Cabinet Button
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = SurfaceRaised,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    onClick = { showCabinetModal = true }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, tint = AccentMid)
                            Text("File Cabinet", fontWeight = FontWeight.SemiBold, color = TextMain)
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceColor,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight)
                        ) {
                            Text(
                                text = "${documents.size} documents",
                                color = AmberGold,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                // Pinned Quick Access Pills
                val pinnedList = documents.filter { it.isPinned }
                if (pinnedList.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        pinnedList.forEach { doc ->
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = AmberGold.copy(alpha = 0.15f),
                                border = androidx.compose.foundation.BorderStroke(1.dp, AmberGold.copy(alpha = 0.4f)),
                                onClick = { openDocumentFile(context, doc) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("📌", fontSize = 12.sp)
                                    Text(doc.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // FILE CABINET FULL SCREEN MODAL / DIALOG
    if (showCabinetModal) {
        val filteredDocs = documents.filter {
            val q = cabinetSearchQuery.trim().lowercase()
            q.isEmpty() || it.name.lowercase().contains(q) || it.notes.lowercase().contains(q)
        }

        AlertDialog(
            onDismissRequest = { showCabinetModal = false },
            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f),
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SurfaceColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight),
                modifier = Modifier.fillMaxSize().padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("File Cabinet", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        IconButton(onClick = { showCabinetModal = false }) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted)
                        }
                    }

                    OutlinedTextField(
                        value = cabinetSearchQuery,
                        onValueChange = { cabinetSearchQuery = it },
                        placeholder = { Text("Filter by name or notes...", color = TextFaint, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceRaised,
                            unfocusedContainerColor = SurfaceRaised,
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        )
                    )

                    if (filteredDocs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No documents found", color = TextFaint, fontSize = 14.sp)
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(filteredDocs, key = { it.id }) { doc ->
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (doc.isPinned) SurfaceRaised else Color(0xFF0F1426),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (doc.isPinned) AmberGold.copy(alpha = 0.5f) else BorderColor)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                IconButton(
                                                    onClick = { documents = repository.togglePin(doc.id) },
                                                    modifier = Modifier.size(24.dp)
                                                ) {
                                                    Text(if (doc.isPinned) "📌" else "📍", fontSize = 14.sp)
                                                }
                                                Text(doc.name, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextMain)
                                            }
                                            Text(doc.date, color = TextFaint, fontSize = 11.sp)
                                        }

                                        if (doc.notes.isNotBlank()) {
                                            Text("💬 ${doc.notes}", color = TextMuted, fontSize = 12.sp)
                                        }

                                        // Actions: View, Share, Delete
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Button(
                                                onClick = { openDocumentFile(context, doc) },
                                                shape = RoundedCornerShape(8.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("👁️ View", fontSize = 12.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { shareDocumentFile(context, doc) },
                                                shape = RoundedCornerShape(8.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                Text("📤 Share", fontSize = 12.sp)
                                            }
                                            IconButton(
                                                onClick = {
                                                    documents = repository.deleteDocument(doc.id)
                                                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Filled.Delete, contentDescription = null, tint = RoseRed, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun openDocumentFile(context: android.content.Context, doc: VaultDocument) {
    try {
        val file = File(doc.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, doc.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Open with"))
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

fun shareDocumentFile(context: android.content.Context, doc: VaultDocument) {
    try {
        val file = File(doc.filePath)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = doc.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            putExtra(Intent.EXTRA_TEXT, "${doc.name}\n${doc.notes}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share via"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
    }
}

