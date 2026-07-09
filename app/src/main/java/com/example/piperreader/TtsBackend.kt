package com.example.piperreader

// Результат генерации конкретного предложения
data class TtsAudioResult(
    val samples: FloatArray,
    val sampleRate: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TtsAudioResult
        if (!samples.contentEquals(other.samples)) return false
        if (sampleRate != other.sampleRate) return false
        return true
    }

    override fun hashCode(): Int {
        var result = samples.contentHashCode()
        result = 31 * result + sampleRate
        return result
    }
}

interface TtsBackend {
    // Инициализация движка под переданную модель
    suspend fun initialize(modelInfo: PiperModelInfo)

    // Синтез текста в аудио-сэмплы
    suspend fun generate(text: String): TtsAudioResult?

    // Получение текущей частоты дискретизации
    fun getSampleRate(): Int

    // Освобождение ресурсов C++ / Системного TTS
    fun release()
}