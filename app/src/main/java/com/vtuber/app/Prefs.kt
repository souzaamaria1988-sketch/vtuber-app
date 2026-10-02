package com.vtuber.app

import android.content.Context

/**
 * Armazena posicao e tamanho como proporcao da tela (0.0 a 1.0).
 * Assim, quando o usuario gira o aparelho (portrait <-> landscape),
 * o sprite se reposiciona e redimensiona proporcionalmente.
 */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vtuber_prefs", Context.MODE_PRIVATE)

    var xRatio: Float
        get() = sp.getFloat("xRatio", -1f)
        set(v) = sp.edit().putFloat("xRatio", v).apply()

    var yRatio: Float
        get() = sp.getFloat("yRatio", -1f)
        set(v) = sp.edit().putFloat("yRatio", v).apply()

    var wRatio: Float
        get() = sp.getFloat("wRatio", 0f)
        set(v) = sp.edit().putFloat("wRatio", v).apply()

    var threshold: Float
        get() = sp.getFloat("threshold", 400f)
        set(v) = sp.edit().putFloat("threshold", v).apply()

    fun hasPosition(): Boolean = xRatio >= 0f && yRatio >= 0f && wRatio > 0f
}
