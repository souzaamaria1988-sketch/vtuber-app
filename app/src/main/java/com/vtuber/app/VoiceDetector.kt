package com.vtuber.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Detector de voz em MODO COMPARTILHADO (nao briga pelo microfone):
 *  - nao segura o microfone (amostra ~250 ms, fecha, espera);
 *  - verifica chamadas (AudioManager.mode) e gravacoes de outros apps
 *    antes de abrir — se houver, nem encosta;
 *  - silencio absoluto = outro app tem prioridade → recua;
 *  - calibrateNoiseFloor(): "IA" de calibracao (mede o ruido ambiente).
 */
class VoiceDetector(
    private val appContext: Context,
    private val threshold: Double = 400.0,
    private val talkFrames: Int = 2,
    private val silenceFrames: Int = 6,
) {
    @Volatile var isTalking: Boolean = false
        private set

    @Volatile private var stopRequested = false
    @Volatile private var paused = false

    fun stop() { stopRequested = true }
    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Mede o ruido ambiente por ~1.5s (para calibracao automatica). */
    @SuppressLint("MissingPermission")
    suspend fun calibrateNoiseFloor(): Double? = withContext(Dispatchers.Default) {
        val rec = openRecorder() ?: return@withContext null
        val buffer = ShortArray(bufferSize)
        var total = 0.0
        var frames = 0L
        var nonZero = false
        try {
            rec.startRecording()
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var sum = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    if (s != 0.0) nonZero = true
                    sum += s * s
                }
                total += sum
                frames += read
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!nonZero || frames <= 0L) null else sqrt(total / frames)
    }

    /**
     * ATENCAO: [onChange] e invocado na thread Dispatchers.Default.
     */
    suspend fun run(onChange: (Boolean) -> Unit) = withContext(Dispatchers.Default) {
        val am = runCatching {
            appContext.getSystemService(AudioManager::class.java)
        }.getOrNull()

        val talkNeeded = talkFrames.coerceIn(1, 4)
        val silenceNeeded = silenceFrames.coerceIn(1, 3)
        var talkCount = 0
        var silenceCount = 0

        while (currentCoroutineContext().isActive && !stopRequested) {

            if (paused) {
                if (isTalking) { isTalking = false; onChange(false) }
                delay(800)
                continue
            }

            if (micBusy(am)) {
                if (isTalking) {
                    isTalking = false
                    talkCount = 0; silenceCount = 0
                    onChange(false)
                }
                delay(1500)
                continue
            }

            val rms = sampleRms()
            if (rms == null) {
                if (isTalking) {
                    isTalking = false
                    talkCount = 0; silenceCount = 0
                    onChange(false)
                }
                delay(2000)
                continue
            }

            val loud = rms > threshold
            if (loud) { talkCount++; silenceCount = 0 }
            else      { silenceCount++; talkCount = 0 }

            val newState = when {
                talkCount >= talkNeeded -> true
                silenceCount >= silenceNeeded -> false
                else -> isTalking
            }
            if (newState != isTalking) {
                isTalking = newState
                onChange(newState)
            }

            delay(if (isTalking || loud) 300L else 1000L)
        }
    }

    private val sampleRate = 16000
    private var bufferSize = 1024

    private fun openRecorder(): AudioRecord? {
        bufferSize = AudioRecord.getMinBufferSize(
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
                bufferSize * 2
            )
        } catch (t: Throwable) {
            null
        } ?: return null
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return null
        }
        return rec
    }

    /** Ha chamada ativa ou outro app gravando? */
    private fun micBusy(am: AudioManager?): Boolean {
        if (am == null) return false
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (mode != AudioManager.MODE_NORMAL) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val busy = runCatching {
                val configs = am.activeRecordingConfigurations
                configs.isNotEmpty() && configs.any { it.clientAudioSource != 199 }
            }.getOrDefault(false)
            if (busy) return true
        }
        return false
    }

    /** Amostra ~250 ms e devolve o RMS; null se silenciado/falhou. */
    @SuppressLint("MissingPermission")
    private fun sampleRms(): Double? {
        val rec = openRecorder() ?: return null
        val buffer = ShortArray(bufferSize)
        var total = 0.0
        var frames = 0L
        var nonZero = false
        try {
            rec.startRecording()
            val deadline = System.currentTimeMillis() + 250
            while (System.currentTimeMillis() < deadline) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var sum = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    if (s != 0.0) nonZero = true
                    sum += s * s
                }
                total += sum
                frames += read
            }
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (!nonZero || frames <= 0L) return null
        return sqrt(total / frames)
    }
}
