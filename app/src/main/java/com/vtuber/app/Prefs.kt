package com.vtuber.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("vtuber_prefs", Context.MODE_PRIVATE)

    /** JSON com personagens + settings do Studio (salvo pelo SpriteStore). */
    var configJson: String
        get() = sp.getString("configJson", "") ?: ""
        set(v) = sp.edit().putString("configJson", v).apply()
}
