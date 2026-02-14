package com.claudessh.app

import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.claudessh.app.data.ConnectionRepository
import com.claudessh.app.data.HistoryRepository
import com.claudessh.app.data.SettingsRepository
import com.claudessh.app.databinding.ActivityMainBinding
import com.claudessh.app.models.AppSettings
import com.claudessh.app.models.ArrowPosition
import com.claudessh.app.models.ConnectionProfile
import com.claudessh.app.ssh.ConnectionState
import com.claudessh.app.ssh.KeyCode
import com.claudessh.app.ssh.SshManager
import com.claudessh.app.terminal.AnsiParser
import com.claudessh.app.terminal.HistoryBuffer
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val sshManager = SshManager()
    private val ansiParser = AnsiParser()
    private val historyBuffer = HistoryBuffer()
    private val settingsRepo by lazy { SettingsRepository(this) }
    private val connectionRepo by lazy { ConnectionRepository(this) }
    private val historyRepo by lazy { HistoryRepository(this) }

    private var currentSettings = AppSettings()
    private var sessionHistoryFile: File? = null

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
        }
    }

    private fun setupTerminalView() {
        // Terminal view is in the layout - just configure it
        binding.terminalView.setOnClickListener {
            // Tap terminal to show keyboard
            showKeyboard()
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
                        binding.statusText.text = getString(R.string.disconnected)
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
                    }
                    is ConnectionState.Connecting -> {
                        binding.statusText.text = getString(R.string.connecting_to, state.name)
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_connecting)
                    }
                    is ConnectionState.Connected -> {
                        binding.statusText.text = getString(R.string.connected_to, state.name)
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_connected)
                        // Update terminal size now that we're connected
                        updateTerminalSize()
                    }
                    is ConnectionState.Error -> {
                        binding.statusText.text = state.message
                        binding.statusIndicator.setBackgroundResource(R.drawable.status_disconnected)
                        Toast.makeText(this@MainActivity, state.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun observeOutput() {
        lifecycleScope.launch {
            sshManager.outputFlow.collectLatest { rawOutput ->
                // Parse ANSI codes into styled text
                val styled = ansiParser.parse(rawOutput)
                val plain = ansiParser.stripAnsi(rawOutput)

                // Add to history buffer
                historyBuffer.appendStyled(styled)
                historyBuffer.appendPlain(plain)

                // Display in terminal view
                binding.terminalView.appendOutput(styled)

                // Persist to disk if enabled
                if (currentSettings.saveHistoryBetweenSessions) {
                    sessionHistoryFile?.let { file ->
                        historyRepo.appendToSession(file, plain)
                    }
                }

                // Show "scroll to bottom" FAB if user is viewing history
                if (binding.terminalView.isViewingHistory()) {
                    binding.scrollBottomFab.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun sendCurrentInput() {
        val text = binding.inputEditText.text.toString()
        if (text.isNotEmpty() || true) { // Allow sending empty (just Enter)
            sshManager.sendInput(text + "\r")
            binding.inputEditText.text?.clear()
        }
    }

    private fun connectById(connectionId: String) {
        lifecycleScope.launch {
            val profile = connectionRepo.getConnection(connectionId) ?: return@launch
            connectToServer(profile)
        }
    }

    private fun connectToServer(profile: ConnectionProfile) {
        // Create a history file for this session
        sessionHistoryFile = historyRepo.createSessionFile(profile.name)

        lifecycleScope.launch {
            val result = sshManager.connect(profile)
            result.onSuccess {
                if (profile.autoAttachTmux) {
                    sshManager.attachTmuxSession(profile.tmuxSessionName)
                }
            }
            result.onFailure { error ->
                Toast.makeText(
                    this@MainActivity,
                    "Connection failed: ${error.message}",
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
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showSoftInput(binding.inputEditText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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
            R.id.action_tmux_sessions -> {
                startActivity(Intent(this, SessionPickerActivity::class.java))
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
        sshManager.destroy()
    }
}
