package com.claudeportal.app.terminal

/**
 * Which coding agent (if any) is running in the active tmux window.
 *
 * The portal's content-filtering heuristics are agent-specific: they strip
 * the agent's in-place-redrawn UI chrome (status area, spinner row, composer)
 * out of the scrollable transcript. Applying one agent's heuristics to a
 * plain shell — or to the other agent — deletes real content, so every
 * filter is gated on the detected kind.
 */
enum class AgentKind {
    NONE,
    CLAUDE,
    CODEX;

    val isAgent: Boolean get() = this != NONE

    companion object {
        /** Detection from the tmux window name. Claude Code's binary is
         *  `claude`, so tmux usually names the window "claude". Codex runs
         *  under `node`, so the name only matches when the user renamed the
         *  window — content detection carries most of the load there. */
        fun fromWindowName(name: String): AgentKind = when {
            name.contains("claude", ignoreCase = true) -> CLAUDE
            name.contains("codex", ignoreCase = true) -> CODEX
            else -> NONE
        }
    }
}
