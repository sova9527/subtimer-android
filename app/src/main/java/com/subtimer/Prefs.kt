package com.subtimer

import android.content.Context

object Prefs {
    private const val NAME = "subtimer"

    fun cdSeconds(c: Context): Int =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("cd", 15).coerceIn(5, 40)

    fun setCdSeconds(c: Context, v: Int) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("cd", v.coerceIn(5, 40)).apply()
    }

    fun sensitivity(c: Context): Int =
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).getInt("sens", 2).coerceIn(0, 4)

    fun setSensitivity(c: Context, v: Int) {
        c.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit().putInt("sens", v.coerceIn(0, 4)).apply()
    }
}
