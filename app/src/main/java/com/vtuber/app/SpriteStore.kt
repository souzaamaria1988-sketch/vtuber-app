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
    val label: String,
    val number: Int = 999,
)

data class CharacterData(
    val id: String,
    val name: String,
    val xRatio: Float,
    val yRatio: Float,
    val wRatio: Float,
    val frames: List<FrameItem>,
)

data class SpriteSettings(
    var idleAlpha: Float = 0.55f,
    var idleDim: Float = 0.35f,
    var idleOffsetDp: Float = 16f,
    var glowWhenTalking: Boolean = true,
    var glowAlpha: Float = 0.9f,
    var idleIntervalMs: Long = 400L,
    var talkingIntervalMs: Long = 140L,
    var threshold: Double = 400.0,
    var talkFrames: Int = 1,
    var silenceFrames: Int = 2,
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

    private fun frameFromJson(o: JSONObject) = FrameItem(
        id = o.getString("id"),
        category = o.optString("category", "idle"),
        fileName = o.optString("fileName", ""),
        label = o.optString("label", "frame"),
        number = o.optInt("number", 999),
    )

    /** Carrega os personagens (schema v2). Migra automaticamente o schema v1. */
    fun loadCharacters(context: Context): List<CharacterData> {
        val root = rootJson(context) ?: return emptyList()

        val arr = root.optJSONArray("characters")
        if (arr != null) {
            val out = mutableListOf<CharacterData>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val framesArr = o.optJSONArray("frames") ?: JSONArray()
                val frames = (0 until framesArr.length()).map { frameFromJson(framesArr.getJSONObject(it)) }
                out.add(
                    CharacterData(
                        id = o.getString("id"),
                        name = o.optString("name", "Personagem ${i + 1}"),
                        xRatio = o.optDouble("xRatio", 0.5).toFloat(),
                        yRatio = o.optDouble("yRatio", 0.82).toFloat(),
                        wRatio = o.optDouble("wRatio", 0.4).toFloat(),
                        frames = frames,
                    )
                )
            }
            if (out.isNotEmpty()) return out
        }

        // migracao: v1 tinha uma unica lista "frames" solta
        val legacy = root.optJSONArray("frames")
        if (legacy != null && legacy.length() > 0) {
            val frames = (0 until legacy.length()).map { frameFromJson(legacy.getJSONObject(it)) }
            return listOf(
                CharacterData(
                    id = UUID.randomUUID().toString(),
                    name = "Personagem 1",
                    xRatio = 0.5f, yRatio = 0.82f, wRatio = 0.55f,
                    frames = frames,
                )
            )
        }
        return emptyList()
    }

    fun loadSettings(context: Context): SpriteSettings {
        val s = SpriteSettings()
        val root = rootJson(context) ?: return s
        val o = root.optJSONObject("settings") ?: return s
        s.idleAlpha = o.optDouble("idleAlpha", 0.55).toFloat()
        s.idleDim = o.optDouble("idleDim", 0.35).toFloat()
        s.idleOffsetDp = o.optDouble("idleOffsetDp", 16.0).toFloat()
        s.glowWhenTalking = o.optBoolean("glowWhenTalking", true)
        s.glowAlpha = o.optDouble("glowAlpha", 0.9).toFloat()
        s.idleIntervalMs = o.optLong("idleIntervalMs", 400L)
        s.talkingIntervalMs = o.optLong("talkingIntervalMs", 140L)
        s.threshold = o.optDouble("threshold", 400.0)
        s.talkFrames = o.optInt("talkFrames", 1)
        s.silenceFrames = o.optInt("silenceFrames", 2)
        return s
    }

    fun saveAll(context: Context, characters: List<CharacterData>, settings: SpriteSettings) {
        val charsArr = JSONArray()
        characters.forEach { c ->
            val fArr = JSONArray()
            c.frames.forEach { f ->
                fArr.put(
                    JSONObject()
                        .put("id", f.id)
                        .put("category", f.category)
                        .put("fileName", f.fileName)
                        .put("label", f.label)
                        .put("number", f.number)
                )
            }
            charsArr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("name", c.name)
                    .put("xRatio", c.xRatio.toDouble())
                    .put("yRatio", c.yRatio.toDouble())
                    .put("wRatio", c.wRatio.toDouble())
                    .put("frames", fArr)
            )
        }
        val o = JSONObject()
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
        Prefs(context).configJson = JSONObject()
            .put("version", 2)
            .put("characters", charsArr)
            .put("settings", o)
            .toString()
    }

    fun newCharacter(name: String): CharacterData = CharacterData(
        id = UUID.randomUUID().toString(),
        name = name,
        xRatio = 0.5f,
        yRatio = 0.86f,
        wRatio = 0.32f,
        frames = emptyList(),
    )

    // ---------- importacao ----------
    /** Nome com "talk" => Falando; resto => Idle. Numero no fim = ordem. */
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

    /** 1 personagem padrao (assets) quando nada foi importado. */
    fun fallbackCharacters(): List<CharacterData> = listOf(
        CharacterData(
            id = "asset-char-1",
            name = "Personagem 1",
            xRatio = 0.5f, yRatio = 0.82f, wRatio = 0.55f,
            frames = listOf(
                FrameItem("asset-idle-1", "idle", "", "idle1.png", 1),
                FrameItem("asset-idle-2", "idle", "", "idle2.png", 2),
                FrameItem("asset-talk-1", "talking", "", "talking.png", 1),
            )
        )
    )
}
