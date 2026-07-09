package com.example.piperreader

import android.app.Activity
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.EditText
import android.text.InputType
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlin.math.pow
import kotlin.time.Duration.Companion.milliseconds

// Регулярные выражения для разбивки текста
private val ellipsisRegex = Regex("(?<=…[»\"]{0,2})\\s+")
private val sentenceRegex = Regex(
    // 1. Одиночные буквы (инициалы, г., д., т.е., т.д.)
    "(?<!\\b[а-яА-Яa-zA-Z])" +
            // 2. Двухбуквенные сокращения (ул., пр., ст., им., св., Mr., Dr., Ms., St., Jr., Sr.)
            "(?<!\\bMr|\\bDr|\\bMs|\\bSt|\\bJr|\\bSr|\\bул|\\bпр|\\bгр|\\bсв|\\bст|\\bим)" +
            // 3. Трехбуквенные сокращения (Mrs., Sgt., Col., Gen., etc., пер., наб., бул., стр., тов., ген., кап.)
            "(?<!\\bMrs|\\bSgt|\\bCol|\\bGen|\\betc|\\bпер|\\bнаб|\\bбул|\\bстр|\\bтов|\\bген|\\bкап)" +
            // 4. Четырехбуквенные сокращения (Prof., Capt., корп., проф.)
            "(?<!\\bProf|\\bCapt|\\bкорп|\\bпроф)" +
            "[.!?]+\\s*",
    RegexOption.IGNORE_CASE
)

data class PiperModelInfo(
    val name: String,
    val onnxPath: String,
    val tokensPath: String,
    val espeakDataPath: String
)

data class ChunkInfo(val text: String)

data class AudioTask(
    val index: Int,
    val samples: FloatArray,
    val sampleRate: Int,
    val rtf: String,
    val isLastOfChunk: Boolean = false
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AudioTask
        if (index != other.index) return false
        if (!samples.contentEquals(other.samples)) return false
        if (sampleRate != other.sampleRate) return false
        if (rtf != other.rtf) return false
        if (isLastOfChunk != other.isLastOfChunk) return false
        return true
    }

    override fun hashCode(): Int {
        var result = index
        result = 31 * result + samples.contentHashCode()
        result = 31 * result + sampleRate
        result = 31 * result + rtf.hashCode()
        result = 31 * result + isLastOfChunk.hashCode()
        return result
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: PiperViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < 35) {
            @Suppress("DEPRECATION")
            window.statusBarColor = android.graphics.Color.parseColor("#121212")
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        setContent {
            val ubuntuGreen = Color(0xFF26A269)
            val darkBg = Color(0xFF121212)
            val surfaceBg = Color(0xFF1E1E1E)

            val darkColorScheme = darkColorScheme(
                primary = ubuntuGreen,
                background = darkBg,
                surface = surfaceBg,
                onPrimary = darkBg,
                onBackground = ubuntuGreen,
                onSurface = ubuntuGreen,
                surfaceVariant = Color(0xFF2C2C2C),
                onSurfaceVariant = ubuntuGreen
            )

            MaterialTheme(colorScheme = darkColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PiperMainScreen(activity = this, viewModel = viewModel)
                }
            }
        }
    }

    fun checkStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.os.Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PiperMainScreen(activity: MainActivity, viewModel: PiperViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var hasPermission by remember { mutableStateOf(activity.checkStoragePermission()) }
    val shouldKeepScreenOn = viewModel.keepScreenOn && viewModel.isPlaying && !viewModel.isPaused

    // Ссылка на активный нативный EditText для сбора текста "по требованию"
    var activeEditText by remember { mutableStateOf<EditText?>(null) }

    DisposableEffect(shouldKeepScreenOn) {
        val currentActivity = context as? Activity
        if (shouldKeepScreenOn) {
            currentActivity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            currentActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            currentActivity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Сохранение при сворачивании приложения
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                if (viewModel.isEditing) {
                    activeEditText?.let { viewModel.rawText = it.text.toString() }
                }
                viewModel.saveTextToStorage(viewModel.rawText)
                viewModel.updatePrefsOnStop()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var dropdownExpanded by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    val clipboardManager = remember { context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager }
    val focusRequester = remember { FocusRequester() }
    var focusTrigger by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let { viewModel.loadFileFromUri(it) }
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) viewModel.scanModels()
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .systemBarsPadding(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Memory access is required",
                color = MaterialTheme.colorScheme.primary,
                fontSize = 22.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "For ease of use, Piper Reader loads models directly from the device's internal storage.",
                color = Color.Gray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Where to upload models?", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Once permission is granted, create `/PiperModels/` folder in storage root:", fontSize = 12.sp, color = Color.LightGray)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "📁 PiperModels (root folder)\n" +
                                " ├── 📁 espeak-ng-data (shared data)\n" +
                                " ├── 📁 vits-piper-ru_RU-ruslan-medium\n" +
                                " │     ├── 📁 espeak-ng-data (has priority)\n" +
                                " │     ├── 📄 ru_RU-ruslan-medium.onnx\n" +
                                " │     └── 📄 tokens.txt\n" +
                                " └── 📁 vits-piper-ru_RU-custom-medium\n" +
                                "       ├── 📄 ru_RU-custom-medium.onnx\n" +
                                "       └── 📄 tokens.txt",
                        fontSize = 11.sp, color = Color.LightGray, lineHeight = 18.sp
                    )
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        try {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { data = android.net.Uri.parse("package:${context.packageName}") })
                        } catch (_: Exception) {
                            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("GRANTED ACCESS IN SETTINGS") }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = { hasPermission = activity.checkStoragePermission() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("CHECK PERMISSION") }
        }
        return
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Settings") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Add silence", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            value = viewModel.pauseInput,
                            onValueChange = {
                                viewModel.pauseInput = it
                                it.toIntOrNull()?.let { v -> viewModel.updateSentencePauseMs(v) }
                            },
                            label = { Text("ms") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Text("Added after each sentence", fontSize = 11.sp, color = Color.Gray)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Screen always on", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                            Text("Prevents performance degradation", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = viewModel.keepScreenOn,
                            onCheckedChange = { viewModel.updateKeepScreenOn(it) }
                        )
                    }
                    Button(
                        onClick = {
                            viewModel.scanModels()
                            viewModel.statusText = "Model list updated"
                            showSettings = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("UPDATE MODEL LIST") }

                    HorizontalDivider(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Sleep Timer", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = viewModel.timerInputMinutes,
                                onValueChange = { viewModel.timerInputMinutes = it },
                                label = { Text("min") },
                                modifier = Modifier.weight(0.66f),
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    val mins = viewModel.timerInputMinutes.toIntOrNull() ?: 30
                                    viewModel.startSleepTimer(mins)
                                    showSettings = false
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("START") }
                        }

                        if (viewModel.timerRemainingSeconds > 0) {
                            Button(
                                onClick = {
                                    viewModel.cancelSleepTimer()
                                    showSettings = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF290F0F),
                                    contentColor = Color(0xFFFF8A8A)
                                )
                            ) { Text("CANCEL TIMER") }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("CLOSE") }
            }
        )
    }

    Column(modifier = Modifier.padding(12.dp).fillMaxSize().systemBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val btnMod = Modifier.padding(horizontal = 1.dp)
            val padVals = PaddingValues(horizontal = 4.dp, vertical = 8.dp)

            TextButton(onClick = { showSettings = true }, modifier = btnMod, contentPadding = padVals) { Text("⚙ SET", fontSize = 12.sp) }
            TextButton(onClick = { filePickerLauncher.launch("text/plain") }, modifier = btnMod, contentPadding = padVals) { Text("OPEN", fontSize = 12.sp) }
            TextButton(onClick = {
                if (clipboardManager.hasPrimaryClip()) {
                    val pasted = clipboardManager.primaryClip?.getItemAt(0)?.text?.toString()
                    if (!pasted.isNullOrEmpty()) {
                        viewModel.rawText = pasted
                        viewModel.isEditing = true
                        focusTrigger = true
                    }
                }
            }, modifier = btnMod, contentPadding = padVals) { Text("PASTE", fontSize = 12.sp) }
            TextButton(onClick = {
                viewModel.rawText = ""
                viewModel.activeIndex = -1
                viewModel.isEditing = true
                focusTrigger = true
            }, modifier = btnMod, contentPadding = padVals) { Text("CLEAR", fontSize = 12.sp) }

            TextButton(onClick = {
                if (viewModel.isEditing) {
                    // Забираем актуальный текст из EditText перед парсингом
                    activeEditText?.let { viewModel.rawText = it.text.toString() }

                    scope.launch {
                        viewModel.statusText = "Parsing text..."
                        viewModel.saveTextToStorage(viewModel.rawText)
                        viewModel.chunksList = withContext(Dispatchers.Default) { splitText(viewModel.rawText) }
                        viewModel.isEditing = false
                        viewModel.statusText = "Ready"
                    }
                } else {
                    viewModel.isEditing = true
                    if (viewModel.activeIndex in viewModel.chunksList.indices) {
                        val tempIndex = minOf(viewModel.chunksList.lastIndex, viewModel.activeIndex + 3)
                        val tempOffset = viewModel.rawText.indexOf(viewModel.chunksList[tempIndex].text)
                        if (tempOffset >= 0) {
                            viewModel.cursorPosition = tempOffset
                        }
                    }
                    focusTrigger = true
                }
            }, modifier = btnMod, contentPadding = padVals) {
                Text(if (viewModel.isEditing) "READ" else "EDIT", fontSize = 12.sp)
            }
        }

        var containerHeight by remember { mutableFloatStateOf(0f) }
        var isDraggingPill by remember { mutableStateOf(false) }

        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().onGloballyPositioned { containerHeight = it.size.height.toFloat() }
        ) {
            if (viewModel.isEditing) {
                val themeTextColor = MaterialTheme.colorScheme.primary.toArgb()
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp)
                        .focusRequester(focusRequester),
                    factory = { ctx ->
                        EditText(ctx).apply {
                            activeEditText = this // Сохраняем ссылку на EditText
                            background = null
                            setTextColor(themeTextColor)
                            textSize = 18f
                            gravity = Gravity.TOP or Gravity.START
                            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS

                            setText(viewModel.rawText)
                        }
                    },
                    update = { view ->
                        view.setTextColor(themeTextColor)
                        if (view.text.toString() != viewModel.rawText) {
                            view.setText(viewModel.rawText)
                        }

                        // Применяем позицию курсора, если она была задана
                        viewModel.cursorPosition?.let { pos ->
                            if (pos <= view.text.length) {
                                view.setSelection(pos)
                                viewModel.cursorPosition = null // Сбрасываем, чтобы не зацикливать вызовы
                            }
                        }
                    }
                )

                LaunchedEffect(focusTrigger) {
                    if (focusTrigger) {
                        try {
                            focusRequester.requestFocus()
                            delay(300.milliseconds)
                            if (viewModel.activeIndex in viewModel.chunksList.indices) {
                                val realOffset = viewModel.rawText.indexOf(viewModel.chunksList[viewModel.activeIndex].text)
                                if (realOffset >= 0) {
                                    viewModel.cursorPosition = realOffset
                                }
                            }
                        } catch (_: Exception) {}
                        focusTrigger = false
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val isDragged by listState.interactionSource.collectIsDraggedAsState()
                var showPillWithDelay by remember { mutableStateOf(false) }

                LaunchedEffect(isDragged, isDraggingPill) {
                    if (isDragged || isDraggingPill) showPillWithDelay = true else { delay(1500.milliseconds); showPillWithDelay = false }
                }

                val showScrollPill by remember { derivedStateOf { showPillWithDelay && viewModel.chunksList.isNotEmpty() } }
                val verticalPositionBias by remember {
                    derivedStateOf { if (viewModel.chunksList.size > 1) -1f + (listState.firstVisibleItemIndex.toFloat() / (viewModel.chunksList.size - 1).toFloat()) * 2f else -1f }
                }

                LaunchedEffect(viewModel.activeIndex, viewModel.isEditing, viewModel.chunksList) {
                    if (!viewModel.isEditing && viewModel.activeIndex in viewModel.chunksList.indices) {
                        try { listState.customAnimateScrollToItem(viewModel.activeIndex) } catch (_: Exception) {}
                    }
                }

                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(viewModel.chunksList) { index, chunk ->
                        val isHighlighted = index == viewModel.activeIndex
                        Text(
                            text = chunk.text,
                            fontSize = 18.sp,
                            lineHeight = 26.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        isHighlighted && viewModel.isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        isHighlighted && !viewModel.isPlaying -> MaterialTheme.colorScheme.primary.copy(alpha = 0.04f)
                                        else -> Color.Transparent
                                    },
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .clickable(enabled = !viewModel.isPlaying) { viewModel.activeIndex = index }
                                .padding(8.dp)
                        )
                    }
                }

                if (showScrollPill) {
                    var dragAccumulator by remember { mutableFloatStateOf(0f) }
                    val pillText by remember {
                        derivedStateOf { "${listState.firstVisibleItemIndex + 1} / ${viewModel.chunksList.size}" }
                    }

                    Box(
                        modifier = Modifier
                            .align(BiasAlignment(horizontalBias = 1f, verticalBias = verticalPositionBias))
                            .padding(end = 4.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), RoundedCornerShape(12.dp))
                            .pointerInput(viewModel.chunksList.size, containerHeight) {
                                detectDragGestures(
                                    onDragStart = {
                                        isDraggingPill = true
                                        dragAccumulator = (if (viewModel.chunksList.size > 1) listState.firstVisibleItemIndex.toFloat() / (viewModel.chunksList.size - 1).toFloat() else 0f) * containerHeight
                                    },
                                    onDragEnd = { isDraggingPill = false },
                                    onDragCancel = { isDraggingPill = false },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        dragAccumulator = (dragAccumulator + dragAmount.y).coerceIn(0f, containerHeight)
                                        val targetIndex = ((dragAccumulator / containerHeight) * (viewModel.chunksList.size - 1)).toInt().coerceIn(0, viewModel.chunksList.size - 1)
                                        scope.launch { listState.scrollToItem(targetIndex) }
                                    }
                                )
                            }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(text = pillText, color = Color.Black, fontSize = 11.sp)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.wrapContentHeight().fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = viewModel.selectedModel?.name ?: "Модели не найдены",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Voice") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = { IconButton(onClick = { dropdownExpanded = true }) { Text("▼") } },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                )
                DropdownMenu(expanded = dropdownExpanded, onDismissRequest = { dropdownExpanded = false }, modifier = Modifier.fillMaxWidth().heightIn(max = 450.dp)) {
                    if (viewModel.modelsList.isEmpty()) {
                        DropdownMenuItem(text = { Text("Пусто. Нажмите SCAN", fontSize = 16.sp) }, onClick = { dropdownExpanded = false })
                    } else {
                        viewModel.modelsList.forEach { m ->
                            DropdownMenuItem(
                                text = { Text(text = m.name, fontSize = 18.sp, modifier = Modifier.padding(vertical = 12.dp)) },
                                onClick = {
                                    viewModel.onModelSelected(m)
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { viewModel.startPlayback() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isPlaying && !viewModel.isPaused) MaterialTheme.colorScheme.primary.copy(alpha = 0.35f) else MaterialTheme.colorScheme.primary,
                        contentColor = if (viewModel.isPlaying && !viewModel.isPaused) MaterialTheme.colorScheme.primary.copy(alpha = 0.9f) else Color.Black
                    )
                ) { Text(if (!viewModel.isPlaying) "PLAY" else if (viewModel.isPaused) "RESUME" else "PAUSE") }

                Button(
                    onClick = { viewModel.stopPlayback() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (viewModel.isPlaying) Color(0xFF290F0F) else MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        contentColor = if (viewModel.isPlaying) Color(0xFF9E4E4E) else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                    )
                ) { Text("STOP") }
            }

            val displayStatus = if (viewModel.timerRemainingSeconds > 0) "${viewModel.statusText} (Sleep: ${(viewModel.timerRemainingSeconds + 59) / 60}m)" else viewModel.statusText
            Text(text = displayStatus, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
        }
    }
}

// Парсинг текста на предложения и абзацы
fun splitText(text: String): List<ChunkInfo> {
    val result = mutableListOf<ChunkInfo>()
    val rawLines = text.split("\n")
    val lines = mutableListOf<String>()

    for (rawLine in rawLines) {
        val parts = rawLine.split(ellipsisRegex)
        for (part in parts) {
            val trimmed = part.trim()
            if (trimmed.isNotEmpty()) {
                lines.add(trimmed)
            }
        }
    }

    for (line in lines) {
        val trimmedLine = line.trim()
        if (trimmedLine.isEmpty()) continue

        if (trimmedLine.length <= 600) {
            result.add(ChunkInfo(trimmedLine))
        } else {
            val sentences = mutableListOf<String>()
            var lastIdx = 0
            val matches = sentenceRegex.findAll(line)
            for (match in matches) {
                val end = match.range.last + 1
                val sText = line.substring(lastIdx, end).trim()
                if (sText.isNotEmpty()) sentences.add(sText)
                lastIdx = end
            }
            if (lastIdx < line.length) {
                val rem = line.substring(lastIdx).trim()
                if (rem.isNotEmpty()) sentences.add(rem)
            }

            var currentGroup = StringBuilder()
            for (s in sentences) {
                if (currentGroup.length + s.length > 600) {
                    if (currentGroup.isNotEmpty()) {
                        result.add(ChunkInfo(currentGroup.toString().trim()))
                        currentGroup = StringBuilder()
                    }
                }
                if (currentGroup.isNotEmpty()) currentGroup.append(" ")
                currentGroup.append(s)
            }
            if (currentGroup.isNotEmpty()) result.add(ChunkInfo(currentGroup.toString().trim()))
        }
    }
    return result
}

val EaseOutPower16 = Easing { fraction -> 1f - (1f - fraction).pow(1.6f) }
suspend fun LazyListState.customAnimateScrollToItem(targetIndex: Int) {
    val visibleItems = layoutInfo.visibleItemsInfo
    val targetItem = visibleItems.firstOrNull { it.index == targetIndex }
    if (targetItem != null) {
        this.animateScrollBy(value = targetItem.offset.toFloat(), animationSpec = tween(durationMillis = 600, easing = EaseOutPower16))
    } else {
        this.scrollToItem(targetIndex)
    }
}

fun splitIntoSentences(text: String): List<String> {
    val sentences = mutableListOf<String>()
    var lastIdx = 0
    val matches = sentenceRegex.findAll(text)
    for (match in matches) {
        val end = match.range.last + 1
        val sText = text.substring(lastIdx, end).trim()
        if (sText.isNotEmpty()) sentences.add(sText)
        lastIdx = end
    }
    if (lastIdx < text.length) {
        val rem = text.substring(lastIdx).trim()
        if (rem.isNotEmpty()) sentences.add(rem)
    }
    return if (sentences.isEmpty() && text.isNotEmpty()) listOf(text) else sentences
}

fun getRandomizedPause(basePauseMs: Int): Int {
    if (basePauseMs <= 0) return 0
    val minPause = (basePauseMs * 0.65).toInt()
    return if (minPause < basePauseMs) {
        (minPause..basePauseMs).random()
    } else {
        basePauseMs
    }
}
