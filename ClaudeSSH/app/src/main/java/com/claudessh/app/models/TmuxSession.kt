package com.claudessh.app.models

data class TmuxSession(
    val name: String,
    val windows: Int,
    val created: String,
    val attached: Boolean
)
