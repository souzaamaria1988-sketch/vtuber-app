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
 * Detector de voz v3 — "fail-open": a prioridade e DETECTAR.
 *
 * CORRECAO v2.1: a versao anterior checava activeRecordingConfigurations
 * antes de abrir o microfone — mas o assistente "Ok Google" deixa uma
 * gravacao PERMANENTE em muitos aparelhos, e o detector achava que o
 * microfone estava sempre ocupado e nunca abria (por isso "falava e nao
 * respondia"). Agora:
 *
 *  - Recua so durante CHAMADAS (AudioManager.mode != NORMAL — confiavel);
 *  - Descarta ~100 ms de warmup a cada abertura (alguns aparelhos so
 *    entregam zeros nesse periodo, e o codigo antigo interpretava como
 *    "silenciado" e desistia);
 *  - Limiar adaptativo: acompanha o ruido ambiente (EMA) e dispara tambem
 *    quando rms > 4x o ruido — funciona mesmo com o slider alto;
 *  - onLevel: informa o RMS medido (barra de nivel no Studio).
 */
class VoiceDetector(
    private val appContext: Context,
    private val threshold: Double = 400.0,
    private val talkFrames: Int = 2,
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

        val talkNeeded = talkFrames.coerceIn(1, 4)
        val silenceNeeded = silenceFrames.coerceIn(1, 4)
        var talkCount = 0
        var silenceCount = 0
        var noiseFloor = 120.0
        var silenced = 0

        while (currentCoroutineContext().isActive && !stopRequested) {

            // pausado pelo usuario: microfone 100% livre
            if (paused) {
                if (isTalking) { isTalking = false; onChange(false) }
                onLevel?.invoke(0.0)
                delay(500)
                continue
            }

            // unico bloqueio "duro": chamada em andamento (celular/VoIP)
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
                // silenciado pelo sistema (outro app tem prioridade) — tenta de novo
                silenced++
                if (isTalking) {
                    isTalking = false
                    talkCount = 0; silenceCount = 0
                    onChange(false)
                }
                onLevel?.invoke(0.0)
                delay(if (silenced > 8) 2500L else 800L)
                continue
            }
            silenced = 0
            onLevel?.invoke(rms)

            // limiar adaptativo: aprende o ruido do ambiente enquanto calado
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

            // microfone solto entre amostras
            delay(if (isTalking || loud) 200L else 700L)
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

    /** Alguns aparelhos so entregam zeros nos primeiros ~100 ms: descarta. */
    private fun discardWarmup(rec: AudioRecord, buffer: ShortArray) {
        val deadline = System.currentTimeMillis() + 100
        while (System.currentTimeMillis() < deadline) {
            rec.read(buffer, 0, buffer.size)
        }
    }

    private fun callActive(am: AudioManager?): Boolean {
        if (am == null) return false
        val mode = runCatching { am.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        return mode != AudioManager.MODE_NORMAL
    }

    /** Amostra ~450 ms (ja sem warmup) e devolve o RMS; null se silenciado. */
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
            val deadline = System.currentTimeMillis() + 450
            while (System.currentTimeMillis() < deadline) {
                buffer.clear()
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
