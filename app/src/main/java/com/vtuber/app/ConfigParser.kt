package com.vtuber.app

import android.content.Context

data class VtuberConfig(
    val scale: Float = 1f,
    val cropTop: Float = 0.55f,
    val idleInterval: Long = 400L,
    val volumeThreshold: Double = 400.0,
    val talkFrames: Int = 2,
    val silenceFrames: Int = 6,
    val anchor: String = "bottom-center",
    val idle1Path: String = "images/idle1.png",
    val idle2Path: String = "images/idle2.png",
    val talkingPath: String = "images/talking.png",
)

object ConfigParser {

    fun load(context: Context): VtuberConfig {
        val html = try {
            context.assets.open("config.html").bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            return VtuberConfig()
        }

        val cfgBlock = findTagBlock(html, "vtuber-config")
        val cfgAttrs: Map<String, String> = if (cfgBlock != null) parseAttrs(cfgBlock) else emptyMap()

        val images = findAllTagBlocks(html, "image").map { parseAttrs(it) }

        fun img(state: String, fallback: String): String {
            val src = images.firstOrNull { it["state"] == state }?.get("src")
            return if (!src.isNullOrBlank()) src else fallback
        }

        return VtuberConfig(
            scale = cfgAttrs["scale"]?.toFloatOrNull() ?: 1f,
            cropTop = cfgAttrs["crop-top"]?.toFloatOrNull() ?: 0.55f,
            idleInterval = cfgAttrs["idle-interval"]?.toLongOrNull() ?: 400L,
            volumeThreshold = cfgAttrs["volume-threshold"]?.toDoubleOrNull() ?: 400.0,
            talkFrames = cfgAttrs["talk-frames"]?.toIntOrNull() ?: 2,
            silenceFrames = cfgAttrs["silence-frames"]?.toIntOrNull() ?: 6,
            anchor = cfgAttrs["anchor"]?.takeIf { it.isNotBlank() } ?: "bottom-center",
            idle1Path = img("idle1", "images/idle1.png"),
            idle2Path = img("idle2", "images/idle2.png"),
            talkingPath = img("talking", "images/talking.png"),
        )
    }

    private fun findTagBlock(html: String, tag: String): String? {
        var i = 0
        while (i < html.length) {
            val start = html.indexOf("<" + tag, i)
            if (start < 0) return null
            val after = start + tag.length + 1
            if (after < html.length) {
                val c = html[after]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>') {
                    val end = html.indexOf('>', after)
                    if (end < 0) return null
                    return html.substring(after, end)
                }
            }
            i = start + 1
        }
        return null
    }

    private fun findAllTagBlocks(html: String, tag: String): List<String> {
        val result = mutableListOf<String>()
        var i = 0
        while (i < html.length) {
            val start = html.indexOf("<" + tag, i)
            if (start < 0) break
            val after = start + tag.length + 1
            if (after < html.length) {
                val c = html[after]
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '/' || c == '>') {
                    val end = html.indexOf('>', after)
                    if (end < 0) break
                    result.add(html.substring(after, end))
                    i = end + 1
                    continue
                }
            }
            i = start + 1
        }
        return result
    }

    private fun parseAttrs(raw: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var i = 0
        val n = raw.length
        while (i < n) {
            while (i < n && raw[i].isWhitespace()) i++
            if (i >= n) break
            val nameStart = i
            while (i < n && (raw[i].isLetterOrDigit() || raw[i] == '_' || raw[i] == '-')) i++
            if (i == nameStart) { i++; continue }
            val name = raw.substring(nameStart, i)
            while (i < n && raw[i].isWhitespace()) i++
            if (i >= n || raw[i] != '=') continue
            i++
            while (i < n && raw[i].isWhitespace()) i++
            if (i >= n) break
            val q = raw[i]
            if (q != '"' && q != '\'') continue
            i++
            val valueStart = i
            while (i < n && raw[i] != q) i++
            val value = raw.substring(valueStart, i)
            if (i < n) i++
            result[name] = value
        }
        return result
    }
}
