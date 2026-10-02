package com.vtuber.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

class VoiceDetector(
    private val threshold: Double = 400.0,
    private val talkFrames: Int = 2,
    private val silenceFrames: Int = 6,
) {
    @Volatile var isTalking: Boolean = false
        private set

    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var stopRequested = false

    /**
     * Interrompe a deteccao de qualquer thread: desbloqueia o read() bloqueado
     * e faz o loop sair (o release acontece no finally do run()).
     */
    fun stop() {
        stopRequested = true
        recorder?.let { rec -> runCatching { rec.stop() } }
    }

    /**
     * Loop de deteccao de voz (RMS).
     * ATENCAO: [onChange] e invocado na thread Dispatchers.Default.
     * O chamador precisa despachar para a main thread antes de tocar em Views.
     */
    @SuppressLint("MissingPermission")
    suspend fun run(onChange: (Boolean) -> Unit) = withContext(Dispatchers.Default) {
        val sampleRate = 16000
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(1024)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 2
            )
        } catch (t: Throwable) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            return@withContext
        }

        recorder = rec
        val buffer = ShortArray(minBuf)
        var talkCount = 0
        var silenceCount = 0
        var failures = 0

        try {
            rec.startRecording()
            while (currentCoroutineContext().isActive && !stopRequested) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    // apos stop() o read pode retornar <=0: evita busy-loop infinito
                    failures++
                    if (failures > 50) break
                    continue
                }
                failures = 0

                var sum = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    sum += s * s
                }
                val rms = sqrt(sum / read)
                val loud = rms > threshold

                if (loud) { talkCount++; silenceCount = 0 }
                else      { silenceCount++; talkCount = 0 }

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
            runCatching { rec.stop() }
            rec.release()
            recorder = null
        }
    }
}
