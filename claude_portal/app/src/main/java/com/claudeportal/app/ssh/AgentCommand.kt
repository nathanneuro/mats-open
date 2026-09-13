package com.claudeportal.app.ssh

/** A coding agent the portal can launch in a tmux session or window. */
enum class AgentCommand(val command: String, val windowName: String, val label: String) {
    CLAUDE("claude", "claude", "Claude"),
    CODEX("codex", "codex", "Codex")
}
