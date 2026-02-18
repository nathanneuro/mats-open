package com.claudeportal.app.ssh

import com.claudeportal.app.models.AuthMethod
import com.claudeportal.app.models.ConnectionProfile
import com.claudeportal.app.models.TmuxSession
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session as SshjSession
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import android.util.Log
import java.io.File
import java.io.OutputStream
import java.security.Security

class SshManager {

    private var client: SSHClient? = null
    private var session: SshjSession? = null
    private var shell: SshjSession.Shell? = null
    private var outputStream: OutputStream? = null
    private var readerThread: Thread? = null
    @Volatile private var shellReady = false

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _outputFlow = MutableSharedFlow<String>(extraBufferCapacity = 1024)
    val outputFlow: SharedFlow<String> = _outputFlow

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    suspend fun connect(profile: ConnectionProfile, filesDir: File? = null): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            _connectionState.value = ConnectionState.Connecting(profile.name)

            // Replace Android's crippled BC with full BouncyCastle for ed25519 support
            Security.removeProvider("BC")
            try {
                val provider = Class.forName("org.bouncycastle.jce.provider.BouncyCastleProvider")
                    .getDeclaredConstructor().newInstance() as java.security.Provider
                Security.insertProviderAt(provider, 1)
                Log.d("SshManager", "Full BouncyCastle provider registered at position 1")
            } catch (e: Exception) {
                Log.w("SshManager", "BouncyCastle provider not available: ${e.message}")
            }

            val ssh = SSHClient()
            ssh.addHostKeyVerifier(PromiscuousVerifier())
            ssh.connection.keepAlive.keepAliveInterval = 15
            ssh.connectTimeout = 15000
            ssh.timeout = 15000

            Log.d("SshManager", "Connecting to ${profile.host}:${profile.port}")
            ssh.connect(profile.host, profile.port)
            Log.d("SshManager", "TCP connected, authenticating as ${profile.username}")

            if (profile.authMethod == AuthMethod.KEY && profile.privateKeyPath != null) {
                val keyFile = if (filesDir != null) {
                    File(File(filesDir, "keys"), profile.privateKeyPath)
                } else {
                    File(profile.privateKeyPath)
                }
                ssh.authPublickey(profile.username, keyFile.absolutePath)
            } else if (profile.authMethod == AuthMethod.PASSWORD && profile.password != null) {
                ssh.authPassword(profile.username, profile.password)
            }
            Log.d("SshManager", "Authenticated")

            client = ssh

            val sess = ssh.startSession()
            sess.allocatePTY("xterm-256color", 80, 24, 0, 0, emptyMap())
            val sh = sess.startShell()
            session = sess
            shell = sh
            outputStream = sh.outputStream

            // Reader thread: read from shell's inputStream and emit to outputFlow
            readerThread = Thread({
                val buf = ByteArray(8192)
                val input = sh.inputStream
                try {
                    while (!Thread.currentThread().isInterrupted) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (!shellReady) {
                            shellReady = true
                            Log.d("SshManager", "Shell ready (first output received)")
                        }
                        val text = String(buf, 0, n)
                        val preview = text.take(80).replace("\n", "\\n").replace("\r", "\\r")
                        Log.d("SshManager", "Received $n bytes: $preview")
                        _outputFlow.tryEmit(text)
                    }
                } catch (e: Exception) {
                    if (!Thread.currentThread().isInterrupted) {
                        Log.e("SshManager", "Reader error: ${e.message}")
                    }
                }
                Log.d("SshManager", "Reader thread exiting")
                if (_connectionState.value is ConnectionState.Connected) {
                    _connectionState.value = ConnectionState.Disconnected
                }
            }, "ssh-reader")
            readerThread!!.isDaemon = true
            readerThread!!.start()

            _connectionState.value = ConnectionState.Connected(profile.name)
            Log.d("SshManager", "Shell started, connected")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e("SshManager", "Connect failed: ${e.message}", e)
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            Result.failure(e)
        }
    }

    fun sendInput(text: String) {
        val out = outputStream
        Log.d("SshManager", "sendInput: '${text.replace("\r", "\\r")}' stream=${out?.javaClass?.name}")
        if (out == null) {
            _connectionState.value = ConnectionState.Error("Send failed: not connected")
            return
        }
        Thread {
            try {
                val bytes = text.toByteArray()
                out.write(bytes)
                out.flush()
                Log.d("SshManager", "Sent ${bytes.size} bytes")
            } catch (e: Exception) {
                Log.e("SshManager", "Send failed: ${e.javaClass.simpleName}: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("Send failed: ${e.message}")
            }
        }.start()
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
        // Intentionally not sending resize to the server.
        // We keep the default 80x24 PTY so programs output full-width text,
        // and the TerminalView wraps it locally for the phone screen.
    }

    suspend fun listTmuxSessions(): Result<List<TmuxSession>> = withContext(Dispatchers.IO) {
        try {
            val ssh = client ?: return@withContext Result.failure(Exception("Not connected"))

            val execSession = ssh.startSession()
            val cmd = execSession.exec("tmux list-sessions -F '#{session_name}|#{session_windows}|#{session_created}|#{session_attached}' 2>/dev/null")
            val output = cmd.inputStream.bufferedReader().readText()
            cmd.join()
            execSession.close()

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

    fun createTmuxWindow() {
        execTmuxCommand("tmux new-window")
    }

    fun nextTmuxWindow() {
        execTmuxCommand("tmux next-window")
    }

    fun closeTmuxWindow() {
        execTmuxCommand("tmux if-shell '[ \$(tmux list-windows | wc -l) -gt 1 ]' kill-window")
    }

    fun selectTmuxWindow(index: Int) {
        execTmuxCommand("tmux select-window -t $index")
    }

    fun createTmuxSessionWithClaude(sessionName: String) {
        execTmuxCommand("tmux new-session -d -s $sessionName 'claude' && tmux attach-session -t $sessionName")
    }

    private fun execTmuxCommand(command: String) {
        Thread {
            try {
                val ssh = client ?: return@Thread
                val execSession = ssh.startSession()
                val cmd = execSession.exec(command)
                cmd.join()
                execSession.close()
                Log.d("SshManager", "Exec: $command")
            } catch (e: Exception) {
                Log.w("SshManager", "Exec failed ($command): ${e.message}")
            }
        }.start()
    }

    fun attachTmuxSession(sessionName: String) {
        Thread {
            Thread.sleep(500)
            if (sessionName.isBlank()) {
                sendInput("tmux new-session -A\r")
            } else {
                sendInput("tmux new-session -A -s $sessionName\r")
            }
        }.start()
    }

    fun disconnect() {
        readerThread?.interrupt()
        readerThread = null
        try {
            shell?.close()
            session?.close()
            client?.disconnect()
        } catch (_: Exception) {}
        shell = null
        session = null
        client = null
        outputStream = null
        shellReady = false
        _connectionState.value = ConnectionState.Disconnected
    }

    fun isConnected(): Boolean {
        return client?.isConnected == true
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
