package com.example.piperreader

import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SherpaTtsBackend : TtsBackend {
    private var ttsEngine: OfflineTts? = null
    private val mutex = Mutex()
    private var currentSampleRate = 22050

    override suspend fun initialize(modelInfo: PiperModelInfo) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                // Предварительно освобождаем старый движок
                ttsEngine?.release()
                ttsEngine = null

                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = modelInfo.onnxPath,
                            tokens = modelInfo.tokensPath,
                            dataDir = modelInfo.espeakDataPath,
                            noiseScale = 0.667f,
                            noiseScaleW = 0.8f,
                            lengthScale = 1.0f
                        ),
                        numThreads = 4,
                        debug = false,
                        provider = "cpu"
                    )
                )
                val engine = OfflineTts(assetManager = null, config = config)
                ttsEngine = engine
                currentSampleRate = engine.sampleRate()
            }
        }
    }

    override suspend fun generate(text: String): TtsAudioResult? {
        return mutex.withLock {
            withContext(Dispatchers.IO) {
                val engine = ttsEngine ?: return@withContext null
                val audio = engine.generate(text)
                if (audio.samples.isNotEmpty()) {
                    TtsAudioResult(audio.samples, audio.sampleRate)
                } else {
                    null
                }
            }
        }
    }

    override fun getSampleRate(): Int {
        return ttsEngine?.sampleRate() ?: currentSampleRate
    }

    override fun release() {
        try {
            ttsEngine?.release()
            ttsEngine = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}