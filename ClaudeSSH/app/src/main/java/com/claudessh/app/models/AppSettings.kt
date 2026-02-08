package com.claudessh.app.models

data class AppSettings(
    val arrowPosition: ArrowPosition = ArrowPosition.RIGHT,
    val arrowOpacity: Float = 0.4f,
    val fontSize: Int = 14,
    val keepScreenOn: Boolean = true,
    val maxHistoryLines: Int = 50000,
    val saveHistoryBetweenSessions: Boolean = true,
    val defaultConnectionId: String? = null,
    val showExtraKeys: Boolean = true,
    val vibrateOnKeyPress: Boolean = false
)

enum class ArrowPosition {
    LEFT,
    RIGHT
}
