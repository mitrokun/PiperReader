package com.example.piperreader

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import android.os.Environment
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

class PiperViewModel(application: Application) : AndroidViewModel(application) {
    private val context = getApplication<Application>()
    private val prefs = context.getSharedPreferences("PiperTTS_Prefs", Context.MODE_PRIVATE)

    // --- Настройки и состояния интерфейса ---
    var keepScreenOn by mutableStateOf(prefs.getBoolean("keepScreenOn", false))
        private set

    var isEditing by mutableStateOf(prefs.getBoolean("isEditing", true))
    var activeIndex by mutableIntStateOf(prefs.getInt("activeIndex", -1))
    var chunksList by mutableStateOf(listOf<ChunkInfo>())
    var sentencePauseMs by mutableIntStateOf(prefs.getInt("sentencePauseMs", 150))
    var pauseInput by mutableStateOf(sentencePauseMs.toString())

    var isPlaying by mutableStateOf(false)
    var isPaused by mutableStateOf(false)
    var statusText by mutableStateOf("Ready")
    var readyModelName by mutableStateOf("")

    var selectedModelName by mutableStateOf(prefs.getString("selectedModel", "") ?: "")
    var selectedModel by mutableStateOf<PiperModelInfo?>(null)
    var modelsList by mutableStateOf(listOf<PiperModelInfo>())

    // Хранилище текущего сырого текста для EditText
    var rawText by mutableStateOf("")
    var cursorPosition by mutableStateOf<Int?>(null)

    // Таймер сна
    var timerRemainingSeconds by mutableIntStateOf(0)
    var timerInputMinutes by mutableStateOf("30")

    private val textStorageFile = File(context.filesDir, "last_session_text.txt")
    val normalizer = RussianNormalizer(context)

    // Текущий движок синтеза (в будущем его можно подменять на системный TTS)
    private val ttsBackend: TtsBackend = SherpaTtsBackend()

    private var playJob: Job? = null
    private var timerJob: Job? = null

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var wasPausedByFocusLoss = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                if (isPlaying && !isPaused) {
                    isPaused = true
                    wasPausedByFocusLoss = true
                }
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                if (wasPausedByFocusLoss) {
                    isPaused = false
                    wasPausedByFocusLoss = false
                }
            }
        }
    }

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener(focusChangeListener)
        .build()

    private val defaultText = "Быстрый старт.\n\n" +
            "1. Загрузите модели в `PiperModels` во внутреннем хранилище. Зайдите в SET и обновите список.\n" +
            "2. Откройте текстовый файл. Или вставьте текст из буфера. Или просто напишите его здесь.\n" +
            "3. Нажмите PLAY для запуска синтеза.\n\n" +
            "В режиме READ вы можете выбрать любой блок, чтобы начать чтение с указанного места.\n\n" +
            "--------------------------------------------------\n\n" +
            "Welcome to Piper Reader — offline book reader.\n\n" +
            "Getting started is simple:\n" +
            "1. Upload your models to the `PiperModels` folder in your internal storage. Go to SET and refresh the list.\n" +
            "2. Open a text file (OPEN), paste text from your clipboard (PASTE), or type it yourself in EDIT mode.\n" +
            "3. Press PLAY to start speech synthesis.\n\n" +
            "While in READ mode, you can simply tap on any text block to start reading directly from that paragraph!"

    init {
        // Фоновая загрузка необходимых ресурсов при старте
        viewModelScope.launch {
            normalizer.loadDictionary()
            loadSavedText()
            scanModels()
        }
    }

    private suspend fun loadSavedText() {
        val savedText = withContext(Dispatchers.IO) {
            if (textStorageFile.exists()) textStorageFile.readText() else defaultText
        }
        rawText = savedText

        if (!isEditing) {
            chunksList = withContext(Dispatchers.Default) { splitText(savedText) }
        }
    }

    fun saveTextToStorage(text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                textStorageFile.writeText(text)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateKeepScreenOn(enabled: Boolean) {
        keepScreenOn = enabled
        prefs.edit { putBoolean("keepScreenOn", enabled) }
    }

    fun updateSentencePauseMs(pauseMs: Int) {
        sentencePauseMs = pauseMs
        prefs.edit { putInt("sentencePauseMs", pauseMs) }
    }

    fun scanModels() {
        val modelsDir = File(Environment.getExternalStorageDirectory(), "PiperModels")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        val globalEspeakDir = File(modelsDir, "espeak-ng-data")
        val found = mutableListOf<PiperModelInfo>()

        modelsDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory && dir.name != "espeak-ng-data") {
                val onnxFile = dir.listFiles()?.find { it.name.endsWith(".onnx") }
                val tokensFile = dir.listFiles()?.find { it.name == "tokens.txt" }
                val localEspeakDir = File(dir, "espeak-ng-data")

                val finalEspeakPath = when {
                    localEspeakDir.exists() -> localEspeakDir.absolutePath
                    globalEspeakDir.exists() -> globalEspeakDir.absolutePath
                    else -> null
                }

                if (onnxFile != null && tokensFile != null && finalEspeakPath != null) {
                    found.add(
                        PiperModelInfo(
                            name = dir.name,
                            onnxPath = onnxFile.absolutePath,
                            tokensPath = tokensFile.absolutePath,
                            espeakDataPath = finalEspeakPath
                        )
                    )
                }
            }
        }
        modelsList = found

        val prevSelected = found.find { it.name == selectedModelName }
        if (prevSelected != null) {
            onModelSelected(prevSelected)
        } else if (found.isNotEmpty()) {
            onModelSelected(found.first())
        }
    }

    fun onModelSelected(model: PiperModelInfo) {
        selectedModel = model
        selectedModelName = model.name
        prefs.edit { putString("selectedModel", model.name) }

        viewModelScope.launch {
            statusText = "Loading model: ${model.name}..."
            try {
                ttsBackend.initialize(model)
                readyModelName = model.name
                statusText = "Model ready: ${model.name}"
            } catch (e: Exception) {
                statusText = "Engine error: ${e.message}"
            }
        }
    }

    fun loadFileFromUri(uri: Uri) {
        viewModelScope.launch {
            try {
                statusText = "Loading file..."
                val fileContent = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { inputStream ->
                        inputStream.bufferedReader().readText()
                    } ?: ""
                }
                if (fileContent.isNotEmpty()) {
                    rawText = fileContent
                    isEditing = false
                    activeIndex = 0
                    statusText = "Parsing text..."
                    chunksList = withContext(Dispatchers.Default) { splitText(fileContent) }
                    statusText = "File uploaded"
                }
            } catch (e: Exception) {
                statusText = "Error: ${e.localizedMessage}"
            }
        }
    }

    fun startPlayback() {
        if (selectedModel == null) {
            statusText = "Select a voice model first!"
            return
        }

        if (isPlaying) {
            if (isPaused) {
                isPaused = false
                wasPausedByFocusLoss = false
                statusText = "Resumed"
            } else {
                isPaused = true
                wasPausedByFocusLoss = false
                statusText = "Pause"
            }
            return
        }

        viewModelScope.launch {
            if (isEditing) {
                statusText = "Parsing text..."
                saveTextToStorage(rawText)
                chunksList = withContext(Dispatchers.Default) { splitText(rawText) }
                isEditing = false
                if (activeIndex < 0) activeIndex = 0
            }

            if (chunksList.isEmpty()) return@launch

            if (activeIndex !in chunksList.indices) activeIndex = 0
            val startIndex = activeIndex

            val result = audioManager.requestAudioFocus(focusRequest)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                isPlaying = true
                isPaused = false
                wasPausedByFocusLoss = false

                playJob = viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val audioChannel = Channel<AudioTask>(capacity = 3)

                        val producer = launch(Dispatchers.IO) {
                            try {
                                val targetModel = selectedModel?.name
                                while (readyModelName != targetModel && isActive) {
                                    delay(100)
                                }

                                for (i in startIndex until chunksList.size) {
                                    if (!isActive) break
                                    val chunkText = chunksList[i].text
                                    val sentences = splitIntoSentences(chunkText)

                                    for (j in sentences.indices) {
                                        if (!isActive) break
                                        val sentence = sentences[j]
                                        val normalizedSentence = normalizer.normalize(sentence)
                                        val startGenMs = System.currentTimeMillis()

                                        val audio = ttsBackend.generate(normalizedSentence)

                                        if (audio != null && audio.samples.isNotEmpty()) {
                                            val isLast = (j == sentences.lastIndex)
                                            val samplesToSend = if (!isLast && sentencePauseMs > 0) {
                                                val randomizedPause = getRandomizedPause(sentencePauseMs)
                                                val silenceSize = (audio.sampleRate * (randomizedPause / 1000.0)).toInt()
                                                val merged = FloatArray(audio.samples.size + silenceSize)
                                                System.arraycopy(audio.samples, 0, merged, 0, audio.samples.size)
                                                merged
                                            } else {
                                                audio.samples
                                            }

                                            val endGenMs = System.currentTimeMillis()
                                            val processSec = (endGenMs - startGenMs) / 1000.0
                                            val durationSec = audio.samples.size.toDouble() / audio.sampleRate
                                            val rtfStr = String.format(Locale.US, "%.2f", processSec / durationSec)

                                            audioChannel.send(
                                                AudioTask(
                                                    index = i,
                                                    samples = samplesToSend,
                                                    sampleRate = audio.sampleRate,
                                                    rtf = rtfStr,
                                                    isLastOfChunk = isLast
                                                )
                                            )
                                        }
                                    }
                                }
                            } finally {
                                audioChannel.close()
                            }
                        }

                        val consumer = launch(Dispatchers.IO) {
                            var currentTrack: AudioTrack? = null
                            var currentSampleRate = -1
                            var totalWrittenFrames = 0

                            try {
                                for (task in audioChannel) {
                                    if (!isActive) break
                                    activeIndex = task.index
                                    statusText = "${task.index + 1}/${chunksList.size} [RTF: ${task.rtf}]"

                                    if (currentTrack == null || currentSampleRate != task.sampleRate) {
                                        currentTrack?.release()
                                        currentSampleRate = task.sampleRate
                                        totalWrittenFrames = 0

                                        val minBuffer = AudioTrack.getMinBufferSize(
                                            currentSampleRate,
                                            AudioFormat.CHANNEL_OUT_MONO,
                                            AudioFormat.ENCODING_PCM_16BIT
                                        )
                                        currentTrack = AudioTrack.Builder()
                                            .setAudioAttributes(
                                                AudioAttributes.Builder()
                                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                    .build()
                                            )
                                            .setAudioFormat(
                                                AudioFormat.Builder()
                                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                                    .setSampleRate(currentSampleRate)
                                                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                                    .build()
                                            )
                                            .setBufferSizeInBytes(minBuffer * 4)
                                            .setTransferMode(AudioTrack.MODE_STREAM)
                                            .build()
                                        currentTrack.play()
                                    }

                                    currentTrack.let { track ->
                                        val shortArray = ShortArray(task.samples.size) { i ->
                                            (task.samples[i] * 32767).toInt().coerceIn(-32768, 32767).toShort()
                                        }
                                        var offset = 0
                                        val chunkSize = AudioTrack.getMinBufferSize(
                                            currentSampleRate,
                                            AudioFormat.CHANNEL_OUT_MONO,
                                            AudioFormat.ENCODING_PCM_16BIT
                                        ) * 2

                                        while (offset < shortArray.size && isActive) {
                                            if (isPaused && isActive) {
                                                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                                                snapshotFlow { isPaused }.first { !it }
                                            }
                                            if (track.playState != AudioTrack.PLAYSTATE_PLAYING && isActive) track.play()

                                            val length = minOf(chunkSize, shortArray.size - offset)
                                            val written = track.write(shortArray, offset, length)
                                            if (written < 0) break
                                            offset += written
                                            totalWrittenFrames += written
                                        }
                                    }

                                    if (task.isLastOfChunk && task.index < chunksList.size - 1 && isActive) {
                                        val nextChunkText = chunksList[task.index + 1].text.trimStart()
                                        val isDialogue = nextChunkText.startsWith("—") || nextChunkText.startsWith("–")
                                        val currentPause = if (isDialogue) sentencePauseMs * 2 else getRandomizedPause(sentencePauseMs)

                                        if (currentPause > 0) {
                                            val silenceSamples = (currentSampleRate * (currentPause / 1000.0)).toInt()
                                            val silenceBuffer = ShortArray(silenceSamples)
                                            var silenceOffset = 0
                                            val chunkSize = AudioTrack.getMinBufferSize(
                                                currentSampleRate,
                                                AudioFormat.CHANNEL_OUT_MONO,
                                                AudioFormat.ENCODING_PCM_16BIT
                                            ) * 2

                                            while (silenceOffset < silenceBuffer.size && isActive) {
                                                if (isPaused && isActive) {
                                                    if (currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING) currentTrack.pause()
                                                    snapshotFlow { isPaused }.first { !it }
                                                }
                                                if (currentTrack.playState != AudioTrack.PLAYSTATE_PLAYING && isActive) currentTrack.play()

                                                val len = minOf(chunkSize, silenceBuffer.size - silenceOffset)
                                                val written = currentTrack.write(silenceBuffer, silenceOffset, len)
                                                if (written < 0) break
                                                silenceOffset += written
                                                totalWrittenFrames += written
                                            }
                                        }
                                    }
                                }

                                currentTrack?.let { track ->
                                    while (track.playbackHeadPosition < totalWrittenFrames && isActive) {
                                        delay(50)
                                    }
                                }
                            } finally {
                                currentTrack?.pause()
                                currentTrack?.flush()
                                currentTrack?.release()
                            }
                        }

                        joinAll(producer, consumer)
                    } finally {
                        isPlaying = false
                        statusText = "Ready"
                        audioManager.abandonAudioFocusRequest(focusRequest)
                    }
                }
            } else {
                statusText = "No audio focus"
            }
        }
    }

    fun stopPlayback() {
        playJob?.cancel()
        isPlaying = false
        isPaused = false
        statusText = "Stopped"
        timerJob?.cancel()
        timerJob = null
        timerRemainingSeconds = 0
    }

    fun startSleepTimer(mins: Int) {
        timerJob?.cancel()
        if (mins > 0) {
            timerJob = viewModelScope.launch {
                var remaining = mins * 60
                timerRemainingSeconds = remaining
                while (remaining > 0) {
                    delay(10000L)
                    remaining -= 10
                    timerRemainingSeconds = remaining
                }
                stopPlayback()
                statusText = "Timer finished"
            }
            statusText = "Timer set for $mins min"
        }
    }

    fun cancelSleepTimer() {
        timerJob?.cancel()
        timerJob = null
        timerRemainingSeconds = 0
        statusText = "Timer cancelled"
    }

    fun updatePrefsOnStop() {
        prefs.edit().apply {
            putString("selectedModel", selectedModelName)
            putBoolean("isEditing", isEditing)
            putInt("activeIndex", activeIndex)
            putInt("sentencePauseMs", sentencePauseMs)
            putBoolean("keepScreenOn", keepScreenOn)
            apply()
        }
    }

    override fun onCleared() {
        stopPlayback()
        ttsBackend.release()
    }
}