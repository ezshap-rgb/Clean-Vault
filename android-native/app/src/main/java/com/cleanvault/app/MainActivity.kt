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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
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
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 1.0f
                )
            ) {
                CleanVaultTheme {
                    MainScreen()
                }
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
        if (success && cameraTempFile != null && cameraTempFile!!.exists() && cameraTempFile!!.length() > 0) {
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
                verticalArrangement = Arrangement.spacedBy(9.dp)
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

                // Document Name Input (Concise One Line with Zero Margin Cutoff)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(SurfaceRaised)
                        .border(1.dp, BorderColor, RoundedCornerShape(11.dp))
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { startSpeech(isForName = true) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = "Dictate Name", tint = AmberGold, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (docNameText.isEmpty()) {
                            Text("Document name...", color = TextFaint, fontSize = 13.sp)
                        }
                        BasicTextField(
                            value = docNameText,
                            onValueChange = { docNameText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Normal),
                            cursorBrush = SolidColor(AmberGold),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (docNameText.isNotEmpty()) {
                        IconButton(onClick = { docNameText = "" }, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextFaint, modifier = Modifier.size(14.dp))
                        }
                    }
                }

                // Labels Input (Concise One Line with Zero Margin Cutoff)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(11.dp))
                        .background(SurfaceRaised)
                        .border(1.dp, BorderColor, RoundedCornerShape(11.dp))
                        .padding(horizontal = 8.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { startSpeech(isForName = false) }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Mic, contentDescription = "Dictate Labels", tint = AccentMid, modifier = Modifier.size(18.dp))
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (labelsText.isEmpty()) {
                            Text("Labels (e.g. taxes, car, receipts)...", color = TextFaint, fontSize = 13.sp)
                        }
                        BasicTextField(
                            value = labelsText,
                            onValueChange = { labelsText = it },
                            singleLine = true,
                            textStyle = TextStyle(color = TextMain, fontSize = 13.sp, fontWeight = FontWeight.Normal),
                            cursorBrush = SolidColor(AccentMid),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (labelsText.isNotEmpty()) {
                        IconButton(onClick = { labelsText = "" }, modifier = Modifier.size(24.dp)) {
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
                                onClick = { openDocumentFile(context, doc, repository) }
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
            onDismissRequest = { showSettingsModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = dialogDensity.density,
                    fontScale = 1.0f
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(22.dp),
                    color = SurfaceColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
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

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(11.dp))
                            .background(SurfaceRaised)
                            .border(1.dp, BorderColor, RoundedCornerShape(11.dp))
                            .padding(horizontal = 10.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (inputUrl.isEmpty()) {
                                Text("https://script.google.com/macros/s/.../exec", color = TextFaint, fontSize = 12.sp)
                            }
                            BasicTextField(
                                value = inputUrl,
                                onValueChange = {
                                    inputUrl = it
                                    testStatus = null
                                },
                                singleLine = true,
                                textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
                                cursorBrush = SolidColor(AccentColor),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

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
}

    // RENAME DOCUMENT DIALOG
    if (docToRename != null) {
        val target = docToRename!!
        var editName by remember(target) { mutableStateOf(target.name) }
        var editLabels by remember(target) { mutableStateOf(target.notes) }

        BasicAlertDialog(
            onDismissRequest = { docToRename = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = dialogDensity.density,
                    fontScale = 1.0f
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text("Rename & Edit Labels", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextMain)

                        // Edit Name
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Document Name", fontSize = 11.5.sp, color = TextMuted)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceRaised)
                                    .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = editName,
                                    onValueChange = { editName = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
                                    cursorBrush = SolidColor(AmberGold),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        // Edit Labels
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Labels", fontSize = 11.5.sp, color = TextMuted)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceRaised)
                                    .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                BasicTextField(
                                    value = editLabels,
                                    onValueChange = { editLabels = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
                                    cursorBrush = SolidColor(AccentMid),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = { docToRename = null },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel", fontSize = 11.5.sp)
                            }

                            Button(
                                onClick = {
                                    val updated = repository.renameDocument(target.id, editName.trim(), editLabels.trim())
                                    documents = updated
                                    val targetDoc = updated.find { it.id == target.id } ?: target
                                    scope.launch {
                                        repository.updateDocRemoteAsync(targetDoc, editName.trim(), editLabels.trim())
                                    }
                                    docToRename = null
                                    Toast.makeText(context, "Document updated", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = AccentColor),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Save", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 11.5.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // FILE CABINET FULL SCREEN MODAL / DIALOG (WIDE, SPACIOUS, NOT THIN)
    if (showCabinetModal) {
        val filteredDocs = documents.filter {
            val q = cabinetSearchQuery.trim().lowercase()
            q.isEmpty() || it.name.lowercase().contains(q) || it.notes.lowercase().contains(q)
        }

        BasicAlertDialog(
            onDismissRequest = { showCabinetModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            val dialogDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = dialogDensity.density,
                    fontScale = 1.0f
                )
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = SurfaceColor,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderHighlight),
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.92f)
                        .padding(horizontal = 12.dp, vertical = 16.dp)
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
                                Text("File Cabinet", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)
                                if (isSyncing) {
                                    CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = AmberGold)
                                }
                            }
                            IconButton(onClick = { showCabinetModal = false }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted)
                            }
                        }

                        // Filter by name or labels (Zero cutoff text field)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(11.dp))
                                .background(SurfaceRaised)
                                .border(1.dp, BorderColor, RoundedCornerShape(11.dp))
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Search, contentDescription = null, tint = TextMuted, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (cabinetSearchQuery.isEmpty()) {
                                    Text("Filter by name or labels...", color = TextFaint, fontSize = 12.sp)
                                }
                                BasicTextField(
                                    value = cabinetSearchQuery,
                                    onValueChange = { cabinetSearchQuery = it },
                                    singleLine = true,
                                    textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
                                    cursorBrush = SolidColor(AccentColor),
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            if (cabinetSearchQuery.isNotEmpty()) {
                                IconButton(onClick = { cabinetSearchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = TextFaint, modifier = Modifier.size(14.dp))
                                }
                            }
                        }

                        if (filteredDocs.isEmpty()) {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text("No documents found", color = TextFaint, fontSize = 12.sp)
                            }
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(filteredDocs, key = { it.id }) { doc ->
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (doc.isPinned) SurfaceRaised else Color(0xFF0F1426),
                                        border = androidx.compose.foundation.BorderStroke(1.dp, if (doc.isPinned) AmberGold.copy(alpha = 0.5f) else BorderColor)
                                    ) {
                                        Column(modifier = Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            // Header Row: Pin, Document Name (Full wrap, not cut off), and Timestamp
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                verticalAlignment = Alignment.Top,
                                                horizontalArrangement = Arrangement.SpaceBetween
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.Top,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                    modifier = Modifier.weight(1f)
                                                ) {
                                                    IconButton(
                                                        onClick = {
                                                            val updated = repository.togglePin(doc.id)
                                                            documents = updated
                                                            val targetDoc = updated.find { it.id == doc.id } ?: doc
                                                            scope.launch { repository.togglePinRemoteAsync(targetDoc) }
                                                        },
                                                        modifier = Modifier.size(22.dp).padding(top = 1.dp)
                                                    ) {
                                                        Text(if (doc.isPinned) "📌" else "📍", fontSize = 12.sp)
                                                    }
                                                    // FULL WRAP: Long names wrap onto multiple lines and are NEVER cut off!
                                                    Text(
                                                        text = doc.name,
                                                        fontWeight = FontWeight.Bold,
                                                        fontSize = 13.sp,
                                                        color = TextMain,
                                                        lineHeight = 16.sp
                                                    )
                                                }
                                                if (doc.isSynced) {
                                                    Text("☁️", fontSize = 11.sp, modifier = Modifier.padding(start = 8.dp, top = 2.dp))
                                                } else {
                                                    IconButton(
                                                        onClick = {
                                                            scope.launch {
                                                                Toast.makeText(context, "Uploading to Google Drive...", Toast.LENGTH_SHORT).show()
                                                                val uploaded = repository.uploadDocAsync(doc)
                                                                documents = repository.getDocuments()
                                                                if (uploaded != null) {
                                                                    Toast.makeText(context, "Uploaded to Google Drive! ☁️", Toast.LENGTH_SHORT).show()
                                                                } else {
                                                                    Toast.makeText(context, "Upload failed. Check Google Drive settings ⚙️", Toast.LENGTH_SHORT).show()
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.size(22.dp).padding(start = 4.dp)
                                                    ) {
                                                        Text("⬆️", fontSize = 11.sp)
                                                    }
                                                }
                                            }

                                            if (doc.notes.isNotBlank()) {
                                                Text("🏷️ ${doc.notes}", color = TextMuted, fontSize = 11.sp)
                                            }

                                            // Actions: Classic Icons for View, Download, Share, Rename, Delete
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(5.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // View Button: Tap to open directly, Long-press to choose a different viewer app
                                                @OptIn(ExperimentalFoundationApi::class)
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = AccentColor,
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .height(32.dp)
                                                        .clip(RoundedCornerShape(8.dp))
                                                        .combinedClickable(
                                                            onClick = { openDocumentFile(context, doc, repository, forceChooser = false) },
                                                            onLongClick = {
                                                                Toast.makeText(context, "Choose viewer app...", Toast.LENGTH_SHORT).show()
                                                                openDocumentFile(context, doc, repository, forceChooser = true)
                                                            }
                                                        )
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp, vertical = 2.dp),
                                                        horizontalArrangement = Arrangement.Center,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Icon(Icons.Filled.Visibility, contentDescription = "View", tint = Color.White, modifier = Modifier.size(13.dp))
                                                        Spacer(modifier = Modifier.width(3.dp))
                                                        Text("View", color = Color.White, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                                                    }
                                                }

                                                // Classic Download Arrow Button
                                                OutlinedButton(
                                                    onClick = { downloadDocumentToStorage(context, doc) },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                    modifier = Modifier.weight(1.15f).height(32.dp)
                                                ) {
                                                    Icon(Icons.Filled.FileDownload, contentDescription = "Download", tint = TextMain, modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text("Download", fontSize = 10.5.sp)
                                                }

                                                // Classic Share Icon Button
                                                OutlinedButton(
                                                    onClick = { shareDocumentFile(context, doc) },
                                                    shape = RoundedCornerShape(8.dp),
                                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                    modifier = Modifier.weight(1f).height(32.dp)
                                                ) {
                                                    Icon(Icons.Filled.Share, contentDescription = "Share", tint = TextMain, modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(3.dp))
                                                    Text("Share", fontSize = 10.5.sp)
                                                }

                                                // Classic Rename Pencil Icon
                                                IconButton(
                                                    onClick = { docToRename = doc },
                                                    modifier = Modifier.size(30.dp)
                                                ) {
                                                    Icon(Icons.Filled.Edit, contentDescription = "Rename", tint = AmberGold, modifier = Modifier.size(16.dp))
                                                }

                                                // Classic Delete Trash Icon
                                                IconButton(
                                                    onClick = {
                                                        val docIdToDelete = doc.id
                                                        val fileIdToDelete = doc.driveFileId
                                                        documents = repository.deleteDocument(docIdToDelete)
                                                        scope.launch { repository.deleteDocRemoteAsync(docIdToDelete, fileIdToDelete) }
                                                        Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.size(30.dp)
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
}

fun openDocumentFile(context: Context, doc: VaultDocument, repository: VaultRepository, forceChooser: Boolean = false) {
    try {
        var file = File(doc.filePath)
        // If file missing locally, attempt on-demand retrieval or drive URL fallback
        if (!file.exists() || file.length() == 0L) {
            val scriptUrl = repository.getScriptUrl()
            if (scriptUrl.isNotBlank() && !doc.driveFileId.isNullOrBlank()) {
                Toast.makeText(context, "Fetching file from Google Drive...", Toast.LENGTH_SHORT).show()
                val downloadedPath = repository.downloadAndSaveFile(scriptUrl, doc.driveFileId, doc.fileName)
                if (downloadedPath != null) {
                    file = File(downloadedPath)
                }
            }
        }

        if (!file.exists() || file.length() == 0L) {
            // Direct Cloud Fallback: Open in Google Drive / Chrome
            if (!doc.driveFileUrl.isNullOrBlank()) {
                val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(doc.driveFileUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(browserIntent)
                return
            }
            Toast.makeText(context, "File not found locally or in Drive", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, doc.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (forceChooser) {
            val chooserIntent = Intent.createChooser(intent, "Open with...").apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooserIntent)
        } else {
            // Direct launch so Android uses default or shows choice if not set
            context.startActivity(intent)
        }
    } catch (e: Exception) {
        Toast.makeText(context, "No app found to open this file", Toast.LENGTH_SHORT).show()
    }
}

fun shareDocumentFile(context: Context, doc: VaultDocument) {
    try {
        val file = File(doc.filePath)
        if (!file.exists()) {
            Toast.makeText(context, "File not found locally to share", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = doc.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, doc.name)
            putExtra(Intent.EXTRA_TEXT, "${doc.name}\n${doc.notes}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // For sharing, Android system share sheet is standard
        context.startActivity(Intent.createChooser(intent, "Share Document"))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not share file", Toast.LENGTH_SHORT).show()
    }
}

fun downloadDocumentToStorage(context: Context, doc: VaultDocument) {
    try {
        val srcFile = File(doc.filePath)
        if (!srcFile.exists() || srcFile.length() == 0L) {
            Toast.makeText(context, "File not found on device to download", Toast.LENGTH_SHORT).show()
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
                Toast.makeText(context, "Saved to Downloads: $displayName 📥", Toast.LENGTH_LONG).show()
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
