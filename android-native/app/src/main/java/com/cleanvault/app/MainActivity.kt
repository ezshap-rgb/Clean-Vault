package com.cleanvault.app

import android.Manifest
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.cleanvault.app.data.VaultDocument
import com.cleanvault.app.data.VaultRepository
import com.cleanvault.app.theme.*
import kotlinx.coroutines.launch
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
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var documents by remember { mutableStateOf(repository.getDocuments()) }
    var isSyncing by remember { mutableStateOf(false) }

    var selectedTempFile by remember { mutableStateOf<File?>(null) }
    var selectedMimeType by remember { mutableStateOf("image/jpeg") }

    var docNameText by remember { mutableStateOf("") }
    var labelsText by remember { mutableStateOf("") }
    var isPinned by remember { mutableStateOf(false) }

    var showCabinetModal by remember { mutableStateOf(false) }
    var showSettingsModal by remember { mutableStateOf(false) }
    var cabinetSearchQuery by remember { mutableStateOf("") }

    // Rename Dialog state
    var docToRename by remember { mutableStateOf<VaultDocument?>(null) }

    // Cloud Sync Runner
    fun triggerSync(silent: Boolean = false) {
        if (isSyncing) return
        val url = repository.getScriptUrl()
        if (url.isBlank()) {
            if (!silent) {
                Toast.makeText(context, "Configure Google Drive in Settings ⚙️ to sync", Toast.LENGTH_SHORT).show()
                showSettingsModal = true
            }
            return
        }

        isSyncing = true
        scope.launch {
            val result = repository.syncWithCloud()
            isSyncing = false
            result.onSuccess { summary ->
                documents = repository.getDocuments()
                val msg = if (summary.downloaded > 0 || summary.uploaded > 0) {
                    "Synced: +${summary.downloaded} new, ^${summary.uploaded} uploaded (${summary.total} total)"
                } else {
                    "File Cabinet up to date (${summary.total} docs)"
                }
                if (!silent) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                if (!silent) Toast.makeText(context, "Sync error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Auto sync on Open (ON_START) and on Close/Background (ON_STOP)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    triggerSync(silent = true)
                }
                Lifecycle.Event.ON_STOP -> {
                    scope.launch {
                        repository.syncWithCloud()
                    }
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Speech recognition launchers
    val speechLauncherName = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                docNameText = if (docNameText.isBlank()) spoken else "$docNameText $spoken"
            }
        }
    }

    val speechLauncherLabels = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                labelsText = if (labelsText.isBlank()) spoken else "$labelsText $spoken"
            }
        }
    }

    fun startSpeech(isForName: Boolean) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (isForName) "Speak Document Name" else "Speak Labels")
        }
        try {
            if (isForName) speechLauncherName.launch(intent) else speechLauncherLabels.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    // Safe Camera Launcher with runtime permission check
    var cameraTempUri by remember { mutableStateOf<Uri?>(null) }
    var cameraTempFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        if (success && cameraTempFile != null && cameraTempFile!!.length() > 0) {
            selectedTempFile = cameraTempFile
            selectedMimeType = "image/jpeg"
        }
    }

    fun launchCamera() {
        try {
            val temp = File(context.cacheDir, "camera_snap_${System.currentTimeMillis()}.jpg")
            if (temp.exists()) temp.delete()
            temp.createNewFile()
            cameraTempFile = temp
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", temp)
            cameraTempUri = uri
            cameraLauncher.launch(uri)
        } catch (e: Exception) {
            Toast.makeText(context, "Camera error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            launchCamera()
        } else {
            Toast.makeText(context, "Camera permission needed to snap photos", Toast.LENGTH_SHORT).show()
        }
    }

    fun handleCameraClick() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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

    // Infinite rotation animation for syncing icon
    val infiniteTransition = rememberInfiniteTransition(label = "sync")
    val syncRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )

    val fileCardBrush = Brush.linearGradient(listOf(Color(0xFF101530), Color(0xFF0C1024)))
    val retrieveCardBrush = Brush.linearGradient(listOf(Color(0xFF1C1028), Color(0xFF120B1C)))

    Scaffold(
        containerColor = BgBase,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(SurfaceColor)
                    .border(1.dp, BorderColor, RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        modifier = Modifier.size(34.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = AccentColor
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Filled.Folder, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }
                    Column {
                        Text(text = "Clean Vault", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        val hasCloud = repository.getScriptUrl().isNotBlank()
                        Text(
                            text = if (hasCloud) "Google Drive Shared" else "Local only",
                            fontSize = 11.sp,
                            color = if (hasCloud) EmeraldGreen else TextFaint
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // Sync Button with animated rotation
                    IconButton(
                        onClick = { triggerSync(silent = false) },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Filled.Sync,
                            contentDescription = "Sync",
                            tint = if (isSyncing) AmberGold else TextMuted,
                            modifier = Modifier
                                .size(18.dp)
                                .then(if (isSyncing) Modifier.rotate(syncRotation) else Modifier)
                        )
                    }

                    // Settings Button
                    IconButton(
                        onClick = { showSettingsModal = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings", tint = TextMuted, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Spacer(modifier = Modifier.height(2.dp))

            // CARD 1: STREAMLINED COMPACT FILE CARD
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(fileCardBrush)
                    .border(1.dp, AccentColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.Folder, contentDescription = null, tint = AmberGold, modifier = Modifier.size(16.dp))
                        Text("File", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                    }
                    if (selectedTempFile != null) {
                        Text(
                            text = "1 item selected",
                            color = EmeraldGreen,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                // Compact Camera / Preview Box (Streamlined Height ~78dp)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(78.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(SurfaceColor.copy(alpha = 0.6f))
                        .border(1.dp, BorderHighlight, RoundedCornerShape(12.dp))
                        .clickable { handleCameraClick() },
                    contentAlignment = Alignment.Center
                ) {
                    if (selectedTempFile != null) {
                        if (selectedMimeType.contains("pdf")) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(Icons.Filled.Description, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                                Text(selectedTempFile?.name ?: "Document.pdf", color = TextMain, fontSize = 12.sp, maxLines = 1)
                            }
                        } else {
                            AsyncImage(
                                model = selectedTempFile,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }

                        // Remove thumbnail button
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(24.dp),
                            shape = CircleShape,
                            color = BgBase.copy(alpha = 0.85f),
                            onClick = { selectedTempFile = null }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = RoseRed, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier.size(36.dp),
                                shape = RoundedCornerShape(10.dp),
                                color = AccentColor
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.CameraAlt, contentDescription = "Camera", tint = Color.White, modifier = Modifier.size(18.dp))
                                }
                            }
                            Text("Tap to snap photo", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                }

                // Sleek File Picker Row
                OutlinedButton(
                    onClick = { filePickerLauncher.launch("*/*") },
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextMuted)
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Or choose file (Photo, PDF, Doc)", fontSize = 12.sp)
                }

                // Document Name (Concise One Line)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SurfaceRaised)
                        .border(1.dp, BorderColor, RoundedCornerShape(11.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { startSpeech(isForName = true) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = "Dictate Name", tint = AmberGold, modifier = Modifier.size(18.dp))
                    }
                    TextField(
                        value = docNameText,
                        onValueChange = { docNameText = it },
                        placeholder = { Text("Document name...", color = TextFaint, fontSize = 13.sp) },
                        singleLine = true,
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
                    if (docNameText.isNotEmpty()) {
                        IconButton(onClick = { docNameText = "" }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextFaint, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Labels Field (Concise One Line)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(SurfaceRaised)
                        .border(1.dp, BorderColor, RoundedCornerShape(11.dp))
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { startSpeech(isForName = false) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = "Dictate Labels", tint = AccentMid, modifier = Modifier.size(18.dp))
                    }
                    TextField(
                        value = labelsText,
                        onValueChange = { labelsText = it },
                        placeholder = { Text("Labels (e.g. taxes, car, receipts)...", color = TextFaint, fontSize = 13.sp) },
                        singleLine = true,
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
                    if (labelsText.isNotEmpty()) {
                        IconButton(onClick = { labelsText = "" }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextFaint, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Streamlined Save Row with Discreet Pin
                Row(
                    modifier = Modifier.fillMaxWidth().height(42.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.size(42.dp),
                        shape = RoundedCornerShape(11.dp),
                        color = if (isPinned) AmberGold.copy(alpha = 0.2f) else SurfaceRaised,
                        border = androidx.compose.foundation.BorderStroke(1.dp, if (isPinned) AmberGold else BorderColor),
                        onClick = { isPinned = !isPinned }
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text("📌", fontSize = 15.sp)
                        }
                    }

                    Button(
                        onClick = {
                            if (selectedTempFile == null) {
                                Toast.makeText(context, "Please snap or choose a file first", Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            val doc = repository.saveFileToVault(
                                name = docNameText,
                                notes = labelsText,
                                isPinned = isPinned,
                                sourceFile = selectedTempFile!!,
                                mimeType = selectedMimeType
                            )
                            documents = repository.getDocuments()
                            selectedTempFile = null
                            docNameText = ""
                            labelsText = ""
                            isPinned = false
                            Toast.makeText(context, "Saved to File Cabinet! 🗄️", Toast.LENGTH_SHORT).show()

                            // Auto-upload in background to Google Drive
                            scope.launch {
                                repository.uploadDocAsync(doc)
                                documents = repository.getDocuments()
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                        shape = RoundedCornerShape(11.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentColor)
                    ) {
                        Text("Save to Vault", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 14.sp)
                    }
                }
            }

            // CARD 2: RETRIEVE
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(retrieveCardBrush)
                    .border(1.dp, WarningColor.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    .padding(13.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = AmberGold, modifier = Modifier.size(16.dp))
                    Text("Retrieve", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextMain)
                }

                // File Cabinet Button
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(11.dp),
                    color = SurfaceRaised,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    onClick = { showCabinetModal = true }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.FolderZip, contentDescription = null, tint = AccentMid, modifier = Modifier.size(20.dp))
                            Text("File Cabinet", fontWeight = FontWeight.SemiBold, color = TextMain, fontSize = 14.sp)
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
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text("📌", fontSize = 11.sp)
                                    Text(doc.name, color = TextMain, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }

    // SETTINGS MODAL (Google Drive Shared Backend Setup)
    if (showSettingsModal) {
        var inputUrl by remember { mutableStateOf(repository.getScriptUrl()) }
        var testStatus by remember { mutableStateOf<String?>(null) }
        var isTesting by remember { mutableStateOf(false) }

        BasicAlertDialog(
            onDismissRequest = { showSettingsModal = false }
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = SurfaceColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight),
                modifier = Modifier.fillMaxWidth(0.92f).padding(16.dp)
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
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Filled.CloudSync, contentDescription = null, tint = AccentMid)
                            Text("Shared Cloud Cabinet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)
                        }
                        IconButton(onClick = { showSettingsModal = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted)
                        }
                    }

                    Text(
                        text = "Both you and your wife can use this same Google Apps Script Web App URL to automatically share the exact same Google Drive files & cabinet.",
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )

                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = {
                            inputUrl = it
                            testStatus = null
                        },
                        placeholder = { Text("https://script.google.com/macros/s/.../exec", color = TextFaint, fontSize = 12.sp) },
                        label = { Text("Apps Script Web App URL", color = TextMuted, fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(11.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceRaised,
                            unfocusedContainerColor = SurfaceRaised,
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        singleLine = true
                    )

                    if (testStatus != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = if (testStatus!!.contains("Connected")) EmeraldGreen.copy(alpha = 0.15f) else RoseRed.copy(alpha = 0.15f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (testStatus!!.contains("Connected")) EmeraldGreen else RoseRed)
                        ) {
                            Text(
                                text = testStatus!!,
                                color = if (testStatus!!.contains("Connected")) EmeraldGreen else RoseRed,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                if (inputUrl.isBlank()) {
                                    testStatus = "Please enter URL first"
                                    return@OutlinedButton
                                }
                                isTesting = true
                                scope.launch {
                                    val (success, message) = repository.testConnection(inputUrl.trim())
                                    isTesting = false
                                    testStatus = message
                                }
                            },
                            enabled = !isTesting,
                            shape = RoundedCornerShape(11.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(if (isTesting) "Testing..." else "Test Link", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                repository.setScriptUrl(inputUrl.trim())
                                showSettingsModal = false
                                Toast.makeText(context, "Saved! Syncing cabinet...", Toast.LENGTH_SHORT).show()
                                triggerSync(silent = false)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                            shape = RoundedCornerShape(11.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save & Sync", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // RENAME DOCUMENT DIALOG
    if (docToRename != null) {
        val target = docToRename!!
        var editName by remember(target) { mutableStateOf(target.name) }
        var editLabels by remember(target) { mutableStateOf(target.notes) }

        BasicAlertDialog(
            onDismissRequest = { docToRename = null }
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = SurfaceColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight),
                modifier = Modifier.fillMaxWidth(0.92f).padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Rename & Edit Labels", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)

                    // Edit Name
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text("Document Name", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceRaised,
                            unfocusedContainerColor = SurfaceRaised,
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        )
                    )

                    // Edit Labels
                    OutlinedTextField(
                        value = editLabels,
                        onValueChange = { editLabels = it },
                        label = { Text("Labels", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceRaised,
                            unfocusedContainerColor = SurfaceRaised,
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        )
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { docToRename = null },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Cancel", fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                documents = repository.renameDocument(target.id, editName.trim(), editLabels.trim())
                                scope.launch {
                                    repository.updateDocRemoteAsync(target.id, editName.trim(), editLabels.trim())
                                }
                                docToRename = null
                                Toast.makeText(context, "Document updated", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Save", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // FILE CABINET FULL SCREEN MODAL / DIALOG
    if (showCabinetModal) {
        val filteredDocs = documents.filter {
            val q = cabinetSearchQuery.trim().lowercase()
            q.isEmpty() || it.name.lowercase().contains(q) || it.notes.lowercase().contains(q)
        }

        BasicAlertDialog(
            onDismissRequest = { showCabinetModal = false }
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = SurfaceColor,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight),
                modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.88f).padding(10.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("File Cabinet", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextMain)
                            if (isSyncing) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = AmberGold)
                            }
                        }
                        IconButton(onClick = { showCabinetModal = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted)
                        }
                    }

                    // Filter by name or labels (as requested)
                    OutlinedTextField(
                        value = cabinetSearchQuery,
                        onValueChange = { cabinetSearchQuery = it },
                        placeholder = { Text("Filter by name or labels...", color = TextFaint, fontSize = 13.sp) },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        shape = RoundedCornerShape(11.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = SurfaceRaised,
                            unfocusedContainerColor = SurfaceRaised,
                            focusedBorderColor = AccentColor,
                            unfocusedBorderColor = BorderColor,
                            focusedTextColor = TextMain,
                            unfocusedTextColor = TextMain
                        ),
                        singleLine = true
                    )

                    if (filteredDocs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No documents found", color = TextFaint, fontSize = 13.sp)
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
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.weight(1f)
                                            ) {
                                                IconButton(
                                                    onClick = {
                                                        documents = repository.togglePin(doc.id)
                                                        scope.launch { repository.togglePinRemoteAsync(doc.id) }
                                                    },
                                                    modifier = Modifier.size(22.dp)
                                                ) {
                                                    Text(if (doc.isPinned) "📌" else "📍", fontSize = 13.sp)
                                                }
                                                Text(doc.name, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextMain, maxLines = 1)
                                            }
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                if (doc.isSynced) {
                                                    Text("☁️", fontSize = 11.sp)
                                                }
                                                Text(doc.date, color = TextFaint, fontSize = 11.sp)
                                            }
                                        }

                                        if (doc.notes.isNotBlank()) {
                                            Text("🏷️ ${doc.notes}", color = TextMuted, fontSize = 11.sp)
                                        }

                                        // Actions: View, Download, Share, Rename, Delete
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = { openDocumentFile(context, doc) },
                                                shape = RoundedCornerShape(7.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.weight(1f).height(32.dp)
                                            ) {
                                                Text("👁️ View", fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { downloadDocumentToStorage(context, doc) },
                                                shape = RoundedCornerShape(7.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.weight(1.1f).height(32.dp)
                                            ) {
                                                Text("📥 Download", fontSize = 11.sp)
                                            }
                                            OutlinedButton(
                                                onClick = { shareDocumentFile(context, doc) },
                                                shape = RoundedCornerShape(7.dp),
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                modifier = Modifier.weight(1f).height(32.dp)
                                            ) {
                                                Text("📤 Share", fontSize = 11.sp)
                                            }
                                            IconButton(
                                                onClick = { docToRename = doc },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = AmberGold, modifier = Modifier.size(16.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    documents = repository.deleteDocument(doc.id)
                                                    scope.launch { repository.deleteDocRemoteAsync(doc.id) }
                                                    Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                                }
                                            ) {
                                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = RoseRed, modifier = Modifier.size(16.dp))
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

fun openDocumentFile(context: Context, doc: VaultDocument) {
    try {
        val file = File(doc.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found locally", Toast.LENGTH_SHORT).show()
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

fun shareDocumentFile(context: Context, doc: VaultDocument) {
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

fun downloadDocumentToStorage(context: Context, doc: VaultDocument) {
    try {
        val srcFile = File(doc.filePath)
        if (!srcFile.exists()) {
            Toast.makeText(context, "File not found on device", Toast.LENGTH_SHORT).show()
            return
        }

        val ext = srcFile.extension.ifEmpty { if (doc.mimeType.contains("pdf")) "pdf" else "jpg" }
        val safeName = (doc.name.ifBlank { "VaultDocument" }).replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val displayName = "${safeName}_${System.currentTimeMillis()}.$ext"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, doc.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                resolver.openOutputStream(uri)?.use { out ->
                    srcFile.inputStream().use { it.copyTo(out) }
                }
                Toast.makeText(context, "Downloaded to Downloads: $displayName 📥", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Failed to create download entry", Toast.LENGTH_SHORT).show()
            }
        } else {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            downloadsDir.mkdirs()
            val destFile = File(downloadsDir, displayName)
            srcFile.copyTo(destFile, overwrite = true)
            Toast.makeText(context, "Saved to Downloads: $displayName 📥", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
