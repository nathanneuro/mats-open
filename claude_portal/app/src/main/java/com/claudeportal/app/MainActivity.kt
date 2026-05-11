package com.claudeportal.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudeportal.app.data.ConnectionRepository
import com.claudeportal.app.data.HistoryRepository
import com.claudeportal.app.data.SettingsRepository
import com.claudeportal.app.databinding.ActivityMainBinding
import com.claudeportal.app.models.AppSettings
import com.claudeportal.app.models.ConnectionProfile
import com.claudeportal.app.ssh.ConnectionState
import com.claudeportal.app.ssh.KeyCode
import com.claudeportal.app.ssh.SshConnectionService
import com.claudeportal.app.ssh.SshManager
import com.claudeportal.app.terminal.HistoryBuffer
import com.claudeportal.app.terminal.OutputProcessor
import com.claudeportal.app.terminal.ThinkingUpdate
import com.claudeportal.app.terminal.TmuxBarUpdate
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val sshManager = SshManager()
    private val outputProcessor = OutputProcessor()
    private val historyBuffer = HistoryBuffer()
    private val settingsRepo by lazy { SettingsRepository(this) }
    private val connectionRepo by lazy { ConnectionRepository(this) }
    private val historyRepo by lazy { HistoryRepository(this) }

    private var currentSettings = AppSettings()
    private var currentProfile: ConnectionProfile? = null
    private var currentTmuxFontSize = 12f

    // WakeLock: keeps CPU alive while connected so SSH doesn't die in background
    private var wakeLock: PowerManager.WakeLock? = null
    // Track whether we were connected when going to background for auto-reconnect
    private var wasConnectedOnPause = false

    // Receiver for "Disconnect" action from the foreground service notification.
    // Disconnects SSH and finishes the activity without opening the app.
    private val disconnectReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            sshManager.disconnect()
            finishAndRemoveTask()
        }
    }

    private val quitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            saveInputState()
            sshManager.disconnect()
            stopSshService()
            finishAndRemoveTask()
            // Hard-kill the process so any background coroutines / threads
            // (SSH IO, foreground service handler) actually go away.
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }

    // Command history: stores previously sent commands for recall via up button
    private val commandHistory = mutableListOf<String>()
    private var historyIndex = -1 // -1 = not browsing history
    private var savedInput = "" // saves current input when entering history mode

    // Thinking animation coroutine
    private var thinkingAnimJob: Job? = null
    private val thinkingSymbols = charArrayOf('\u2736', '\u273B', '\u273D', '\u00B7', '\u2722', '*')
    private var thinkingSymbolIndex = 0

    // Broom toggle: when true, the terminalView shows the dirty (raw) history
    // for the active window instead of the deduplicated clean history.
    private var showDirtyHistory: Boolean = false

    // After a "tmux a" click we optimistically switch the active history
    // window to 0. If the server reports tmux isn't running, we roll back
    // to the pre-tmux shell history (window -1). This deadline bounds how
    // long we'll watch for that failure response.
    private var tmuxAttachPendingUntil: Long = 0L
    private val tmuxFailurePatterns = listOf(
        "no server running",
        "no sessions",
        "no current session",
        "error connecting"
    )

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
        const val MAX_HISTORY = 30
        private const val INPUT_PREFS = "input_state"
        private const val KEY_PENDING_INPUT = "pending_input"
        private const val KEY_COMMAND_HISTORY = "command_history"
        // Separator unlikely to appear in commands;  is "start of header".
        private const val HISTORY_SEP = ""
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        registerReceiver(
            disconnectReceiver,
            IntentFilter(SshConnectionService.ACTION_DISCONNECT),
            RECEIVER_NOT_EXPORTED
        )
        registerReceiver(
            quitReceiver,
            IntentFilter(SshConnectionService.ACTION_QUIT_BROADCAST),
            RECEIVER_NOT_EXPORTED
        )

        setupTerminalView()
        setupInputBar()
        restoreInputState()
        setupArrowOverlay()
        setupExtraKeys()
        setupBroomToggle()
        observeSettings()
        observeConnection()
        observeOutput()

        // Check if launched with a specific connection
        val connectionId = intent.getStringExtra(EXTRA_CONNECTION_ID)
        if (connectionId != null) {
            connectById(connectionId)
        } else if (!sshManager.isConnected()) {
            startActivity(Intent(this, ConnectionActivity::class.java))
        }
    }

    private fun setupTerminalView() {
        binding.terminalView.setOnClickListener {
            showKeyboard()
        }
        binding.terminalView.onLoadOlder = { skipFromEndBytes, callback ->
            // Read the next disk chunk off the main thread.
            lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                val chunk = historyBuffer.readWindowCleanChunk(
                    skipFromEndBytes = skipFromEndBytes,
                    chunkBytes = 50_000
                )
                callback(chunk)
            }
        }
        binding.terminalView.onLoadOlderStateChanged = { loading ->
            binding.historyLoadingIndicator.visibility =
                if (loading) View.VISIBLE else View.GONE
        }
        binding.terminalView.onHistoryModeChanged = { viewingHistory ->
            if (viewingHistory) {
                binding.scrollBottomFab.visibility = View.VISIBLE
                binding.scrollBottomFab.backgroundTintList =
                    android.content.res.ColorStateList.valueOf(0xFFFF8800.toInt())
                // Hide info bars and keyboard in history mode
                binding.statusBar.visibility = View.GONE
                binding.thinkingIndicator.visibility = View.GONE
                hideKeyboard()
            } else {
                binding.scrollBottomFab.visibility = View.GONE
                // Show info bars and keyboard in live mode (only on Claude windows)
                if (outputProcessor.isClaudeWindow) {
                    binding.statusBar.visibility = View.VISIBLE
                    binding.thinkingIndicator.visibility = View.VISIBLE
                }
                showKeyboard()
                // Re-scroll after layout settles from bars + keyboard appearing.
                // Two passes: once after bars appear, again after keyboard animation.
                binding.terminalView.postDelayed({
                    binding.terminalView.fullScroll(View.FOCUS_DOWN)
                }, 200)
                binding.terminalView.postDelayed({
                    binding.terminalView.fullScroll(View.FOCUS_DOWN)
                }, 500)
            }
        }
    }

    private fun setupInputBar() {
        binding.inputEditText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendCurrentInput()
                true
            } else false
        }

        binding.inputEditText.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> {
                        sendCurrentInput()
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        sshManager.sendKeyPress(KeyCode.ARROW_UP)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        sshManager.sendKeyPress(KeyCode.ARROW_DOWN)
                        true
                    }
                    KeyEvent.KEYCODE_TAB -> {
                        sshManager.sendKeyPress(KeyCode.TAB)
                        true
                    }
                    KeyEvent.KEYCODE_DEL -> {
                        // Backspace: if input is empty, send to terminal
                        if (binding.inputEditText.text.isNullOrEmpty()) {
                            sshManager.sendInput("\u007F") // DEL character (backspace)
                            true
                        } else false
                    }
                    else -> false
                }
            } else false
        }

        binding.sendButton.setOnClickListener {
            sendCurrentInput()
        }

        // History up button: cycle through previously sent commands
        binding.historyUpButton.setOnClickListener {
            if (commandHistory.isEmpty()) return@setOnClickListener
            if (historyIndex == -1) {
                // Entering history mode — save current input
                savedInput = binding.inputEditText.text.toString()
                historyIndex = commandHistory.size - 1
            } else if (historyIndex > 0) {
                historyIndex--
            }
            val cmd = commandHistory[historyIndex]
            binding.inputEditText.setText(cmd)
            binding.inputEditText.setSelection(cmd.length)
        }

        // Long-press: go forward (back toward most recent / saved input)
        binding.historyUpButton.setOnLongClickListener {
            if (historyIndex == -1) return@setOnLongClickListener false
            if (historyIndex < commandHistory.size - 1) {
                historyIndex++
                val cmd = commandHistory[historyIndex]
                binding.inputEditText.setText(cmd)
                binding.inputEditText.setSelection(cmd.length)
            } else {
                // Past the end — restore saved input
                historyIndex = -1
                binding.inputEditText.setText(savedInput)
                binding.inputEditText.setSelection(savedInput.length)
            }
            true
        }
    }

    private fun setupArrowOverlay() {
        binding.arrowOverlay.onArrowUp = {
            sshManager.sendKeyPress(KeyCode.ARROW_UP)
        }
        binding.arrowOverlay.onArrowDown = {
            sshManager.sendKeyPress(KeyCode.ARROW_DOWN)
        }
    }

    private fun setupExtraKeys() {
        binding.keyTab.setOnClickListener {
            if (shiftHeld) {
                sshManager.sendKeyPress(KeyCode.SHIFT_TAB)
            } else {
                sshManager.sendKeyPress(KeyCode.TAB)
            }
        }
        binding.keyEsc.setOnClickListener { sshManager.sendKeyPress(KeyCode.ESCAPE) }
        binding.keyCtrlC.setOnClickListener { sshManager.sendKeyPress(KeyCode.CTRL_C) }
        binding.keyCtrlB.setOnClickListener { sshManager.sendKeyPress(KeyCode.CTRL_B) }
        binding.keyEnter.setOnClickListener { sshManager.sendKeyPress(KeyCode.ENTER) }

        binding.keyTmuxNew.setOnClickListener {
            historyBuffer.beginPendingSwitch()
            sshManager.createTmuxWindow()
            scheduleTmuxStateQuery()
        }
        binding.keyTmuxNext.setOnClickListener {
            historyBuffer.beginPendingSwitch()
            sshManager.nextTmuxWindow()
            outputProcessor.notifyTmuxWindowNext()
            scheduleTmuxStateQuery()
        }
        binding.keyTmuxSync.setOnClickListener {
            syncTmuxWindow()
        }
        binding.keyTmuxClose.setOnClickListener {
            historyBuffer.beginPendingSwitch()
            sshManager.closeTmuxWindow()
            scheduleTmuxStateQuery()
        }

        binding.scrollBottomFab.setOnClickListener {
            binding.terminalView.scrollToBottom()
            binding.scrollBottomFab.visibility = View.GONE
        }
    }

    /** Line dedup (skeleton-key collapse + generous overlap-merge) is enabled
     *  only when the broom is in clean mode AND the active window is running
     *  Claude Code. Other tmux windows and plain shells render every line.
     *  Pushed to both the live view and the persisted-history ring. */
    private fun applyDedupMode() {
        val on = !showDirtyHistory && outputProcessor.isClaudeWindow
        binding.terminalView.setDedupEnabled(on)
        historyBuffer.dedupEnabled = on
    }

    private fun setupBroomToggle() {
        updateBroomIcon()
        applyDedupMode()
        binding.broomToggle.setOnClickListener {
            val enteringCleanMode = showDirtyHistory  // (about to flip to false)
            showDirtyHistory = !showDirtyHistory
            updateBroomIcon()
            applyDedupMode()
            replayActiveWindow()
            // Going raw → clean: scrub already-rendered duplicate lines
            // out of the loaded history (newest occurrence wins). The
            // per-emit dedup can't catch cross-session repeats persisted
            // on disk; this pass does.
            if (enteringCleanMode) {
                binding.terminalView.cleanupHistory()
                binding.terminalView.scrollToBottom()
            }
        }
    }

    private fun updateBroomIcon() {
        binding.broomToggle.setImageResource(
            if (showDirtyHistory) R.drawable.ic_broom_dirty else R.drawable.ic_broom
        )
    }

    /**
     * Authoritatively re-sync our cached tmux window index against tmux itself.
     * Used as a recovery when our parsed status-bar state has drifted (e.g.
     * the user used Ctrl-b shortcuts directly instead of our buttons, or a
     * tmux bar parse missed a switch). Asks tmux for its real active window
     * via `tmux display-message`, then replays that window's history into
     * the terminal view so the user is looking at the right thing.
     */
    private fun syncTmuxWindow() {
        // Ask tmux directly for the full window list + active flag. This is
        // authoritative — used to recover when the status-bar parser has
        // drifted (truncated names, missed switches, stale active index).
        // Format per line: "<index>:<name>:<flags>"  e.g. "2:claude:*"
        sshManager.execCapture("tmux list-windows -F '#I:#W:#F'") { listResult ->
            val listText = listResult?.trim().orEmpty()
            if (listText.isEmpty()) return@execCapture
            val windows = listText.lines().mapNotNull { line ->
                val parts = line.split(":", limit = 3)
                if (parts.size < 2) return@mapNotNull null
                val idx = parts[0].toIntOrNull() ?: return@mapNotNull null
                val name = parts[1]
                val flags = parts.getOrNull(2).orEmpty()
                com.claudeportal.app.terminal.TmuxWindow(
                    index = idx,
                    name = name,
                    isActive = '*' in flags
                )
            }
            if (windows.isEmpty()) return@execCapture
            val active = windows.firstOrNull { it.isActive } ?: windows.first()

            // Capture the live pane content so we can compare against each
            // saved per-window file and figure out which file is the *real*
            // history of the window we're now looking at. Previous routing
            // bugs may have written current output into a different window's
            // file — replaying from the file that actually matches the live
            // pane is the right repair (without destroying any history).
            sshManager.execCapture("tmux capture-pane -p -S -200") { paneResult ->
                val livePane = paneResult.orEmpty()
                // Match against raw dirty buffers — they preserve the
                // exact lines tmux's capture-pane returns. The clean files
                // are deduped/filtered and lose the fingerprints we need.
                val dirty = historyBuffer.snapshotAllDirtyBuffers()
                val tails = historyBuffer.snapshotAllWindowTails()
                val combined = HashMap<Int, String>()
                for ((i, t) in dirty) combined[i] = t
                for ((i, t) in tails) combined[i] = (combined[i].orEmpty() + "\n" + t)
                val bestIdx = pickBestMatchingHistory(livePane, combined, fallback = active.index)
                android.util.Log.d("MainActivity",
                    "W? authoritativeActive=${active.index} matchedHistory=$bestIdx")
                runOnUiThread {
                    outputProcessor.forceSetWindows(windows)
                    lastActiveWindowIndex = active.index
                    historyBuffer.setActiveWindow(active.index)
                    outputProcessor.resetDiffState()
                    binding.terminalView.clear()
                    val replayText = tails[bestIdx]
                        ?: historyBuffer.readWindowClean(active.index)
                    binding.terminalView.setContent(
                        android.text.SpannableStringBuilder(replayText)
                    )
                    binding.terminalView.scrollToBottom()
                }
            }
        }
    }

    /**
     * Score each window-file tail by how many of the live pane's recent
     * non-blank lines it contains, and return the index of the best match.
     * Falls back to `fallback` when no file scores meaningfully (>=2 line
     * matches), since picking by a single coincidental line would be worse
     * than just trusting the authoritative active-window file.
     */
    private fun pickBestMatchingHistory(
        livePane: String,
        candidates: Map<Int, String>,
        fallback: Int
    ): Int {
        if (candidates.isEmpty()) return fallback
        val signatureLines = livePane.lines()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                t.length >= 10 &&
                    !t.startsWith("[") &&  // tmux status bar
                    !t.matches(Regex("^[\\s─═━\\-=]+$")) &&  // fence/blank
                    !t.matches(Regex("^[❯>$%#]\\s*.{0,3}$"))  // bare prompts
            }
            .takeLast(30)
            .distinct()
        if (signatureLines.size < 3) return fallback
        // Compute weighted score per candidate: each unique-across-candidates
        // matching line counts double (rare lines are stronger evidence).
        val matchCounts = HashMap<String, Int>()
        for (line in signatureLines) {
            val n = candidates.values.count { it.contains(line) }
            matchCounts[line] = n
        }
        var bestIdx = fallback
        var bestScore = 0
        for ((idx, history) in candidates) {
            var score = 0
            for (line in signatureLines) {
                if (history.contains(line)) {
                    val matchedIn = matchCounts[line] ?: 1
                    score += if (matchedIn == 1) 3 else if (matchedIn == 2) 2 else 1
                }
            }
            android.util.Log.d("MainActivity", "W? candidate w=$idx score=$score")
            if (score > bestScore) {
                bestScore = score
                bestIdx = idx
            }
        }
        // Require the best to clearly beat noise — at least 6 weighted points
        // (e.g. 2 unique line matches, or 6 common line matches). Otherwise
        // fall back to whatever tmux says is the active window.
        return if (bestScore >= 6) bestIdx else fallback
    }

    /**
     * Query tmux directly for its authoritative window list and active
     * window, after a short delay to let the server process the keystroke.
     * This is the reliable path: parse-the-status-bar is for the steady
     * state, but right after a switch/new/close the bar may still show the
     * old state. tmux's own answer is ground truth.
     */
    private fun scheduleTmuxStateQuery(delayMs: Long = 250) {
        binding.terminalView.postDelayed({
            sshManager.execCapture("tmux list-windows -F '#I:#W:#F'") { result ->
                val text = result?.trim().orEmpty()
                if (text.isEmpty()) return@execCapture
                val windows = text.lines().mapNotNull { line ->
                    val parts = line.split(":", limit = 3)
                    if (parts.size < 2) return@mapNotNull null
                    val idx = parts[0].toIntOrNull() ?: return@mapNotNull null
                    com.claudeportal.app.terminal.TmuxWindow(
                        index = idx,
                        name = parts[1],
                        isActive = '*' in parts.getOrNull(2).orEmpty()
                    )
                }
                if (windows.isEmpty()) return@execCapture
                val active = windows.firstOrNull { it.isActive } ?: return@execCapture
                runOnUiThread {
                    outputProcessor.forceSetWindows(windows)
                    if (active.index != lastActiveWindowIndex) {
                        lastActiveWindowIndex = active.index
                        historyBuffer.setActiveWindow(active.index)
                        outputProcessor.resetDiffState()
                        binding.terminalView.clear()
                        replayActiveWindow()
                        binding.terminalView.scrollToBottom()
                    } else {
                        // Same active index, but commit any pendingSwitch
                        // that the button press queued so buffered output
                        // lands in the right file.
                        historyBuffer.setActiveWindow(active.index)
                    }
                }
            }
        }, delayMs)
    }

    /**
     * If we're inside the post-"tmux a" watch window, check the incoming
     * raw text for tmux's "no session" failure messages. If found, undo
     * the optimistic switch to window 0 and restore the pre-tmux shell
     * history (window -1) into the terminal view.
     */
    private fun checkTmuxAttachFailure(text: String) {
        if (tmuxAttachPendingUntil == 0L) return
        if (System.currentTimeMillis() > tmuxAttachPendingUntil) {
            tmuxAttachPendingUntil = 0L
            return
        }
        val lower = text.lowercase()
        if (tmuxFailurePatterns.none { lower.contains(it) }) return
        tmuxAttachPendingUntil = 0L
        runOnUiThread {
            historyBuffer.setActiveWindow(-1)
            lastActiveWindowIndex = -1
            outputProcessor.resetDiffState()
            binding.terminalView.clear()
            replayActiveWindow()
            binding.terminalView.scrollToBottom()
            // Re-show the "tmux a" attach button so the user can try again.
            if (!tmuxDetected) showTmuxAttachButton()
        }
    }

    /**
     * Reload the active window's persisted history into the terminal view.
     * Called on tmux window switches (so the user doesn't lose history when
     * bouncing between windows) and when the broom toggle flips between
     * clean and dirty histories.
     */
    private fun replayActiveWindow() {
        // Set the dedup mode for the window we're about to replay first, so the
        // disk-tail seed in readWindowClean() (which re-runs the dedup ring) is
        // consistent with what the live stream will do.
        applyDedupMode()
        val text = if (showDirtyHistory) {
            historyBuffer.readWindowDirty()
        } else {
            historyBuffer.readWindowClean()
        }
        val styled = android.text.SpannableStringBuilder(text)
        binding.terminalView.setContent(styled)
    }

    private fun observeSettings() {
        lifecycleScope.launch {
            settingsRepo.settingsFlow.collectLatest { settings ->
                currentSettings = settings
                applySettings(settings)
            }
        }
    }

    private fun applySettings(settings: AppSettings) {
        binding.terminalView.setFontSize(settings.fontSize.toFloat())
        binding.terminalView.setTableShrinkRatio(settings.graphShrinkPercent / 100f)
        binding.thinkingSymbol.textSize = settings.thinkingFontSize.toFloat()
        binding.thinkingStatus.textSize = settings.thinkingFontSize.toFloat()
        binding.statusBar.textSize = settings.thinkingFontSize.toFloat()
        // Lock the thinking indicator's vertical extent so the cycling
        // animation glyphs (✶ ✻ ✽ · ✢ *), each pulling a different font
        // fallback with its own metrics, can't drag the bar's height up
        // and down between frames. Height = font px * 1.5 + a little
        // padding, computed from the user's thinkingFontSize so the lock
        // tracks any font-size adjustment.
        val thinkingFontPx = settings.thinkingFontSize *
            resources.displayMetrics.scaledDensity
        val thinkingBarHeightPx = (thinkingFontPx * 1.5f).toInt() +
            (8 * resources.displayMetrics.density).toInt()
        binding.thinkingIndicator.layoutParams =
            binding.thinkingIndicator.layoutParams.apply { height = thinkingBarHeightPx }
        // Status bar uses wrap_content so its text always has room. It has
        // no cycling animation, so it doesn't need the wiggle-anchor lock.
        // Locking it to the same height as the thinking bar was clipping
        // the bottom half of its text on some devices.
        binding.statusBar.layoutParams =
            binding.statusBar.layoutParams.apply {
                height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            }
        currentTmuxFontSize = settings.tmuxFontSize.toFloat()
        binding.arrowOverlay.position = settings.arrowPosition
        binding.arrowOverlay.buttonOpacity = settings.arrowOpacity

        // Push the user's emulated terminal width into the screen interpreter.
        // Resize is a no-op when unchanged.
        outputProcessor.resize(settings.emulatedTerminalWidth)

        if (settings.keepScreenOn) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun observeConnection() {
        lifecycleScope.launch {
            sshManager.connectionState.collectLatest { state ->
                when (state) {
                    is ConnectionState.Disconnected -> {
                        stopSshService()
                        releaseWakeLock()
                        tmuxDetected = false
                        binding.tmuxBar.visibility = View.GONE
                        if (currentProfile != null) {
                            binding.statusText.text = getString(R.string.disconnected_tap_reconnect)
                            binding.statusText.setOnClickListener { reconnect() }
                        } else {
                            binding.statusText.text = getString(R.string.disconnected)
                            binding.statusText.setOnClickListener(null)
                        }
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
                    }
                    is ConnectionState.Connecting -> {
                        binding.statusText.text = getString(R.string.connecting_to, state.name)
                        binding.statusText.setOnClickListener(null)
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_connecting)
                    }
                    is ConnectionState.Connected -> {
                        binding.statusText.text = getString(R.string.connected_to, state.name)
                        binding.statusText.setOnClickListener(null)
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_connected)
                        startSshService(state.name)
                        acquireWakeLock()
                        updateTerminalSize()
                        if (!tmuxDetected) showTmuxAttachButton()
                        showKeyboard()
                    }
                    is ConnectionState.Error -> {
                        if (currentProfile != null) {
                            binding.statusText.text = "${state.message} — tap to retry"
                            binding.statusText.setOnClickListener { reconnect() }
                        } else {
                            binding.statusText.text = state.message
                            binding.statusText.setOnClickListener(null)
                        }
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun observeOutput() {
        // Feed raw SSH output to the OutputProcessor on a background thread
        // to avoid blocking the UI during heavy screen interpretation + diffing
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.Default) {
            sshManager.outputFlow.collect { rawOutput ->
                outputProcessor.processRawOutput(rawOutput)
            }
        }

        // Consume deduplicated content lines. Skip rendering when broom is
        // in dirty mode — the raw flow takes over rendering in that case.
        // TerminalView locks scroll position during history mode so new
        // content buffers below the viewport without jumping.
        lifecycleScope.launch {
            outputProcessor.contentFlow.collectLatest { lines ->
                if (!showDirtyHistory) {
                    binding.terminalView.appendLines(lines)
                }
            }
        }

        // Consume raw (un-deduplicated) lines: always feed the dirty history
        // file, and render to terminalView only when the broom toggle is on.
        lifecycleScope.launch {
            outputProcessor.rawContentFlow.collectLatest { lines ->
                if (showDirtyHistory) {
                    binding.terminalView.appendLines(lines)
                }
            }
        }

        // Consume plain text for clean history persistence
        lifecycleScope.launch {
            outputProcessor.plainTextFlow.collectLatest { plainText ->
                historyBuffer.appendPlain(plainText)
            }
        }

        // Consume raw plain text for dirty history persistence
        lifecycleScope.launch {
            outputProcessor.rawPlainTextFlow.collectLatest { plainText ->
                historyBuffer.appendDirtyPlain(plainText)
                checkTmuxAttachFailure(plainText)
            }
        }

        // Consume thinking state
        lifecycleScope.launch {
            outputProcessor.thinkingFlow.collectLatest { update ->
                updateThinkingIndicator(update)
            }
        }

        // Consume tmux bar updates
        lifecycleScope.launch {
            outputProcessor.tmuxBarFlow.collectLatest { update ->
                updateTmuxBar(update)
            }
        }

        // Consume Claude Code status bar
        lifecycleScope.launch {
            outputProcessor.statusBarFlow.collectLatest { status ->
                updateStatusBar(status)
            }
        }

        // Line dedup runs only for Claude-Code windows — flip it whenever the
        // active window's Claude-ness changes (e.g. claude launched/exited
        // inside a generically-named window, detected from screen content).
        lifecycleScope.launch {
            outputProcessor.claudeWindowFlow.collectLatest {
                applyDedupMode()
            }
        }

        // Consume `clear` invocations from non-Claude windows. ESC[3J in
        // those windows is a reliable user-typed-`clear` signal (tmux
        // pane redraws don't emit it) — wipe both the on-screen text and
        // the per-window history file so scrolling up shows nothing,
        // matching bash's `clear` semantics.
        lifecycleScope.launch {
            outputProcessor.clearScrollbackFlow.collect {
                binding.terminalView.clear()
                historyBuffer.resetActiveWindow()
            }
        }
    }

    private fun updateThinkingIndicator(update: ThinkingUpdate) {
        if (!outputProcessor.isClaudeWindow) {
            binding.thinkingIndicator.visibility = View.GONE
            stopThinkingAnimation()
            return
        }
        binding.thinkingIndicator.visibility = View.VISIBLE
        if (update.isThinking) {
            binding.thinkingSymbol.visibility = View.VISIBLE
            binding.thinkingStatus.visibility = View.VISIBLE
            binding.thinkingStatus.text = update.statusText ?: getString(R.string.thinking)
            startThinkingAnimation()
        } else {
            binding.thinkingSymbol.visibility = View.INVISIBLE
            binding.thinkingStatus.visibility = View.INVISIBLE
            stopThinkingAnimation()
        }
    }

    private fun startThinkingAnimation() {
        if (thinkingAnimJob?.isActive == true) return
        thinkingSymbolIndex = 0
        thinkingAnimJob = lifecycleScope.launch {
            while (isActive) {
                binding.thinkingSymbol.text = thinkingSymbols[thinkingSymbolIndex].toString()
                thinkingSymbolIndex = (thinkingSymbolIndex + 1) % thinkingSymbols.size
                delay(150)
            }
        }
    }

    private fun stopThinkingAnimation() {
        thinkingAnimJob?.cancel()
        thinkingAnimJob = null
    }

    private fun updateStatusBar(status: String?) {
        if (!outputProcessor.isClaudeWindow) {
            binding.statusBar.visibility = View.GONE
            return
        }
        binding.statusBar.visibility = View.VISIBLE
        binding.statusBar.text = status ?: ""
    }

    private var tmuxDetected = false
    private var lastActiveWindowIndex = -1

    private fun showTmuxAttachButton() {
        binding.tmuxBar.visibility = View.VISIBLE
        val container = binding.tmuxTabs
        container.removeAllViews()

        val btn = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
            text = "tmux a"
            textSize = currentTmuxFontSize
            minWidth = 0
            minimumWidth = 0
            minHeight = 0
            minimumHeight = 0
            setPadding(12, 2, 12, 2)
            insetTop = 0
            insetBottom = 0
            setBackgroundColor(0xFF3D3D5C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            cornerRadius = 12
            strokeWidth = 0
            setOnClickListener {
                // Switching from non-tmux shell into tmux: route history
                // immediately to a tmux-side file. Trust the user's button
                // press over the server's status-bar parse — the parse may
                // arrive late or be wrong, but the click is ground truth
                // that we are now in tmux. Default to window 0; the bar
                // parse will correct to the real active index shortly.
                historyBuffer.setActiveWindow(0)
                lastActiveWindowIndex = 0
                tmuxAttachPendingUntil = System.currentTimeMillis() + 3000
                // Hard-wipe the virtual screen too, not just the diff
                // snapshot. Otherwise the first post-tmux diff (which has
                // no prev snapshot) emits every non-blank row of the
                // shell screen as "new", leaking shell content into w0.
                outputProcessor.resetScreenState()
                binding.terminalView.clear()
                // Show the previous session's saved history for w0 right
                // away. If we let the tmux-bar parse drive the replay,
                // the "switched" check (lastActiveWindowIndex changed)
                // wouldn't trigger — we already set it to 0 above — and
                // the saved history would never appear.
                replayActiveWindow()
                binding.terminalView.scrollToBottom()
                sshManager.sendInput("tmux a\r")
            }
        }
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        container.addView(btn, lp)
    }

    private fun updateTmuxBar(update: TmuxBarUpdate?) {
        if (update == null || update.windows.isEmpty()) {
            if (!tmuxDetected && sshManager.isConnected()) {
                showTmuxAttachButton()
            } else if (!sshManager.isConnected()) {
                binding.tmuxBar.visibility = View.GONE
            }
            return
        }

        tmuxDetected = true
        // Real tmux bar arrived — attach succeeded, no rollback needed.
        tmuxAttachPendingUntil = 0L

        // Route history writes to the active tmux window's file. If the index
        // changed, commit any pending switch (flushes buffered output to the
        // new window's files), then replay that window's persisted history
        // into the terminal view so the user doesn't lose context.
        val activeWindow = update.windows.getOrNull(update.activeIndex)
        if (activeWindow != null) {
            val switched = activeWindow.index != lastActiveWindowIndex
            // Match dedup to the (possibly newly-active) window's Claude-ness
            // before setActiveWindow flushes any pending-switch output into it.
            applyDedupMode()
            historyBuffer.setActiveWindow(activeWindow.index)
            if (switched) {
                lastActiveWindowIndex = activeWindow.index
                outputProcessor.resetDiffState()
                binding.terminalView.clear()
                replayActiveWindow()
                binding.terminalView.scrollToBottom()
            }
        }

        // Show/hide Claude window border based on active tmux window name
        updateClaudeBorder(outputProcessor.isClaudeWindow)

        binding.tmuxBar.visibility = View.VISIBLE
        val container = binding.tmuxTabs
        container.removeAllViews()

        for (window in update.windows) {
            val tab = MaterialButton(this, null, android.R.attr.borderlessButtonStyle).apply {
                text = "${window.index}:${window.name}"
                textSize = currentTmuxFontSize
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(12, 2, 12, 2)
                insetTop = 0
                insetBottom = 0
                cornerRadius = 12
                strokeWidth = 0

                isClickable = false

                if (window.isActive) {
                    setBackgroundColor(0xFF3D3D5C.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                } else {
                    setBackgroundColor(0x00000000)
                    setTextColor(0xFF777777.toInt())
                }
            }

            val lp = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 4
            }
            container.addView(tab, lp)
        }
    }

    private var claudeBorderShowing = false

    private fun updateClaudeBorder(isClaude: Boolean) {
        if (isClaude == claudeBorderShowing) return
        claudeBorderShowing = isClaude
        if (isClaude) {
            val borderPx = (2 * resources.displayMetrics.density).toInt()
            binding.claudeContainer.foreground = android.graphics.drawable.GradientDrawable().apply {
                setStroke(borderPx, 0xFF9E5A28.toInt())
                setColor(0x00000000)
            }
        } else {
            binding.claudeContainer.foreground = null
        }
        // Hide Claude-specific UI bars when not in a Claude window
        if (isClaude) {
            binding.statusBar.visibility = View.VISIBLE
            binding.thinkingIndicator.visibility = View.VISIBLE
        } else {
            binding.statusBar.visibility = View.GONE
            binding.thinkingIndicator.visibility = View.GONE
            stopThinkingAnimation()
        }
    }

    private fun sendCurrentInput() {
        val text = binding.inputEditText.text.toString()
        if (text.isNotEmpty()) {
            sshManager.sendInput(text)
            // Record in command history (skip duplicates of the last entry)
            if (commandHistory.isEmpty() || commandHistory.last() != text) {
                commandHistory.add(text)
                if (commandHistory.size > MAX_HISTORY) commandHistory.removeAt(0)
            }
        }
        // Reset history browsing state
        historyIndex = -1
        savedInput = ""
        // Send Enter as a separate write so TUI doesn't merge it with text
        binding.terminalView.postDelayed({
            sshManager.sendInput("\r")
        }, 50)
        binding.inputEditText.text?.clear()
    }

    private fun connectById(connectionId: String) {
        lifecycleScope.launch {
            val profile = connectionRepo.getConnection(connectionId) ?: return@launch
            connectToServer(profile)
        }
    }

    private fun connectToServer(profile: ConnectionProfile) {
        currentProfile = profile
        // Set up per-window history files (appends if reconnecting to same server)
        historyBuffer.setConnection(historyRepo.historyDir, profile.name)

        lifecycleScope.launch {
            val result = sshManager.connect(profile, filesDir)
            result.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "Connection failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun reconnect() {
        val profile = currentProfile ?: return
        // Reset to fresh terminal state — clear tmux detection, terminal, and caches
        tmuxDetected = false
        lastActiveWindowIndex = -1
        binding.tmuxBar.visibility = View.GONE
        binding.terminalView.clear()
        outputProcessor.resetAllState()
        claudeBorderShowing = false
        binding.claudeContainer.foreground = null
        binding.statusBar.visibility = View.GONE
        binding.thinkingIndicator.visibility = View.GONE

        lifecycleScope.launch {
            val result = sshManager.connect(profile, filesDir)
            result.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "Reconnect failed: ${error.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun updateTerminalSize() {
        binding.terminalView.post {
            val cols = binding.terminalView.calculateColumns()
            val rows = binding.terminalView.calculateRows()
            if (cols > 0 && rows > 0) {
                sshManager.resizeTerminal(cols, rows)
            }
        }
    }

    private fun showKeyboard() {
        binding.inputEditText.requestFocus()
        binding.inputEditText.post {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            imm.showSoftInput(binding.inputEditText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(binding.inputEditText.windowToken, 0)
        binding.inputEditText.clearFocus()
    }

    // Track shift state from keyboard events for modifier+button combos (e.g. shift+TAB)
    private var shiftHeld = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Track shift key state
        if (event.keyCode == KeyEvent.KEYCODE_SHIFT_LEFT || event.keyCode == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            shiftHeld = event.action == KeyEvent.ACTION_DOWN
        }
        // Also track via meta state (covers soft keyboards that report it)
        if (event.action == KeyEvent.ACTION_DOWN || event.action == KeyEvent.ACTION_UP) {
            shiftHeld = (event.metaState and KeyEvent.META_SHIFT_ON) != 0
        }

        // Route all key events to the input field so typing always goes there
        if (event.action == KeyEvent.ACTION_DOWN && !binding.inputEditText.hasFocus()) {
            val keyCode = event.keyCode
            // Don't steal system keys, menu, or back
            if (keyCode != KeyEvent.KEYCODE_BACK &&
                keyCode != KeyEvent.KEYCODE_MENU &&
                keyCode != KeyEvent.KEYCODE_HOME &&
                keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
                keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
                binding.inputEditText.requestFocus()
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_connections -> {
                startActivity(Intent(this, ConnectionActivity::class.java))
                true
            }
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_instructions -> {
                startActivity(Intent(this, InstructionsActivity::class.java))
                true
            }
            R.id.action_disconnect -> {
                sshManager.disconnect()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        // If we were connected before going to background but the connection died,
        // auto-reconnect. This handles the phone being locked/app backgrounded.
        if (wasConnectedOnPause && !sshManager.isConnected() && currentProfile != null) {
            reconnect()
        }
    }

    override fun onPause() {
        super.onPause()
        wasConnectedOnPause = sshManager.isConnected()
        saveInputState()
    }

    private fun restoreInputState() {
        val prefs = getSharedPreferences(INPUT_PREFS, MODE_PRIVATE)
        val pending = prefs.getString(KEY_PENDING_INPUT, "") ?: ""
        if (pending.isNotEmpty()) {
            binding.inputEditText.setText(pending)
            binding.inputEditText.setSelection(pending.length)
        }
        val historyStr = prefs.getString(KEY_COMMAND_HISTORY, "") ?: ""
        if (historyStr.isNotEmpty()) {
            commandHistory.clear()
            commandHistory.addAll(historyStr.split(HISTORY_SEP).filter { it.isNotEmpty() })
            while (commandHistory.size > MAX_HISTORY) commandHistory.removeAt(0)
        }
    }

    private fun saveInputState() {
        val prefs = getSharedPreferences(INPUT_PREFS, MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PENDING_INPUT, binding.inputEditText.text?.toString().orEmpty())
            .putString(KEY_COMMAND_HISTORY, commandHistory.joinToString(HISTORY_SEP))
            .apply()
    }

    /**
     * Start the foreground service to keep the process alive in background.
     * Without this, Android will kill the process when the app is backgrounded,
     * which drops the SSH connection. The foreground service shows a persistent
     * notification ("SSH Connected") that keeps the process protected.
     */
    private fun startSshService(serverName: String) {
        val intent = Intent(this, SshConnectionService::class.java).apply {
            putExtra("server_name", serverName)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopSshService() {
        val intent = Intent(this, SshConnectionService::class.java).apply {
            action = SshConnectionService.ACTION_STOP
        }
        startService(intent)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "ClaudePortal::SshConnection"
        ).apply {
            // Auto-release after 4 hours to prevent battery drain if user forgets
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
        }
        wakeLock = null
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(disconnectReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(quitReceiver) } catch (_: Exception) {}
        stopSshService()
        releaseWakeLock()
        outputProcessor.destroy()
        historyBuffer.close()
        sshManager.destroy()
    }
}
