package com.claudessh.app.ssh

import com.claudessh.app.models.AuthMethod
import com.claudessh.app.models.ConnectionProfile
import com.claudessh.app.models.TmuxSession
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

class SshManager {

    private var session: Session? = null
    private var channel: ChannelShell? = null
    private var outputStream: OutputStream? = null
    private var inputStream: InputStream? = null
    private var readJob: Job? = null

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _outputFlow = MutableSharedFlow<String>(extraBufferCapacity = 1024)
    val outputFlow: SharedFlow<String> = _outputFlow

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect(profile: ConnectionProfile): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.Connecting(profile.name)

            val jsch = JSch()

            if (profile.authMethod == AuthMethod.KEY && profile.privateKeyPath != null) {
                jsch.addIdentity(profile.privateKeyPath)
            }

            val sshSession = jsch.getSession(profile.username, profile.host, profile.port)

            if (profile.authMethod == AuthMethod.PASSWORD && profile.password != null) {
                sshSession.setPassword(profile.password)
            }

            val config = Properties()
            config["StrictHostKeyChecking"] = "no"
            sshSession.setConfig(config)
            sshSession.timeout = 15000

            sshSession.connect()
            session = sshSession

            val shell = sshSession.openChannel("shell") as ChannelShell
            shell.setPtyType("xterm-256color")
            shell.setPtySize(80, 24, 640, 480)

            inputStream = shell.inputStream
            outputStream = shell.outputStream

            shell.connect()
            channel = shell

            _connectionState.value = ConnectionState.Connected(profile.name)

            startReadLoop()

            Result.success(Unit)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    private fun startReadLoop() {
        readJob = scope.launch {
            val buffer = ByteArray(8192)
            try {
                while (isActive && channel?.isConnected == true) {
                    val stream = inputStream ?: break
                    val available = stream.available()
                    if (available > 0) {
                        val bytesRead = stream.read(buffer, 0, minOf(available, buffer.size))
                        if (bytesRead > 0) {
                            val text = String(buffer, 0, bytesRead)
                            _outputFlow.emit(text)
                        }
                    } else {
                        delay(10)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    _connectionState.value = ConnectionState.Error("Connection lost: ${e.message}")
                }
            }
        }
    }

    fun sendInput(text: String) {
        scope.launch {
            try {
                outputStream?.write(text.toByteArray())
                outputStream?.flush()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error("Send failed: ${e.message}")
            }
        }
    }

    fun sendKeyPress(keyCode: KeyCode) {
        val escape = when (keyCode) {
            KeyCode.ARROW_UP -> "\u001b[A"
            KeyCode.ARROW_DOWN -> "\u001b[B"
            KeyCode.ARROW_LEFT -> "\u001b[D"
            KeyCode.ARROW_RIGHT -> "\u001b[C"
            KeyCode.TAB -> "\t"
            KeyCode.ESCAPE -> "\u001b"
            KeyCode.ENTER -> "\r"
            KeyCode.CTRL_C -> "\u0003"
            KeyCode.CTRL_D -> "\u0004"
            KeyCode.CTRL_Z -> "\u001a"
            KeyCode.CTRL_L -> "\u000c"
            KeyCode.CTRL_A -> "\u0001"
            KeyCode.CTRL_E -> "\u0005"
        }
        sendInput(escape)
    }

    fun resizeTerminal(cols: Int, rows: Int) {
        channel?.setPtySize(cols, rows, cols * 8, rows * 16)
    }

    suspend fun listTmuxSessions(): Result<List<TmuxSession>> = withContext(Dispatchers.IO) {
        try {
            val execChannel = session?.openChannel("exec") as? ChannelExec
                ?: return@withContext Result.failure(Exception("Not connected"))

            execChannel.setCommand("tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_created}|#{session_attached}' 2>/dev/null")
            execChannel.inputStream = null

            val resultStream = execChannel.inputStream
            execChannel.connect()

            val output = resultStream.bufferedReader().readText()
            execChannel.disconnect()

            val sessions = output.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size >= 4) {
                        TmuxSession(
                            name = parts[0],
                            windows = parts[1].toIntOrNull() ?: 1,
                            created = parts[2],
                            attached = parts[3] == "1"
                        )
                    } else null
                }

            Result.success(sessions)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun attachTmuxSession(sessionName: String) {
        sendInput("tmux attach-session -t $sessionName || tmux new-session -s $sessionName\r")
    }

    fun createTmuxSessionWithClaude(sessionName: String) {
        sendInput("tmux new-session -d -s $sessionName 'claude' && tmux attach-session -t $sessionName\r")
    }

    fun disconnect() {
        readJob?.cancel()
        try {
            channel?.disconnect()
            session?.disconnect()
        } catch (_: Exception) {}
        channel = null
        session = null
        outputStream = null
        inputStream = null
        _connectionState.value = ConnectionState.Disconnected
    }

    fun isConnected(): Boolean {
        return session?.isConnected == true && channel?.isConnected == true
    }

    fun destroy() {
        disconnect()
        scope.cancel()
    }
}

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data class Connecting(val name: String) : ConnectionState()
    data class Connected(val name: String) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

enum class KeyCode {
    ARROW_UP, ARROW_DOWN, ARROW_LEFT, ARROW_RIGHT,
    TAB, ESCAPE, ENTER,
    CTRL_C, CTRL_D, CTRL_Z, CTRL_L, CTRL_A, CTRL_E
}
