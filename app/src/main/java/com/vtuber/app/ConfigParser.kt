package com.vtuber.app

import android.content.Context
import org.jsoup.Jsoup

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
    fun load(context: Context): VtuberConfig {
        val html = context.assets.open("config.html")
            .bufferedReader().use { it.readText() }
        val doc = Jsoup.parse(html, "UTF-8", "")
        val cfg = doc.selectFirst("vtuber-config")
        val imgs = doc.select("vtuber-images > image")

        fun getImage(state: String, fallback: String): String =
            imgs.firstOrNull { it.attr("state") == state }
                ?.attr("src")?.takeIf { it.isNotBlank() } ?: fallback

        return VtuberConfig(
            scale = cfg?.attr("scale")?.toFloatOrNull() ?: 1f,
            cropTop = cfg?.attr("crop-top")?.toFloatOrNull() ?: 0.5f,
            idleInterval = cfg?.attr("idle-interval")?.toLongOrNull() ?: 400L,
            volumeThreshold = cfg?.attr("volume-threshold")?.toDoubleOrNull() ?: 1500.0,
            talkFrames = cfg?.attr("talk-frames")?.toIntOrNull() ?: 3,
            silenceFrames = cfg?.attr("silence-frames")?.toIntOrNull() ?: 8,
            anchor = cfg?.attr("anchor")?.takeIf { it.isNotBlank() } ?: "bottom-center",
            positionX = cfg?.attr("position-x")?.toFloatOrNull() ?: 0.5f,
            positionY = cfg?.attr("position-y")?.toFloatOrNull() ?: 1.0f,
            idle1Path = getImage("idle1", "images/idle1.png"),
            idle2Path = getImage("idle2", "images/idle2.png"),
            talkingPath = getImage("talking", "images/talking.png"),
        )
    }
}
