package com.vtuber.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class FrameItem(
    val id: String,
    val category: String,      // "idle" | "talking"
    val fileName: String,      // vazio = asset embutido (padrao)
    val label: String,         // nome original do arquivo
    val number: Int = 999,     // numero parsed do nome (ordem)
)

data class SpriteSettings(
    var xRatio: Float = 0.5f,
    var yRatio: Float = 0.82f,
    var wRatio: Float = 0.55f,
    var idleAlpha: Float = 0.55f,
    var idleDim: Float = 0.35f,
    var idleOffsetDp: Float = 16f,
    var glowWhenTalking: Boolean = true,
    var glowAlpha: Float = 0.9f,
    var idleIntervalMs: Long = 400L,
    var talkingIntervalMs: Long = 140L,
    var threshold: Double = 400.0,
    var talkFrames: Int = 2,
    var silenceFrames: Int = 3,
)

object SpriteStore {

    private const val DIR = "sprites"
    private const val MAX_DIM = 1024

    // ---------- persistencia ----------
    private fun rootJson(context: Context): JSONObject? {
        val raw = Prefs(context).configJson
        if (raw.isBlank()) return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    fun loadFrames(context: Context): List<FrameItem> {
        val root = rootJson(context) ?: return emptyList()
        val arr = root.optJSONArray("frames") ?: return emptyList()
        val out = mutableListOf<FrameItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(
                FrameItem(
                    id = o.getString("id"),
                    category = o.optString("category", "idle"),
                    fileName = o.optString("fileName", ""),
                    label = o.optString("label", "frame"),
                    number = o.optInt("number", 999),
                )
            )
        }
        return out
    }

    fun loadSettings(context: Context): SpriteSettings {
        val s = SpriteSettings()
        val root = rootJson(context) ?: return s
        val o = root.optJSONObject("settings") ?: return s
        s.xRatio = o.optDouble("xRatio", 0.5).toFloat()
        s.yRatio = o.optDouble("yRatio", 0.82).toFloat()
        s.wRatio = o.optDouble("wRatio", 0.55).toFloat()
        s.idleAlpha = o.optDouble("idleAlpha", 0.55).toFloat()
        s.idleDim = o.optDouble("idleDim", 0.35).toFloat()
        s.idleOffsetDp = o.optDouble("idleOffsetDp", 16.0).toFloat()
        s.glowWhenTalking = o.optBoolean("glowWhenTalking", true)
        s.glowAlpha = o.optDouble("glowAlpha", 0.9).toFloat()
        s.idleIntervalMs = o.optLong("idleIntervalMs", 400L)
        s.talkingIntervalMs = o.optLong("talkingIntervalMs", 140L)
        s.threshold = o.optDouble("threshold", 400.0)
        s.talkFrames = o.optInt("talkFrames", 2)
        s.silenceFrames = o.optInt("silenceFrames", 3)
        return s
    }

    fun save(context: Context, frames: List<FrameItem>, settings: SpriteSettings) {
        val arr = JSONArray()
        frames.forEach { f ->
            arr.put(
                JSONObject()
                    .put("id", f.id)
                    .put("category", f.category)
                    .put("fileName", f.fileName)
                    .put("label", f.label)
                    .put("number", f.number)
            )
        }
        val o = JSONObject()
            .put("xRatio", settings.xRatio.toDouble())
            .put("yRatio", settings.yRatio.toDouble())
            .put("wRatio", settings.wRatio.toDouble())
            .put("idleAlpha", settings.idleAlpha.toDouble())
            .put("idleDim", settings.idleDim.toDouble())
            .put("idleOffsetDp", settings.idleOffsetDp.toDouble())
            .put("glowWhenTalking", settings.glowWhenTalking)
            .put("glowAlpha", settings.glowAlpha.toDouble())
            .put("idleIntervalMs", settings.idleIntervalMs)
            .put("talkingIntervalMs", settings.talkingIntervalMs)
            .put("threshold", settings.threshold)
            .put("talkFrames", settings.talkFrames)
            .put("silenceFrames", settings.silenceFrames)
        Prefs(context).configJson = JSONObject().put("frames", arr).put("settings", o).toString()
    }

    // ---------- importacao ----------
    /**
     * Importa imagens da galeria. Nome com "talk" => lista Falando;
     * resto => lista Idle. Numero no fim do nome = ordem na animacao.
     */
    fun importFrames(context: Context, uris: List<Uri>): List<FrameItem> {
        val added = mutableListOf<FrameItem>()
        uris.forEach { uri ->
            runCatching {
                val name = displayName(context, uri) ?: "img.png"
                val category = if (name.contains("talk", ignoreCase = true)) "talking" else "idle"
                val number = Regex("""(\d+)\s*$""").find(name)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 999
                val file = copyToFile(context, uri) ?: return@runCatching
                added.add(
                    FrameItem(
                        id = UUID.randomUUID().toString(),
                        category = category,
                        fileName = file.name,
                        label = name,
                        number = number,
                    )
                )
            }
        }
        return added
    }

    fun deleteFrame(context: Context, frame: FrameItem) {
        if (frame.fileName.isBlank()) return
        runCatching { File(File(context.filesDir, DIR), frame.fileName).delete() }
    }

    private fun displayName(context: Context, uri: Uri): String? {
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) return c.getString(idx)
            }
        }
        return uri.lastPathSegment ?: "img.png"
    }

    private fun copyToFile(context: Context, uri: Uri): File? {
        return runCatching {
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            val tmp = File(dir, "tmp_" + UUID.randomUUID().toString() + ".bin")
            val input = context.contentResolver.openInputStream(uri)
                ?: return@runCatching null
            input.use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
            val bmp = decodeSampled(tmp, MAX_DIM)
            if (bmp == null) {
                tmp.delete()
                return@runCatching null
            }
            val out = File(dir, UUID.randomUUID().toString() + ".png")
            out.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
            tmp.delete()
            out
        }.getOrNull()
    }

    // ---------- decodificacao ----------
    fun decodeSampled(file: File, maxDim: Int): Bitmap? {
        if (!file.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / sample > maxDim || bounds.outHeight / sample > maxDim) sample *= 2
        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    /** Carrega o bitmap de um frame (importado ou asset padrao). */
    fun loadBitmap(context: Context, frame: FrameItem, cropTop: Float = 0f): Bitmap? {
        return if (frame.fileName.isBlank()) {
            val raw = runCatching {
                context.assets.open("images/" + frame.label).use { BitmapFactory.decodeStream(it) }
            }.getOrNull() ?: return null
            if (cropTop in 0.01f..0.99f) {
                val visible = (raw.height * cropTop).toInt().coerceIn(1, raw.height)
                runCatching { Bitmap.createBitmap(raw, 0, 0, raw.width, visible) }.getOrDefault(raw)
            } else raw
        } else {
            decodeSampled(File(File(context.filesDir, DIR), frame.fileName), MAX_DIM)
        }
    }

    /** Frames padrao (assets) quando nada foi importado ainda. */
    fun fallbackFrames(): List<FrameItem> = listOf(
        FrameItem("asset-idle-1", "idle", "", "idle1.png", 1),
        FrameItem("asset-idle-2", "idle", "", "idle2.png", 2),
        FrameItem("asset-talk-1", "talking", "", "talking.png", 1),
    )
}
