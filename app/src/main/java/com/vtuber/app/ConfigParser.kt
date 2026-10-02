package com.vtuber.app

import android.content.Context

data class VtuberConfig(
    val scale: Float = 1f,
    val cropTop: Float = 0.5f,
    val idleInterval: Long = 400L,
    val volumeThreshold: Double = 1500.0,
    val talkFrames: Int = 3,
    val silenceFrames: Int = 8,
    val anchor: String = "bottom-center",
    val positionX: Float = 0.5f,
    val positionY: Float = 1.0f,
    val idle1Path: String = "images/idle1.png",
    val idle2Path: String = "images/idle2.png",
    val talkingPath: String = "images/talking.png",
)

object ConfigParser {

    private val CONFIG_TAG = Regex(
        "<vtuber-config\\b([^>]*?)/?>",
        RegexOption.IGNORE_CASE
    )
    private val IMAGE_TAG = Regex(
        "<image\\b([^>]*?)/?>",
        RegexOption.IGNORE_CASE
    )
    private val ATTR = Regex(
        "([A-Za-z_][\\w-]*)\\s*=\\s*\"([^\"]*)\""
    )

    fun load(context: Context): VtuberConfig {
        val html = context.assets.open("config.html")
            .bufferedReader().use { it.readText() }

        val cfgAttrs: Map<String, String> = CONFIG_TAG.find(html)
            ?.groupValues?.get(1)
            ?.let { parseAttrs(it) }
            ?: emptyMap()

        val imageAttrs: List<Map<String, String>> = IMAGE_TAG.findAll(html)
            .map { parseAttrs(it.groupValues[1]) }
            .toList()

        fun getImage(state: String, fallback: String): String {
            val src = imageAttrs.firstOrNull { it["state"] == state }?.get("src")
            return if (!src.isNullOrBlank()) src else fallback
        }

        return VtuberConfig(
            scale = cfgAttrs["scale"]?.toFloatOrNull() ?: 1f,
            cropTop = cfgAttrs["crop-top"]?.toFloatOrNull() ?: 0.5f,
            idleInterval = cfgAttrs["idle-interval"]?.toLongOrNull() ?: 400L,
            volumeThreshold = cfgAttrs["volume-threshold"]?.toDoubleOrNull() ?: 1500.0,
            talkFrames = cfgAttrs["talk-frames"]?.toIntOrNull() ?: 3,
            silenceFrames = cfgAttrs["silence-frames"]?.toIntOrNull() ?: 8,
            anchor = cfgAttrs["anchor"]?.takeIf { it.isNotBlank() } ?: "bottom-center",
            positionX = cfgAttrs["position-x"]?.toFloatOrNull() ?: 0.5f,
            positionY = cfgAttrs["position-y"]?.toFloatOrNull() ?: 1.0f,
            idle1Path = getImage("idle1", "images/idle1.png"),
            idle2Path = getImage("idle2", "images/idle2.png"),
            talkingPath = getImage("talking", "images/talking.png"),
        )
    }

    private fun parseAttrs(raw: String): Map<String, String> =
        ATTR.findAll(raw).associate { it.groupValues[1] to it.groupValues[2] }
}
