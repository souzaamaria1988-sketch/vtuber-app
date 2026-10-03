package com.vtuber.app

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detector de voz v4 — LATENCIA BAIXA.
 *
 *  - Dispara na PRIMEIRA amostra alta (antes exigia 2 — era o atraso);
 *  - Amostra 320 ms com pausas de 300 ms quando calado → reacao ~0,3–0,6 s;
 *  - Pausas curtinhas (120 ms) quando ha atividade (rastreio fluido);
 *  - Recua so durante CHAMADAS (AudioManager.mode);
 *  - Descarta ~80 ms de warmup (alguns aparelhos so entregam zeros nele);
 *  - Limiar adaptativo: dispara com rms > threshold OU rms > 4x ruido;
 *  - onLevel: RMS medido (barra de nivel no Studio).
 */
class VoiceDetector(
    private val appContext: Context,
    private val threshold: Double = 400.0,
    private val talkFrames: Int = 1,
    private val silenceFrames: Int = 2,
    private val onLevel: ((Double) -> Unit)? = null,
) {
    @Volatile var isTalking: Boolean = false
        private set

    @Volatile private var stopRequested = false
    @Volatile private var paused = false

    fun stop() { stopRequested = true }
    fun pause() { paused = true }
    fun resume() { paused = false }

    /** Mede o ruido ambiente por ~1.5s (calibracao automatica). */
    @SuppressLint("MissingPermission")
    suspend fun calibrateNoiseFloor(): Double? = withContext(Dispatchers.Default) {
        val rec = openRecorder() ?: return@withContext null
        val buffer = ShortArray(bufferSize)
        var total = 0.0
        var frames = 0L
        try {
            rec.startRecording()
            discardWarmup(rec, buffer)
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                val read = rec.read(buffer, 0, buffer.size)
                if (read <= 0) continue
                var sum = 0.0
                for (i in 0 until read) {
                    val s = buffer[i].toDouble()
                    sum += s * s
                }
                total += sum
                frames += read
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        if (frames <= 0L) null else sqrt(total / frames)
    }

    /**
     * ATENCAO: [onChange] e [onLevel] sao invocados na thread
     * Dispatchers.Default — despache para a main thread antes de tocar em Views.
     */
    suspend fun run(onChange: (Boolean) -> Unit) = withContext(Dispatchers.Default) {
        val am = runCatching {
            appContext.getSystemService(AudioManager::class.java)
        }.getOrNull()

        val talkNeeded = 1 // v4: dispara JÁ na primeira amostra alta
        val silenceNeeded = silenceFrames.coerceIn(1, 3)
        var talkCount = 0
        var silenceCount = 0
        var noiseFloor = 120.0
        var silenced = 0

        while (currentCoroutineContext().isActive && !stopRequested) {

            if (paused) {
                if (isTalking) { isTalking = false; onChange(false) }
                onLevel?.invoke(0.0)
                delay(500)
                continue
            }

            // unico bloqueio "duro": chamada em andamento
            if (callActive(am)) {
                if (isTalking) {
                    isTalking = false
                    talkCount = 0; silenceCount = 0
                    onChange(false)
                }
                onLevel?.invoke(0.0)
                delay(1000)
                continue
            }

            val rms = sampleRms()
            if (rms == null) {
                silenced++
                if (isTalking) {
                    isTalking = false
                    talkCount = 0; silenceCount = 0
                    onChange(false)
                }
                onLevel?.invoke(0.0)
                delay(if (silenced > 8) 2000L else 600L)
                continue
            }
            silenced = 0
            onLevel?.invoke(rms)

            if (!isTalking && rms < threshold) {
                noiseFloor = max(60.0, noiseFloor * 0.85 + rms * 0.15)
            }
            val loud = rms > threshold || (rms > 150.0 && rms > noiseFloor * 4.0)

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

            delay(when {
                isTalking || loud -> 120L
                rms > threshold * 0.55 -> 160L
                else -> 300L
            })
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

    private fun discardWarmup(rec: AudioRecord, buffer: ShortArray) {
        val deadline = System.currentTimeMillis() + 80
        while (System.currentTimeMillis() < deadline) {
            rec.read(buffer, 0, buffer.size)
        }
    }

    private fun callActive(am: AudioManager?): Boolean {
        if (am == null) return false
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return mode != AudioManager.MODE_NORMAL
    }

    /** Amostra ~320 ms (ja sem warmup) e devolve o RMS; null se silenciado. */
    @SuppressLint("MissingPermission")
    private fun sampleRms(): Double? {
        val rec = openRecorder() ?: return null
        val buffer = ShortArray(bufferSize)
        var total = 0.0
        var frames = 0L
        var nonZero = false
        try {
            rec.startRecording()
            discardWarmup(rec, buffer)
            val deadline = System.currentTimeMillis() + 320
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
