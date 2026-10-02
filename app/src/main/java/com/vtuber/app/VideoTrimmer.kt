package com.vtuber.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.nio.ByteBuffer

/**
 * Corte de video SEM recodificar: copia os samples originais entre
 * [startMs, endMs] com MediaExtractor + MediaMuxer. Rapido e sem perda.
 * (O inicio "pula" para o keyframe anterior a marca — normal em corte
 * sem re-encoding.)
 */
object VideoTrimmer {

    fun trim(
        context: Context,
        src: Uri,
        startMs: Long,
        endMs: Long,
        onProgress: (Int) -> Unit = {},
    ): File? {
        var muxer: MediaMuxer? = null
        val extractors = mutableListOf<MediaExtractor>()
        try {
            // duracao real
            var durationUs = 0L
            run {
                val probe = MediaExtractor()
                probe.setDataSource(context, src, null)
                for (i in 0 until probe.trackCount) {
                    val f = probe.getTrackFormat(i)
                    if (f.containsKey(MediaFormat.KEY_DURATION)) {
                        val d = f.getLong(MediaFormat.KEY_DURATION)
                        if (d > durationUs) durationUs = d
                    }
                }
                probe.release()
            }
            if (durationUs <= 0L) return null

            val startUs = (startMs * 1000).coerceIn(0L, durationUs - 1)
            val endUs = (endMs * 1000).coerceIn(startUs + 1, durationUs)
            if (endUs - startUs < 100_000L) return null // corte < 100 ms

            val dir = File(context.filesDir, "videos").apply { mkdirs() }
            val out = File(dir, "corte_" + System.currentTimeMillis() + ".mp4")
            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackCount = run {
                val ex = MediaExtractor()
                ex.setDataSource(context, src, null)
                val c = ex.trackCount
                ex.release()
                c
            }
            if (trackCount <= 0) return null

            val buffer = ByteBuffer.allocate(4 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            val muxTrackIds = mutableListOf<Int>()

            // um extractor por pista evita problemas de interleaving
            for (i in 0 until trackCount) {
                val ex = MediaExtractor()
                ex.setDataSource(context, src, null)
                ex.selectTrack(i)
                ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                extractors.add(ex)
                muxTrackIds.add(muxer.addTrack(ex.getTrackFormat(i)))
            }
            muxer.start()

            var lastP = -1
            for (i in 0 until trackCount) {
                val ex = extractors[i]
                val trackId = muxTrackIds[i]
                while (true) {
                    info.offset = 0
                    buffer.clear()
                    info.size = ex.readSampleData(buffer, 0)
                    if (info.size < 0) break
                    val t = ex.sampleTime
                    if (t < 0 || t > endUs) break
                    info.presentationTimeUs = t
                    info.flags = ex.sampleFlags
                    muxer.writeSampleData(trackId, buffer, info)
                    val frac = ((t - startUs).coerceAtLeast(0L).toDouble() /
                            (endUs - startUs)).coerceIn(0.0, 1.0)
                    val p = (((i + frac) / trackCount) * 100).toInt()
                    if (p != lastP) { lastP = p; onProgress(p) }
                    ex.advance()
                }
            }
            muxer.stop()
            muxer.release()
            muxer = null
            onProgress(100)
            return if (out.length() > 0L) out else null
        } catch (t: Throwable) {
            runCatching { muxer?.release() }
            extractors.forEach { runCatching { it.release() } }
            return null
        }
    }

    /** Salva na galeria (Filmes/VTuber). Android 10+; false em 8/9. */
    fun saveToGallery(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT < 29) return false
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VTuber")
            }
            val uri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values
            ) ?: return false
            context.contentResolver.openOutputStream(uri)?.use { os ->
                file.inputStream().use { ins -> ins.copyTo(os) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    /** Intent de compartilhamento (funciona em qualquer Android 8+). */
    fun shareIntent(context: Context, file: File): Intent? {
        return runCatching {
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            Intent(Intent.ACTION_SEND).apply {
                type = "video/mp4"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }.getOrNull()
    }
}
