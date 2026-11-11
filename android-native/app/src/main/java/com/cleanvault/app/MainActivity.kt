package com.cleanvault.app

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
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
import com.cleanvault.app.data.CloudSyncMode
import com.cleanvault.app.theme.*
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.RESULT_FORMAT_PDF
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions.SCANNER_MODE_FULL
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class AppScreen {
    HOME,
    FILE_IN,
    ESSENTIALS,
    FILE_CABINET
}

class MainActivity : ComponentActivity() {
    private val incomingIntentState = mutableStateOf<Intent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incomingIntentState.value = intent
        setContent {
            val currentDensity = LocalDensity.current
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = currentDensity.density,
                    fontScale = 1.0f
                )
            ) {
                CleanVaultTheme {
                    MainScreen(
                        incomingIntent = incomingIntentState.value,
                        onIntentHandled = { incomingIntentState.value = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingIntentState.value = intent
    }
}

fun getFileTypeIcon(doc: VaultDocument): String {
    val path = doc.filePath.lowercase()
    val name = doc.fileName.lowercase()
    val mime = doc.mimeType.lowercase()
    return when {
        path.endsWith(".txt") || name.endsWith(".txt") || mime.startsWith("text/") -> "📝"
        path.endsWith(".pdf") || name.endsWith(".pdf") || mime.contains("pdf") -> "📄"
        else -> "🖼️"
    }
}

@Composable
fun ScanReticleIcon(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cornerLen = w * 0.25f
        val stroke = 3.dp.toPx()
        val white = Color.White
        val yellow = Color(0xFFFFD54F)

        // Corners
        drawLine(white, Offset(0f, cornerLen), Offset(0f, 0f), strokeWidth = stroke)
        drawLine(white, Offset(0f, 0f), Offset(cornerLen, 0f), strokeWidth = stroke)

        drawLine(white, Offset(w - cornerLen, 0f), Offset(w, 0f), strokeWidth = stroke)
        drawLine(white, Offset(w, 0f), Offset(w, cornerLen), strokeWidth = stroke)

        drawLine(white, Offset(0f, h - cornerLen), Offset(0f, h), strokeWidth = stroke)
        drawLine(white, Offset(0f, h), Offset(cornerLen, h), strokeWidth = stroke)

        drawLine(white, Offset(w - cornerLen, h), Offset(w, h), strokeWidth = stroke)
        drawLine(white, Offset(w, h), Offset(w, h - cornerLen), strokeWidth = stroke)

        // Inner dashed rect
        val inset = w * 0.22f
        drawRoundRect(
            color = white.copy(alpha = 0.85f),
            topLeft = Offset(inset, inset),
            size = Size(w - inset * 2, h - inset * 2),
            cornerRadius = CornerRadius(6.dp.toPx()),
            style = Stroke(
                width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f))
            )
        )

        // Laser scan line across center
        drawLine(
            color = yellow,
            start = Offset(w * 0.1f, h * 0.5f),
            end = Offset(w * 0.9f, h * 0.5f),
            strokeWidth = 3.dp.toPx()
        )
    }
}

@Composable
fun NeonHandle(width: Dp, height: Dp) {
    Box(
        modifier = Modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(20.dp))
            .background(NeonAmberHandle),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .padding(top = 2.dp, start = 8.dp, end = 8.dp)
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.7f))
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StreamlinedDocCard(
    doc: VaultDocument,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onEdit: () -> Unit,
    onTogglePin: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Main Top Row: Icon, Title, Tags, Date, Cloud icon
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = getFileTypeIcon(doc),
                        fontSize = 24.sp
                    )
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = doc.name,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextDark,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = if (doc.notes.isNotBlank()) "🏷️ ${doc.notes}" else "🏷️ No labels",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = TextSubtle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (doc.date.isNotBlank()) {
                                Text(
                                    text = "• ${doc.date}",
                                    fontSize = 10.sp,
                                    color = TextFaint
                                )
                            }
                        }
                    }
                }

                if (doc.isSynced || doc.driveFileId != null) {
                    Text("☁️", fontSize = 16.sp, modifier = Modifier.padding(start = 6.dp))
                }
            }

            // Clean subtle divider
            HorizontalDivider(
                color = BorderColor.copy(alpha = 0.5f),
                thickness = 0.8.dp,
                modifier = Modifier.padding(vertical = 2.dp)
            )

            // Action Row: Download, Edit, Pin, Share, Delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 1. Download
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Download,
                        contentDescription = "Download",
                        tint = PurpleDark,
                        modifier = Modifier.size(19.dp)
                    )
                }

                // 2. Edit
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit",
                        tint = PurpleDark,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 3. Pin (RED when pinned!)
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier.size(34.dp)
                ) {
                    if (doc.isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            tint = RedPin,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Unpinned",
                            tint = TextSubtle.copy(alpha = 0.35f),
                            modifier = Modifier.size(20.dp).rotate(45f)
                        )
                    }
                }

                // 4. Share
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Share,
                        contentDescription = "Share",
                        tint = PurpleDark,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // 5. Delete
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "Delete",
                        tint = RedPin.copy(alpha = 0.85f),
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun MainScreen(
    incomingIntent: Intent? = null,
    onIntentHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val repository = remember { VaultRepository(context) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current

    var documents by remember { mutableStateOf(repository.getDocuments()) }
    var isSyncing by remember { mutableStateOf(false) }

    var currentScreen by remember { mutableStateOf(AppScreen.HOME) }

    var selectedTempFile by remember { mutableStateOf<File?>(null) }
    var selectedMimeType by remember { mutableStateOf("image/jpeg") }
    var scanAsPdf by remember { mutableStateOf(true) }

    var docNameText by remember { mutableStateOf("") }
    var labelsText by remember { mutableStateOf("") }
    var isPinned by remember { mutableStateOf(false) }

    var showSettingsModal by remember { mutableStateOf(false) }
    var appsScriptUrlInput by remember { mutableStateOf(repository.getScriptUrl()) }
    var isTestingScript by remember { mutableStateOf(false) }
    var cabinetSearchQuery by remember { mutableStateOf("") }
    var cabinetFilterPinnedOnly by remember { mutableStateOf(false) }

    // Rename Dialog state
    var docToRename by remember { mutableStateOf<VaultDocument?>(null) }
    var docToDelete by remember { mutableStateOf<VaultDocument?>(null) }

    // Quick Note Dialog & Reader state
    var showNoteDialog by remember { mutableStateOf(false) }
    var noteTitleInput by remember { mutableStateOf("") }
    var noteBodyInput by remember { mutableStateOf("") }
    var noteDocToView by remember { mutableStateOf<VaultDocument?>(null) }
    var noteEditText by remember { mutableStateOf("") }
    var isEditingNote by remember { mutableStateOf(false) }

    // System Back Gesture handling: if inside sub-screens, back returns to HOME
    BackHandler(enabled = currentScreen != AppScreen.HOME) {
        currentScreen = AppScreen.HOME
    }

    // Handle Incoming Shares (from other apps: PDFs, images, docs)
    LaunchedEffect(incomingIntent) {
        val intent = incomingIntent ?: return@LaunchedEffect
        val action = intent.action
        if (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) {
            handleIncomingShare(
                context = context,
                intent = intent,
                onFileSelected = { file, mime, name ->
                    selectedTempFile = file
                    selectedMimeType = mime
                    if (docNameText.isBlank() && name.isNotBlank()) {
                        docNameText = name
                    }
                    currentScreen = AppScreen.FILE_IN
                    Toast.makeText(context, "Received document: ${file.name}", Toast.LENGTH_SHORT).show()
                }
            )
            onIntentHandled()
        }
    }

    // Cloud Sync Runner
    fun triggerSync(silent: Boolean = false) {
        if (isSyncing) return
        val hasCloud = repository.hasCloudConnection()
        if (!hasCloud) {
            if (!silent) {
                Toast.makeText(context, "Connect Google Drive in Settings ⚙️ to sync", Toast.LENGTH_SHORT).show()
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
                    "Clean Filing up to date (${summary.total} docs)"
                }
                if (!silent) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }.onFailure { err ->
                if (!silent) Toast.makeText(context, "Sync error: ${err.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Google Sign-In state & Launcher
    var googleAccountEmail by remember { mutableStateOf(repository.getGoogleEmail()) }

    val googleSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            if (account != null && account.email != null) {
                repository.setGoogleAccount(account.email)
                googleAccountEmail = account.email!!
                Toast.makeText(context, "Connected as ${account.email}! Syncing...", Toast.LENGTH_SHORT).show()
                triggerSync(silent = false)
            }
        } catch (e: ApiException) {
            Toast.makeText(context, "Sign in failed (${e.statusCode}): ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun startGoogleSignIn() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope("https://www.googleapis.com/auth/drive.file"))
            .build()
        val client = GoogleSignIn.getClient(context, gso)
        googleSignInLauncher.launch(client.signInIntent)
    }

    fun disconnectGoogleAccount() {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).build()
        val client = GoogleSignIn.getClient(context, gso)
        client.signOut().addOnCompleteListener {
            repository.setGoogleAccount(null, null)
            googleAccountEmail = ""
            Toast.makeText(context, "Disconnected Google Drive", Toast.LENGTH_SHORT).show()
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

    val speechLauncherNote = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                noteBodyInput = if (noteBodyInput.isBlank()) spoken else "$noteBodyInput $spoken"
            }
        }
    }

    fun startSpeechNote() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak Note Content")
        }
        try {
            speechLauncherNote.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    val speechLauncherNoteEdit = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            val spoken = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: ""
            if (spoken.isNotBlank()) {
                noteEditText = if (noteEditText.isBlank()) spoken else "$noteEditText $spoken"
            }
        }
    }

    fun startSpeechNoteEdit() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak Note Content")
        }
        try {
            speechLauncherNoteEdit.launch(intent)
        } catch (_: Exception) {
            Toast.makeText(context, "Voice input not available", Toast.LENGTH_SHORT).show()
        }
    }

    // Safe Camera Launcher
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

    // Google Play Services ML Kit Document Scanner
    val scannerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { activityResult ->
        if (activityResult.resultCode == Activity.RESULT_OK) {
            val result = GmsDocumentScanningResult.fromActivityResultIntent(activityResult.data)
            val pdf = result?.pdf
            if (scanAsPdf && pdf != null) {
                try {
                    val tempPdf = File(context.cacheDir, "scan_${System.currentTimeMillis()}.pdf")
                    context.contentResolver.openInputStream(pdf.uri)?.use { input ->
                        FileOutputStream(tempPdf).use { output -> input.copyTo(output) }
                    }
                    selectedTempFile = tempPdf
                    selectedMimeType = "application/pdf"
                    if (docNameText.isBlank()) {
                        val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                        docNameText = "Scan $timeStamp"
                    }
                    Toast.makeText(context, "Scanned ${pdf.pageCount} page(s) to PDF 📄", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Failed to save PDF: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            } else {
                val pageUri = result?.pages?.firstOrNull()?.imageUri
                if (pageUri != null) {
                    try {
                        val tempJpg = File(context.cacheDir, "scan_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(pageUri)?.use { input ->
                            FileOutputStream(tempJpg).use { output -> input.copyTo(output) }
                        }
                        selectedTempFile = tempJpg
                        selectedMimeType = "image/jpeg"
                        if (docNameText.isBlank()) {
                            val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
                            docNameText = "Scan $timeStamp"
                        }
                        Toast.makeText(context, "Scanned & cropped image to JPG 🖼️", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(context, "Failed to save scan: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    fun launchDocumentScanner() {
        try {
            val optionsBuilder = GmsDocumentScannerOptions.Builder()
                .setGalleryImportAllowed(true)
                .setScannerMode(SCANNER_MODE_FULL)

            if (scanAsPdf) {
                optionsBuilder.setPageLimit(100)
                optionsBuilder.setResultFormats(RESULT_FORMAT_PDF)
            } else {
                optionsBuilder.setPageLimit(1)
                optionsBuilder.setResultFormats(RESULT_FORMAT_JPEG)
            }

            val options = optionsBuilder.build()

            val activity = context as? Activity
            if (activity == null) {
                handleCameraClick()
                return
            }

            val client = GmsDocumentScanning.getClient(options)
            client.getStartScanIntent(activity)
                .addOnSuccessListener { intentSender ->
                    scannerLauncher.launch(
                        IntentSenderRequest.Builder(intentSender).build()
                    )
                }
                .addOnFailureListener { e ->
                    Toast.makeText(context, "Document scanner unavailable, using camera", Toast.LENGTH_SHORT).show()
                    handleCameraClick()
                }
        } catch (e: Exception) {
            handleCameraClick()
        }
    }

    // File Picker
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

    // Sync animation
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

    Scaffold(
        containerColor = ClayAppBg
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            when (currentScreen) {
                // ============================================
                // SCREEN 1: HOME (THE 3D CLAY CABINET)
                // ============================================
                AppScreen.HOME -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Professional App Header Card (App Icon, Clean Filing, STUDIO badge, Document filing system subheader)
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = CardWhite,
                            shadowElevation = 3.dp,
                            border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                // Left: App Icon + Clean Filing + STUDIO badge + Subtitle
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Image(
                                        painter = painterResource(id = R.drawable.app_icon),
                                        contentDescription = "Clean Filing",
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                    Column(verticalArrangement = Arrangement.Center) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "Clean Filing",
                                                fontSize = 17.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = TextDark
                                            )
                                            Surface(
                                                shape = RoundedCornerShape(8.dp),
                                                color = PurpleAccent.copy(alpha = 0.15f),
                                                border = androidx.compose.foundation.BorderStroke(0.8.dp, PurpleAccent.copy(alpha = 0.5f))
                                            ) {
                                                Text(
                                                    text = "STUDIO",
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PurpleDark,
                                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.5.dp)
                                                )
                                            }
                                        }
                                        Text(
                                            text = "Document filing system",
                                            fontSize = 11.5.sp,
                                            color = TextSubtle,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }

                                // Right: Sync & Settings buttons
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val hasCloud = repository.hasCloudConnection()
                                    // Sync Button with animated rotation
                                    IconButton(
                                        onClick = { triggerSync(silent = false) },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Sync,
                                            contentDescription = "Sync",
                                            tint = if (isSyncing) AmberGold else if (hasCloud) EmeraldGreen else TextSubtle,
                                            modifier = if (isSyncing) Modifier.rotate(syncRotation) else Modifier
                                        )
                                    }

                                    // Settings Button
                                    IconButton(
                                        onClick = { showSettingsModal = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            Icons.Filled.Settings,
                                            contentDescription = "Settings",
                                            tint = TextSubtle
                                        )
                                    }
                                }
                            }
                        }

                        // 3D Clay Cabinet Casing
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 580.dp)
                                    .clip(RoundedCornerShape(44.dp))
                                    .background(ClayCabinetGradient)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(44.dp))
                                    .padding(18.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Drawer 1: IN (Scan & File)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1.25f)
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(ClaySlotBg)
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(ClayDrawerGradient)
                                            .clickable { currentScreen = AppScreen.FILE_IN },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            NeonHandle(width = 110.dp, height = 14.dp)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "IN",
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 2.5.sp
                                            )
                                            Text(
                                                text = "Scan & File",
                                                color = PillLavender,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Drawer 2: ESSENTIALS (Pinned)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(0.95f)
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(ClaySlotBg)
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(ClayDrawerGradient)
                                            .clickable { currentScreen = AppScreen.ESSENTIALS },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            NeonHandle(width = 80.dp, height = 12.dp)
                                            Spacer(modifier = Modifier.height(5.dp))
                                            Text(
                                                text = "📌 ESSENTIALS",
                                                color = Color.White,
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 1.5.sp
                                            )
                                            Text(
                                                text = "${documents.count { it.isPinned }} pinned files",
                                                color = PillLavender,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }

                                // Drawer 3: OUT (Archive / Cabinet)
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1.25f)
                                        .clip(RoundedCornerShape(32.dp))
                                        .background(ClaySlotBg)
                                        .padding(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(28.dp))
                                            .background(ClayDrawerGradient)
                                            .clickable { currentScreen = AppScreen.FILE_CABINET },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            NeonHandle(width = 110.dp, height = 14.dp)
                                            Spacer(modifier = Modifier.height(6.dp))
                                            Text(
                                                text = "OUT",
                                                color = Color.White,
                                                fontSize = 16.sp,
                                                fontWeight = FontWeight.Black,
                                                letterSpacing = 2.5.sp
                                            )
                                            Text(
                                                text = "${documents.size} documents",
                                                color = PillLavender,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ============================================
                // SCREEN 2: "FILE" (CENTERED SCAN VIEW & INTAKE)
                // ============================================
                AppScreen.FILE_IN -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Sub-screen header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = CardWhite,
                                shadowElevation = 3.dp,
                                modifier = Modifier
                                    .size(42.dp)
                                    .clickable {
                                        currentScreen = AppScreen.HOME
                                        selectedTempFile = null
                                    }
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text("✕", fontSize = 18.sp, fontWeight = FontWeight.Black, color = TextDark)
                                }
                            }
                            Text(
                                text = "File",
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Black,
                                color = TextDark
                            )
                        }

                        if (selectedTempFile == null) {
                            // Format Toggle Pill [ PDF | JPG ]
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(PillLavender)
                                        .padding(4.dp)
                                        .width(160.dp),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (scanAsPdf) CardWhite else Color.Transparent)
                                            .clickable { scanAsPdf = true }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "PDF",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (scanAsPdf) PurpleDark else ClaySlotBg
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(20.dp))
                                            .background(if (!scanAsPdf) CardWhite else Color.Transparent)
                                            .clickable { scanAsPdf = false }
                                            .padding(vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "JPG",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Black,
                                            color = if (!scanAsPdf) PurpleDark else ClaySlotBg
                                        )
                                    }
                                }
                            }

                            // Centered Large Scan Area
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .padding(vertical = 18.dp)
                                    .clip(RoundedCornerShape(38.dp))
                                    .background(ClayScanCardGradient)
                                    .clickable { launchDocumentScanner() },
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Center
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(96.dp)
                                            .clip(RoundedCornerShape(30.dp))
                                            .background(Color.White.copy(alpha = 0.18f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        ScanReticleIcon(modifier = Modifier.size(48.dp))
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Scan Document",
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Black,
                                        color = Color.White
                                    )
                                }
                            }

                            // Clay Action Row: 📷 Quick Photo, 📁 Choose File, 📝 Quick Note
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .clickable { handleCameraClick() },
                                    shape = RoundedCornerShape(26.dp),
                                    color = CardWhite,
                                    shadowElevation = 4.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("📷", fontSize = 30.sp)
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .clickable { filePickerLauncher.launch("*/*") },
                                    shape = RoundedCornerShape(26.dp),
                                    color = CardWhite,
                                    shadowElevation = 4.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("📁", fontSize = 30.sp)
                                    }
                                }

                                Surface(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(RoundedCornerShape(26.dp))
                                        .clickable { showNoteDialog = true },
                                    shape = RoundedCornerShape(26.dp),
                                    color = CardWhite,
                                    shadowElevation = 4.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("📝", fontSize = 30.sp)
                                    }
                                }
                            }
                        } else {
                            // File Intake Form (When a file is ready to be filed)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                // Preview Container
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(160.dp),
                                    shape = RoundedCornerShape(24.dp),
                                    color = CardWhite,
                                    shadowElevation = 3.dp
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        if (selectedMimeType.startsWith("image/")) {
                                            AsyncImage(
                                                model = selectedTempFile,
                                                contentDescription = "Preview",
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Text(
                                                    if (selectedMimeType.contains("pdf")) "📄" else "📝",
                                                    fontSize = 48.sp
                                                )
                                                Text(
                                                    selectedTempFile?.name ?: "",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = TextDark
                                                )
                                            }
                                        }
                                    }
                                }

                                // Document Name Input
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    color = CardWhite,
                                    shadowElevation = 2.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Document Name", fontSize = 11.sp, color = TextSubtle, fontWeight = FontWeight.Bold)
                                            BasicTextField(
                                                value = docNameText,
                                                onValueChange = { docNameText = it },
                                                textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                singleLine = true
                                            )
                                        }
                                        IconButton(onClick = { startSpeech(isForName = true) }) {
                                            Text("🎤", fontSize = 18.sp)
                                        }
                                    }
                                }

                                // Labels Input
                                Surface(
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(20.dp),
                                    color = CardWhite,
                                    shadowElevation = 2.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text("Labels / Tags", fontSize = 11.sp, color = TextSubtle, fontWeight = FontWeight.Bold)
                                            BasicTextField(
                                                value = labelsText,
                                                onValueChange = { labelsText = it },
                                                textStyle = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = TextDark),
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                singleLine = true
                                            )
                                        }
                                        IconButton(onClick = { startSpeech(isForName = false) }) {
                                            Text("🎤", fontSize = 18.sp)
                                        }
                                    }
                                }

                                // Pin to Essentials Toggle
                                Surface(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(20.dp))
                                        .clickable { isPinned = !isPinned },
                                    shape = RoundedCornerShape(20.dp),
                                    color = if (isPinned) Color(0xFFFFEBEE) else CardWhite,
                                    shadowElevation = 2.dp
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.PushPin,
                                                contentDescription = "Pin",
                                                tint = if (isPinned) RedPin else TextSubtle.copy(alpha = 0.4f)
                                            )
                                            Text(
                                                "Pin to Essentials",
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isPinned) RedPin else TextSubtle
                                            )
                                        }
                                        Switch(
                                            checked = isPinned,
                                            onCheckedChange = { isPinned = it },
                                            colors = SwitchDefaults.colors(checkedThumbColor = RedPin)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Save to Vault Button
                                Button(
                                    onClick = {
                                        val temp = selectedTempFile
                                        if (temp != null && temp.exists()) {
                                            val finalName = if (docNameText.isNotBlank()) docNameText.trim() else "Document ${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}"
                                            scope.launch {
                                                val doc = repository.saveFileToVault(
                                                    name = finalName,
                                                    notes = labelsText.trim(),
                                                    isPinned = isPinned,
                                                    sourceFile = temp,
                                                    mimeType = selectedMimeType
                                                )
                                                documents = repository.getDocuments()
                                                selectedTempFile = null
                                                docNameText = ""
                                                labelsText = ""
                                                isPinned = false
                                                Toast.makeText(context, "Saved to Vault! 🗄️", Toast.LENGTH_SHORT).show()
                                                currentScreen = AppScreen.FILE_CABINET
                                                scope.launch {
                                                    repository.uploadDocAsync(doc)
                                                }
                                            }
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(52.dp),
                                    shape = RoundedCornerShape(22.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleDark)
                                ) {
                                    Text("Save to Vault 🗄️", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                }

                                // Cancel Button
                                OutlinedButton(
                                    onClick = { selectedTempFile = null },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Text("Cancel / Retake", fontSize = 14.sp, color = TextSubtle)
                                }
                            }
                        }
                    }
                }

                // ============================================
                // SCREEN 3: ESSENTIALS (PINNED DOCUMENTS)
                // ============================================
                AppScreen.ESSENTIALS -> {
                    val pinnedDocs = documents.filter { it.isPinned }
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Sub-screen header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = CardWhite,
                                    shadowElevation = 3.dp,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clickable { currentScreen = AppScreen.HOME }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("‹", fontSize = 22.sp, fontWeight = FontWeight.Black, color = TextDark)
                                    }
                                }
                                Text(
                                    text = "📌 Essentials",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextDark
                                )
                            }
                            Text(
                                text = "${pinnedDocs.size} pinned",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSubtle
                            )
                        }

                        if (pinnedDocs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("📌", fontSize = 48.sp)
                                    Text(
                                        "No pinned essentials yet",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextDark
                                    )
                                    Text(
                                        "Tap the pin icon on any file in your cabinet\nto keep it handy right here.",
                                        fontSize = 12.sp,
                                        color = TextSubtle,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(pinnedDocs, key = { it.id }) { doc ->
                                    StreamlinedDocCard(
                                        doc = doc,
                                        onOpen = {
                                            if (doc.filePath.endsWith(".txt", ignoreCase = true) || doc.fileName.endsWith(".txt", ignoreCase = true)) {
                                                noteDocToView = doc
                                                isEditingNote = false
                                            } else {
                                                openDocumentFile(context, doc, repository)
                                            }
                                        },
                                        onDownload = {
                                            downloadDocumentToStorage(context, doc)
                                        },
                                        onEdit = {
                                            if (doc.filePath.endsWith(".txt", ignoreCase = true) || doc.fileName.endsWith(".txt", ignoreCase = true)) {
                                                noteDocToView = doc
                                                isEditingNote = true
                                            } else {
                                                docToRename = doc
                                            }
                                        },
                                        onTogglePin = {
                                            val updated = repository.togglePin(doc.id)
                                            documents = updated
                                            val targetDoc = updated.find { it.id == doc.id } ?: doc.copy(isPinned = !doc.isPinned)
                                            scope.launch { repository.togglePinRemoteAsync(targetDoc) }
                                        },
                                        onShare = { shareDocumentFile(context, doc) },
                                        onDelete = { docToDelete = doc }
                                    )
                                }
                            }
                        }
                    }
                }

                // ============================================
                // SCREEN 4: OUT (HIGH-DENSITY FILE CABINET)
                // ============================================
                AppScreen.FILE_CABINET -> {
                    val filteredDocs = documents.filter { doc ->
                        val matchesSearch = cabinetSearchQuery.isBlank() ||
                                doc.name.contains(cabinetSearchQuery, ignoreCase = true) ||
                                doc.notes.contains(cabinetSearchQuery, ignoreCase = true)
                        val matchesPinned = !cabinetFilterPinnedOnly || doc.isPinned
                        matchesSearch && matchesPinned
                    }

                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Sub-screen header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = CardWhite,
                                    shadowElevation = 3.dp,
                                    modifier = Modifier
                                        .size(42.dp)
                                        .clickable { currentScreen = AppScreen.HOME }
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("‹", fontSize = 22.sp, fontWeight = FontWeight.Black, color = TextDark)
                                    }
                                }
                                Text(
                                    text = "File Cabinet",
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Black,
                                    color = TextDark
                                )
                            }
                            Text(
                                text = "${documents.size} files",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextSubtle
                            )
                        }

                        // Search Bar & Filter
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(20.dp),
                                color = CardWhite,
                                shadowElevation = 2.dp
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text("🔍", fontSize = 14.sp)
                                    Box(modifier = Modifier.weight(1f)) {
                                        if (cabinetSearchQuery.isEmpty()) {
                                            Text(
                                                "Search documents, tags...",
                                                fontSize = 14.sp,
                                                color = TextSubtle.copy(alpha = 0.6f)
                                            )
                                        }
                                        BasicTextField(
                                            value = cabinetSearchQuery,
                                            onValueChange = { cabinetSearchQuery = it },
                                            textStyle = TextStyle(
                                                fontSize = 14.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = TextDark
                                            ),
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    if (cabinetSearchQuery.isNotEmpty()) {
                                        IconButton(
                                            onClick = { cabinetSearchQuery = "" },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Text("✕", fontSize = 12.sp, color = TextSubtle)
                                        }
                                    }
                                }
                            }

                            // Pinned Filter Toggle Pill (Red Pin)
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (cabinetFilterPinnedOnly) Color(0xFFFFEBEE) else CardWhite,
                                shadowElevation = 2.dp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .clickable { cabinetFilterPinnedOnly = !cabinetFilterPinnedOnly }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PushPin,
                                        contentDescription = "Pinned",
                                        tint = if (cabinetFilterPinnedOnly) RedPin else TextSubtle.copy(alpha = 0.4f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        "Pinned",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (cabinetFilterPinnedOnly) RedPin else TextSubtle
                                    )
                                }
                            }
                        }

                        // Streamlined Document List
                        if (filteredDocs.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No documents found",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextSubtle
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .weight(1f),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredDocs, key = { it.id }) { doc ->
                                    StreamlinedDocCard(
                                        doc = doc,
                                        onOpen = {
                                            if (doc.filePath.endsWith(".txt", ignoreCase = true) || doc.fileName.endsWith(".txt", ignoreCase = true)) {
                                                noteDocToView = doc
                                                isEditingNote = false
                                            } else {
                                                openDocumentFile(context, doc, repository)
                                            }
                                        },
                                        onDownload = {
                                            downloadDocumentToStorage(context, doc)
                                        },
                                        onEdit = {
                                            if (doc.filePath.endsWith(".txt", ignoreCase = true) || doc.fileName.endsWith(".txt", ignoreCase = true)) {
                                                noteDocToView = doc
                                                isEditingNote = true
                                            } else {
                                                docToRename = doc
                                            }
                                        },
                                        onTogglePin = {
                                            val updated = repository.togglePin(doc.id)
                                            documents = updated
                                            val targetDoc = updated.find { it.id == doc.id } ?: doc.copy(isPinned = !doc.isPinned)
                                            scope.launch { repository.togglePinRemoteAsync(targetDoc) }
                                        },
                                        onShare = { shareDocumentFile(context, doc) },
                                        onDelete = { docToDelete = doc }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // DIALOGS
    if (showSettingsModal) {
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
                    shape = RoundedCornerShape(26.dp),
                    color = CardWhite,
                    shadowElevation = 6.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("⚙️", fontSize = 20.sp)
                                Text("Settings & Cloud Backup", fontSize = 17.sp, fontWeight = FontWeight.Black, color = TextDark)
                            }
                            IconButton(onClick = { showSettingsModal = false }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = TextSubtle)
                            }
                        }

                        var activeCloudMode by remember { mutableStateOf(repository.getActiveCloudMode()) }

                        // ============================================
                        // OPTION 1: LOCAL ONLY (NO CLOUD SETUP)
                        // ============================================
                        val isLocalActive = (activeCloudMode == CloudSyncMode.LOCAL_ONLY)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = if (isLocalActive) Color(0xFFF0FDF4) else SurfaceRaised,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isLocalActive) EmeraldGreen.copy(alpha = 0.6f) else BorderColor)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("📱", fontSize = 18.sp)
                                        Text("Option 1: Local Only (Offline)", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDark)
                                    }
                                    if (isLocalActive) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = EmeraldGreen.copy(alpha = 0.15f)
                                        ) {
                                            Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = EmeraldGreen, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text(
                                    "Everything stays private on this phone. Zero cloud connection or accounts required.",
                                    fontSize = 11.5.sp,
                                    color = TextSubtle,
                                    lineHeight = 16.sp
                                )
                                if (!isLocalActive) {
                                    OutlinedButton(
                                        onClick = {
                                            repository.setActiveCloudMode(CloudSyncMode.LOCAL_ONLY)
                                            activeCloudMode = CloudSyncMode.LOCAL_ONLY
                                            Toast.makeText(context, "Switched to Local Only (Offline)", Toast.LENGTH_SHORT).show()
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.height(34.dp)
                                    ) {
                                        Text("Switch to Local Only", fontSize = 11.sp, color = TextDark)
                                    }
                                }
                            }
                        }

                        // ============================================
                        // OPTION 2: PRIVATE GOOGLE DRIVE (1-TAP)
                        // ============================================
                        val isDriveConnected = googleAccountEmail.isNotBlank()
                        val isDriveActive = (activeCloudMode == CloudSyncMode.GOOGLE_DRIVE)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = if (isDriveActive) Color(0xFFFAF5FF) else SurfaceRaised,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isDriveActive) PurpleAccent.copy(alpha = 0.6f) else BorderColor)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("☁️", fontSize = 18.sp)
                                        Text("Option 2: Private Google Drive", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDark)
                                    }
                                    if (isDriveActive) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = PurpleAccent.copy(alpha = 0.15f)
                                        ) {
                                            Text("ACTIVE", fontSize = 9.sp, fontWeight = FontWeight.Black, color = PurpleDark, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    } else if (isDriveConnected) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = SurfaceHover
                                        ) {
                                            Text("CONNECTED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = TextSubtle, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text(
                                    "One-tap connection for your own private backup. Automatically stores and syncs your documents and notes in a 'Clean Filing' folder in your personal Google Drive.",
                                    fontSize = 11.5.sp,
                                    color = TextSubtle,
                                    lineHeight = 16.sp
                                )

                                if (isDriveConnected) {
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Text("Account: $googleAccountEmail", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = EmeraldGreen)
                                        Text("Folder: Google Drive / Clean Filing", fontSize = 11.sp, color = TextFaint)
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        if (isDriveActive) {
                                            Button(
                                                onClick = { triggerSync(silent = false) },
                                                colors = ButtonDefaults.buttonColors(containerColor = PurpleDark),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.weight(1f).height(38.dp)
                                            ) {
                                                Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("Sync Private Drive", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        } else {
                                            Button(
                                                onClick = {
                                                    repository.setActiveCloudMode(CloudSyncMode.GOOGLE_DRIVE)
                                                    activeCloudMode = CloudSyncMode.GOOGLE_DRIVE
                                                    Toast.makeText(context, "Switched to Private Google Drive", Toast.LENGTH_SHORT).show()
                                                    triggerSync(silent = false)
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = PurpleDark),
                                                shape = RoundedCornerShape(12.dp),
                                                modifier = Modifier.weight(1f).height(38.dp)
                                            ) {
                                                Text("Use Private Drive", fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                disconnectGoogleAccount()
                                                activeCloudMode = repository.getActiveCloudMode()
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.height(38.dp),
                                            colors = ButtonDefaults.outlinedButtonColors(contentColor = RoseRed)
                                        ) {
                                            Text("Disconnect", fontSize = 11.sp)
                                        }
                                    }
                                } else {
                                    Button(
                                        onClick = { startGoogleSignIn() },
                                        colors = ButtonDefaults.buttonColors(containerColor = PurpleDark),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.fillMaxWidth().height(42.dp)
                                    ) {
                                        Icon(Icons.Filled.AccountCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Sign in with Google", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 13.5.sp)
                                    }
                                }
                            }
                        }

                        // ============================================
                        // OPTION 3: SHARED FILE CABINET (PASTE URL)
                        // ============================================
                        val isScriptConnected = appsScriptUrlInput.isNotBlank()
                        val isScriptActive = (activeCloudMode == CloudSyncMode.SHARED_CABINET && isScriptConnected)
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(18.dp),
                            color = if (isScriptActive) Color(0xFFEFF6FF) else SurfaceRaised,
                            border = androidx.compose.foundation.BorderStroke(1.dp, if (isScriptActive) Color(0xFF3B82F6) else BorderColor)
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text("🤝", fontSize = 18.sp)
                                        Text("Option 3: Shared File Cabinet", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextDark)
                                    }
                                    if (isScriptActive) {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFFDBEAFE)
                                        ) {
                                            Text("ACTIVE (SHARED)", fontSize = 9.sp, fontWeight = FontWeight.Black, color = Color(0xFF1D4ED8), modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                                        }
                                    }
                                }
                                Text(
                                    "Shared mutual vault between spouses or partners. Both devices sync to the same shared cabinet, pins, and documents.",
                                    fontSize = 11.5.sp,
                                    color = TextSubtle,
                                    lineHeight = 16.sp
                                )

                                BasicTextField(
                                    value = appsScriptUrlInput,
                                    onValueChange = { appsScriptUrlInput = it },
                                    textStyle = TextStyle(color = TextDark, fontSize = 12.sp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(CardWhite)
                                        .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    singleLine = true
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val trimmed = appsScriptUrlInput.trim()
                                            repository.setScriptUrl(trimmed)
                                            if (trimmed.isNotBlank()) {
                                                repository.setActiveCloudMode(CloudSyncMode.SHARED_CABINET)
                                                activeCloudMode = CloudSyncMode.SHARED_CABINET
                                                Toast.makeText(context, "Connected to Shared Cabinet! Syncing...", Toast.LENGTH_SHORT).show()
                                                triggerSync(silent = false)
                                            } else {
                                                activeCloudMode = repository.getActiveCloudMode()
                                                Toast.makeText(context, "URL is blank", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = PurpleDark),
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.weight(1f).height(38.dp)
                                    ) {
                                        Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (isScriptActive) "Sync Shared Cabinet" else "Connect & Sync Shared", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    }

                                    if (isScriptConnected) {
                                        OutlinedButton(
                                            onClick = {
                                                appsScriptUrlInput = ""
                                                repository.setScriptUrl("")
                                                activeCloudMode = repository.getActiveCloudMode()
                                                Toast.makeText(context, "Removed Shared Cabinet", Toast.LENGTH_SHORT).show()
                                            },
                                            shape = RoundedCornerShape(10.dp),
                                            modifier = Modifier.height(38.dp)
                                        ) {
                                            Text("Disconnect", fontSize = 11.sp, color = RoseRed)
                                        }
                                    }
                                }

                                if (isScriptConnected) {
                                    OutlinedButton(
                                        onClick = {
                                            val shareText = "Here is our Clean Vault shared cabinet link. In Clean Vault Settings, paste this under 'Option 3: Shared File Cabinet':\n\n" + appsScriptUrlInput.trim()
                                            val intent = Intent(Intent.ACTION_SEND).apply {
                                                type = "text/plain"
                                                putExtra(Intent.EXTRA_TEXT, shareText)
                                            }
                                            context.startActivity(Intent.createChooser(intent, "Share Vault Link"))
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        modifier = Modifier.fillMaxWidth().height(36.dp)
                                    ) {
                                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = PurpleDark)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Send Shared Link to Partner / Wife", fontSize = 11.sp, color = PurpleDark, fontWeight = FontWeight.SemiBold)
                                    }
                                }
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

    
    // DELETE CONFIRMATION DIALOG
    if (docToDelete != null) {
        val target = docToDelete!!
        AlertDialog(
            onDismissRequest = { docToDelete = null },
            shape = RoundedCornerShape(24.dp),
            containerColor = CardWhite,
            title = {
                Text("Delete Document", fontWeight = FontWeight.Bold, color = TextDark)
            },
            text = {
                Text(
                    "Are you sure you want to delete \"${target.name}\"? This cannot be undone.",
                    color = TextSubtle,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val updated = repository.deleteDocument(target.id)
                        documents = updated
                        scope.launch {
                            repository.deleteDocRemoteAsync(target.id, target.driveFileId, target.name)
                        }
                        Toast.makeText(context, "Deleted \"${target.name}\"", Toast.LENGTH_SHORT).show()
                        docToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedPin),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Delete", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { docToDelete = null },
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text("Cancel", color = TextSubtle, fontWeight = FontWeight.SemiBold)
                }
            }
        )
    }

    if (showNoteDialog) {
        BasicAlertDialog(
            onDismissRequest = { showNoteDialog = false },
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
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Filled.EditNote, contentDescription = null, tint = AmberGold)
                                Text("Write Note", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextMain)
                            }
                            IconButton(onClick = { showNoteDialog = false }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = null, tint = TextMuted)
                            }
                        }

                        // Note Title (Optional)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("Note Title (optional)", fontSize = 11.5.sp, color = TextMuted)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceRaised)
                                    .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (noteTitleInput.isEmpty()) {
                                        Text("e.g. WiFi password, Insurance details...", color = TextFaint, fontSize = 12.sp)
                                    }
                                    BasicTextField(
                                        value = noteTitleInput,
                                        onValueChange = { noteTitleInput = it },
                                        singleLine = true,
                                        textStyle = TextStyle(color = TextMain, fontSize = 12.sp),
                                        cursorBrush = SolidColor(AccentColor),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }

                        // Note Body
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Note Content", fontSize = 11.5.sp, color = TextMuted)
                                Surface(
                                    shape = CircleShape,
                                    color = SurfaceRaised,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                                    onClick = { startSpeechNote() },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Filled.Mic, contentDescription = "Dictate Note", tint = AmberGold, modifier = Modifier.size(13.dp))
                                    }
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 120.dp, max = 220.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(SurfaceRaised)
                                    .border(1.dp, BorderColor, RoundedCornerShape(10.dp))
                                    .padding(10.dp)
                            ) {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopStart) {
                                    if (noteBodyInput.isEmpty()) {
                                        Text("Type or dictate your note here...", color = TextFaint, fontSize = 12.5.sp)
                                    }
                                    BasicTextField(
                                        value = noteBodyInput,
                                        onValueChange = { noteBodyInput = it },
                                        textStyle = TextStyle(color = TextMain, fontSize = 12.5.sp, lineHeight = 18.sp),
                                        cursorBrush = SolidColor(AccentColor),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }

                        // Action Buttons: Cancel and Attach
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { showNoteDialog = false },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.weight(1f).height(38.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text("Cancel", fontSize = 12.sp)
                            }

                            Button(
                                onClick = {
                                    if (noteBodyInput.isBlank() && noteTitleInput.isBlank()) {
                                        Toast.makeText(context, "Note is empty", Toast.LENGTH_SHORT).show()
                                        return@Button
                                    }
                                    try {
                                        val timeStamp = java.text.SimpleDateFormat("yyyy-MM-dd_HHmm", java.util.Locale.US).format(java.util.Date())
                                        val cleanTitle = noteTitleInput.trim().replace(Regex("[^a-zA-Z0-9._ -]"), "_")
                                        val fileName = if (cleanTitle.isNotBlank()) "${cleanTitle}.txt" else "Note_${timeStamp}.txt"
                                        val noteFile = File(context.cacheDir, fileName)
                                        val fullText = if (noteTitleInput.isNotBlank()) "${noteTitleInput.trim()}\n\n${noteBodyInput.trim()}" else noteBodyInput.trim()
                                        noteFile.writeText(fullText)

                                        selectedTempFile = noteFile
                                        selectedMimeType = "text/plain"
                                        if (docNameText.isBlank()) {
                                            docNameText = if (cleanTitle.isNotBlank()) cleanTitle else "Note $timeStamp"
                                        }

                                        showNoteDialog = false
                                        Toast.makeText(context, "Note attached! 📝", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "Failed to save note: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = AmberGold),
                                modifier = Modifier.weight(1.5f).height(38.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = null, tint = Color.Black, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Attach Note", fontWeight = FontWeight.Bold, color = Color.Black, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // EMBEDDED NOTE VIEWER & EDITOR DIALOG

    noteDocToView?.let { noteDoc ->
        var noteTitleEdit by remember(noteDoc) { mutableStateOf(noteDoc.name) }
        var noteLabelsEdit by remember(noteDoc) { mutableStateOf(noteDoc.notes) }

        BasicAlertDialog(
            onDismissRequest = {
                noteDocToView = null
                isEditingNote = false
            },
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
                    shape = RoundedCornerShape(24.dp),
                    color = CardWhite,
                    shadowElevation = 6.dp,
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        // Dialog Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
                                Text("📝", fontSize = 20.sp)
                                Text(
                                    text = if (isEditingNote) "Edit Note" else noteDoc.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = TextDark,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (isEditingNote) {
                                    Surface(
                                        shape = CircleShape,
                                        color = SurfaceRaised,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderColor),
                                        onClick = { startSpeechNoteEdit() },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text("🎤", fontSize = 16.sp)
                                        }
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                                IconButton(
                                    onClick = {
                                        noteDocToView = null
                                        isEditingNote = false
                                    },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(Icons.Filled.Close, contentDescription = null, tint = TextSubtle)
                                }
                            }
                        }

                        // Content Area
                        if (!isEditingNote) {
                            if (noteDoc.notes.isNotBlank()) {
                                Text("🏷️ ${noteDoc.notes}", color = TextSubtle, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }

                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 300.dp)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(SurfaceRaised)
                                    .border(1.dp, BorderColor, RoundedCornerShape(14.dp))
                                    .padding(14.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = noteEditText.ifEmpty { "Empty note" },
                                    color = TextDark,
                                    fontSize = 14.sp,
                                    lineHeight = 20.sp
                                )
                            }
                        } else {
                            // EDIT MODE: Title, Labels, and Content are all editable!
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // Title Field
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Note Title", fontSize = 11.sp, color = TextSubtle, fontWeight = FontWeight.Bold)
                                    BasicTextField(
                                        value = noteTitleEdit,
                                        onValueChange = { noteTitleEdit = it },
                                        textStyle = TextStyle(color = TextDark, fontSize = 14.sp, fontWeight = FontWeight.Bold),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SurfaceRaised)
                                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        singleLine = true
                                    )
                                }

                                // Labels Field
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Labels / Tags", fontSize = 11.sp, color = TextSubtle, fontWeight = FontWeight.Bold)
                                    BasicTextField(
                                        value = noteLabelsEdit,
                                        onValueChange = { noteLabelsEdit = it },
                                        textStyle = TextStyle(color = TextDark, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SurfaceRaised)
                                            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        singleLine = true
                                    )
                                }

                                // Content Field
                                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text("Note Content", fontSize = 11.sp, color = TextSubtle, fontWeight = FontWeight.Bold)
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 120.dp, max = 220.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(SurfaceRaised)
                                            .border(1.dp, PurpleDark.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                                            .padding(12.dp)
                                            .verticalScroll(rememberScrollState())
                                    ) {
                                        BasicTextField(
                                            value = noteEditText,
                                            onValueChange = { noteEditText = it },
                                            textStyle = TextStyle(color = TextDark, fontSize = 13.5.sp, lineHeight = 19.sp),
                                            cursorBrush = SolidColor(PurpleDark),
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            }
                        }

                        // Bottom Action Buttons
                        if (!isEditingNote) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Copy
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText(noteDoc.name, noteEditText)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "Copied note to clipboard! 📋", Toast.LENGTH_SHORT).show()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp), tint = PurpleDark)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy", fontSize = 11.5.sp, color = PurpleDark)
                                }

                                // 2. Share
                                OutlinedButton(
                                    onClick = { shareDocumentFile(context, noteDoc) },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(14.dp), tint = PurpleDark)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Share", fontSize = 11.5.sp, color = PurpleDark)
                                }

                                // 3. Edit (Pencil)
                                Button(
                                    onClick = {
                                        noteTitleEdit = noteDoc.name
                                        noteLabelsEdit = noteDoc.notes
                                        isEditingNote = true
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleDark),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1.1f).height(38.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Filled.Edit, contentDescription = "Edit", tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Edit", fontSize = 11.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            // In Edit Mode: Cancel & Save Note
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        noteTitleEdit = noteDoc.name
                                        noteLabelsEdit = noteDoc.notes
                                        noteEditText = try { val f = File(noteDoc.filePath); if (f.exists()) f.readText() else "" } catch (_: Exception) { "" }
                                        isEditingNote = false
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1f).height(40.dp)
                                ) {
                                    Text("Cancel", fontSize = 12.sp)
                                }

                                Button(
                                    onClick = {
                                        try {
                                            val updatedName = noteTitleEdit.trim().ifBlank { noteDoc.name }
                                            val updatedNotes = noteLabelsEdit.trim()
                                            repository.renameDocument(noteDoc.id, updatedName, updatedNotes)
                                            val updated = repository.updateNoteContent(noteDoc.id, noteEditText)
                                            if (updated != null) {
                                                val finalDoc = updated.copy(name = updatedName, notes = updatedNotes)
                                                noteDocToView = finalDoc
                                                documents = repository.getDocuments()
                                                scope.launch {
                                                    repository.updateDocRemoteAsync(finalDoc, updatedName, updatedNotes)
                                                    repository.updateNoteRemoteAsync(finalDoc, noteEditText)
                                                }
                                            }
                                            isEditingNote = false
                                            Toast.makeText(context, "Note saved successfully! ✅", Toast.LENGTH_SHORT).show()
                                        } catch (e: Exception) {
                                            Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = PurpleDark),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.weight(1.3f).height(40.dp)
                                ) {
                                    Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Save Note", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 12.5.sp)
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

private fun handleIncomingShare(
    context: Context,
    intent: Intent,
    onFileSelected: (file: File, mimeType: String, suggestedName: String) -> Unit
) {
    val action = intent.action ?: return
    try {
        if (action == Intent.ACTION_SEND) {
            val uri: Uri? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            } ?: intent.data ?: intent.clipData?.let { cd ->
                if (cd.itemCount > 0) cd.getItemAt(0).uri else null
            }

            if (uri != null) {
                val rawType = intent.type ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
                val displayName = getFileNameFromUri(context, uri) ?: "shared_doc"
                val ext = when {
                    rawType.contains("pdf", ignoreCase = true) || displayName.endsWith(".pdf", ignoreCase = true) -> "pdf"
                    rawType.contains("png", ignoreCase = true) || displayName.endsWith(".png", ignoreCase = true) -> "png"
                    rawType.contains("jpeg", ignoreCase = true) || rawType.contains("jpg", ignoreCase = true) || displayName.endsWith(".jpg", ignoreCase = true) || displayName.endsWith(".jpeg", ignoreCase = true) -> "jpg"
                    else -> displayName.substringAfterLast('.', "dat")
                }
                val mimeType = if (ext == "pdf") "application/pdf" else if (ext == "png") "image/png" else if (ext == "jpg") "image/jpeg" else rawType

                val tempFile = File(context.cacheDir, "shared_${System.currentTimeMillis()}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                }

                val suggestedName = displayName.substringBeforeLast('.')
                onFileSelected(tempFile, mimeType, suggestedName)
            }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val uris: ArrayList<Uri>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
            }

            if (!uris.isNullOrEmpty()) {
                if (uris.size == 1) {
                    val uri = uris[0]
                    val rawType = intent.type ?: context.contentResolver.getType(uri) ?: "application/octet-stream"
                    val displayName = getFileNameFromUri(context, uri) ?: "shared_doc"
                    val ext = if (rawType.contains("pdf")) "pdf" else if (rawType.contains("png")) "png" else "jpg"
                    val tempFile = File(context.cacheDir, "shared_${System.currentTimeMillis()}.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    }
                    onFileSelected(tempFile, if (ext == "pdf") "application/pdf" else "image/jpeg", displayName.substringBeforeLast('.'))
                } else {
                    val tempPdf = File(context.cacheDir, "shared_multi_${System.currentTimeMillis()}.pdf")
                    val converted = convertImagesToPdf(context, uris, tempPdf)
                    if (converted) {
                        val dateStr = SimpleDateFormat("yyyyMMdd_HHmm", Locale.getDefault()).format(Date())
                        onFileSelected(tempPdf, "application/pdf", "Multi-page Document $dateStr")
                    } else {
                        val uri = uris[0]
                        val displayName = getFileNameFromUri(context, uri) ?: "shared_doc"
                        val tempFile = File(context.cacheDir, "shared_${System.currentTimeMillis()}.jpg")
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                        }
                        onFileSelected(tempFile, "image/jpeg", displayName.substringBeforeLast('.'))
                    }
                }
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Error reading shared file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun convertImagesToPdf(context: Context, uris: List<Uri>, destFile: File): Boolean {
    val pdfDoc = PdfDocument()
    try {
        var pageNum = 1
        for (uri in uris) {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            } ?: continue

            val pageInfo = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, pageNum).create()
            val page = pdfDoc.startPage(pageInfo)
            page.canvas.drawBitmap(bitmap, 0f, 0f, null)
            pdfDoc.finishPage(page)
            bitmap.recycle()
            pageNum++
        }
        if (pageNum == 1) return false
        destFile.outputStream().use { out ->
            pdfDoc.writeTo(out)
        }
        return true
    } catch (_: Exception) {
        return false
    } finally {
        pdfDoc.close()
    }
}

private fun getFileNameFromUri(context: Context, uri: Uri): String? {
    var result: String? = null
    if (uri.scheme == "content") {
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) {
                        result = it.getString(idx)
                    }
                }
            }
        } catch (_: Exception) {}
    }
    if (result == null) {
        result = uri.path
        val cut = result?.lastIndexOf('/') ?: -1
        if (cut != -1) {
            result = result?.substring(cut + 1)
        }
    }
    return result
}
