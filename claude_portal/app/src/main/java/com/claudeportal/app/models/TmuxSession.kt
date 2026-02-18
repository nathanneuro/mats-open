package com.claudeportal.app.models

data class TmuxSession(
    val name: String,
    val windows: Int,
    val created: String,
    val attached: Boolean
)
