package com.vtuber.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

class VoiceDetector(
    private val threshold: Double = 1500.0,
    private val talkFrames: Int = 3,
    private val silenceFrames: Int = 8,
) {
    @Volatile var isTalking: Boolean = false
        private set

    @SuppressLint("MissingPermission")
    suspend fun run(onChange: (Boolean) -> Unit) = withContext(Dispatchers.Default) {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(2048)

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext
        }

        val buffer = ShortArray(minBuf)
        var talkCount = 0
        var silenceCount = 0

        try {
            recorder.startRecording()
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                var sum = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    sum += s * s
                }
                val rms = kotlin.math.sqrt(sum / read)
                val loud = rms > threshold

                if (loud) { talkCount++; silenceCount = 0 }
                else { silenceCount++; talkCount = 0 }

                val newState = when {
                    talkCount >= talkFrames -> true
                    silenceCount >= silenceFrames -> false
                    else -> isTalking
                }

                if (newState != isTalking) {
                    isTalking = newState
                    onChange(newState)
                }
            }
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}
