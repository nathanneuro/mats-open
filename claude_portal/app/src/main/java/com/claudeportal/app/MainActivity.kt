package com.claudeportal.app

import android.content.Intent
import android.os.Bundle
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

    // Thinking animation coroutine
    private var thinkingAnimJob: Job? = null
    private val thinkingSymbols = charArrayOf('\u2736', '\u273B', '\u273D', '\u00B7', '\u2722', '*')
    private var thinkingSymbolIndex = 0

    companion object {
        const val EXTRA_CONNECTION_ID = "connection_id"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        setupTerminalView()
        setupInputBar()
        setupArrowOverlay()
        setupExtraKeys()
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
                // Show info bars and keyboard in live mode
                binding.statusBar.visibility = View.VISIBLE
                binding.thinkingIndicator.visibility = View.VISIBLE
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
        binding.keyTab.setOnClickListener { sshManager.sendKeyPress(KeyCode.TAB) }
        binding.keyEsc.setOnClickListener { sshManager.sendKeyPress(KeyCode.ESCAPE) }
        binding.keyCtrlC.setOnClickListener { sshManager.sendKeyPress(KeyCode.CTRL_C) }
        binding.keyEnter.setOnClickListener { sshManager.sendKeyPress(KeyCode.ENTER) }

        binding.keyTmuxNew.setOnClickListener { sshManager.createTmuxWindow() }
        binding.keyTmuxNext.setOnClickListener { sshManager.nextTmuxWindow() }
        binding.keyTmuxClose.setOnClickListener { sshManager.closeTmuxWindow() }

        binding.scrollBottomFab.setOnClickListener {
            binding.terminalView.scrollToBottom()
            binding.scrollBottomFab.visibility = View.GONE
        }
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
        binding.thinkingSymbol.textSize = settings.thinkingFontSize.toFloat()
        binding.thinkingStatus.textSize = settings.thinkingFontSize.toFloat()
        binding.statusBar.textSize = settings.thinkingFontSize.toFloat()
        currentTmuxFontSize = settings.tmuxFontSize.toFloat()
        binding.arrowOverlay.position = settings.arrowPosition
        binding.arrowOverlay.buttonOpacity = settings.arrowOpacity
        binding.arrowOverlay.vibrateOnPress = settings.vibrateOnKeyPress
        binding.arrowOverlay.visibility = if (settings.showExtraKeys) View.VISIBLE else View.GONE
        binding.extraKeysBar.visibility = if (settings.showExtraKeys) View.VISIBLE else View.GONE

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

        // Consume deduplicated content lines (skip while viewing history)
        lifecycleScope.launch {
            outputProcessor.contentFlow.collectLatest { lines ->
                if (!binding.terminalView.isViewingHistory()) {
                    binding.terminalView.appendLines(lines)
                }
            }
        }

        // Consume plain text for history persistence
        lifecycleScope.launch {
            outputProcessor.plainTextFlow.collectLatest { plainText ->
                historyBuffer.appendPlain(plainText)
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
    }

    private fun updateThinkingIndicator(update: ThinkingUpdate) {
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

        // Route history writes to the active tmux window's file
        val activeWindow = update.windows.getOrNull(update.activeIndex)
        if (activeWindow != null) {
            if (activeWindow.index != lastActiveWindowIndex) {
                // Switched tmux windows — clear scrollback and enter live mode.
                // The old window's history isn't relevant, and replaying
                // the new window's history would overwhelm the UI thread.
                lastActiveWindowIndex = activeWindow.index
                binding.terminalView.clear()
                outputProcessor.resetDiffState()
                binding.terminalView.scrollToBottom()
            }
            historyBuffer.setActiveWindow(activeWindow.index)
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
                setStroke(borderPx, 0xFFD97706.toInt())
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
        }
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
        // Reuse existing history files — don't clear terminal
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

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
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

    override fun onDestroy() {
        super.onDestroy()
        outputProcessor.destroy()
        historyBuffer.close()
        sshManager.destroy()
    }
}
