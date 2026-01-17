package com.crosscheck.app.models

import java.util.UUID

data class Chat(
    val id: String = UUID.randomUUID().toString(),
    val name: String? = null, // Auto-generated after first query
    val createdAt: Long = System.currentTimeMillis(),
    val lastUpdatedAt: Long = System.currentTimeMillis(),
    val queryCount: Int = 0
) {
    fun withName(newName: String): Chat {
        return copy(name = newName, lastUpdatedAt = System.currentTimeMillis())
    }

    fun incrementQueryCount(): Chat {
        return copy(queryCount = queryCount + 1, lastUpdatedAt = System.currentTimeMillis())
    }

    fun getDisplayName(): String {
        return name ?: "Chat ${id.substring(0, 8)}"
    }
}
