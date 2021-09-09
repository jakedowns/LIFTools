package com.jakedowns.LIFTools.app.utils

object ExtraTypeCoercion {
    fun Int.toBoolean() = if (this > 0) true else false
    fun Boolean.toInt() = if (this) 1 else 0
}