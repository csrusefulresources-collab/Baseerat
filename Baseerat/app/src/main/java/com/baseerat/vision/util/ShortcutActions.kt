package com.baseerat.vision.util

import com.baseerat.vision.R

/**
 * Everything a floating-button tap / long-press or a volume-key gesture can be mapped to.
 * Stored as plain strings in settings.
 */
object ShortcutActions {
    const val NONE = "none"
    const val MENU = "menu"
    const val SCREEN = "screen"
    const val FOCUSED = "focused"
    const val READ = "read"
    const val BUTTONS = "buttons"
    const val CAMERA = "camera"
    const val CAMERA_READ = "camera_read"
    const val CHAT = "chat"
    const val REPEAT = "repeat"
    const val STOP = "stop"

    val all: List<String> = listOf(
        NONE, MENU, SCREEN, FOCUSED, READ, BUTTONS, CAMERA, CAMERA_READ, CHAT, REPEAT, STOP
    )

    /** For the floating button tap: "nothing" makes no sense. */
    val withoutNone: List<String> = all.filter { it != NONE }

    fun labelRes(id: String): Int = when (id) {
        MENU -> R.string.action_menu
        SCREEN -> R.string.action_screen
        FOCUSED -> R.string.action_focused
        READ -> R.string.action_read
        BUTTONS -> R.string.action_buttons
        CAMERA -> R.string.action_camera
        CAMERA_READ -> R.string.action_camera_read
        CHAT -> R.string.action_chat
        REPEAT -> R.string.action_repeat
        STOP -> R.string.action_stop
        else -> R.string.action_none
    }
}
