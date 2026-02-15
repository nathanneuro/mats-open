package com.claudessh.app.models

import java.util.UUID

data class ConnectionProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authMethod: AuthMethod = AuthMethod.KEY,
    val privateKeyPath: String? = null,
    val password: String? = null,
    val autoAttachTmux: Boolean = true,
    val tmuxSessionName: String = "",
    val startCommand: String? = null
)

enum class AuthMethod {
    PASSWORD,
    KEY
}
