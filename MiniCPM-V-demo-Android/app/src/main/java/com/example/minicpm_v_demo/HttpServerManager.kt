package com.example.minicpm_v_demo

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception

class HttpServerManager private constructor(private val context: Context) {

    private var serverProcess: Process? = null
    private val handler = Handler(Looper.getMainLooper())

    private val _serverState = MutableStateFlow<ServerState>(ServerState.Stopped)
    val serverState: StateFlow<ServerState> = _serverState

    companion object {
        private const val PREFS_NAME = "http_server_config"
        private const val KEY_PORT = "server_port"
        private const val DEFAULT_PORT = 8080

        @Volatile
        private var instance: HttpServerManager? = null

        fun getInstance(context: Context): HttpServerManager {
            return instance ?: synchronized(this) {
                HttpServerManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getPort(): Int = prefs.getInt(KEY_PORT, DEFAULT_PORT)

    fun setPort(port: Int): Boolean {
        if (port < 1024 || port > 65535) {
            return false
        }
        prefs.edit().putInt(KEY_PORT, port).apply()
        return true
    }

    fun isRunning(): Boolean = serverProcess != null

    fun start(
        modelPath: String,
        mmprojPath: String?,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        if (isRunning()) {
            onError("Server is already running")
            return
        }

        val port = getPort()
        _serverState.value = ServerState.Starting

        Thread {
            try {
                // Build llama-server command (adjust this based on your setup)
                val llamaServerBinary = "llama-server" // Should be in system PATH or provide full path
                val command = mutableListOf(
                    llamaServerBinary,
                    "-m", modelPath,
                    "-p", port.toString(),
                    "-n", "512",
                    "--host", "0.0.0.0"
                )

                if (mmprojPath != null) {
                    command.addAll(listOf("--mmproj", mmprojPath))
                }

                serverProcess = Runtime.getRuntime().exec(command.toTypedArray())

                // Start a thread to read server output
                Thread {
                    val reader = serverProcess!!.inputStream.bufferedReader()
                    reader.forEachLine { line ->
                        android.util.Log.i("HttpServer", line)
                    }
                }.start()

                // Monitor error stream
                Thread {
                    val reader = serverProcess!!.errorStream.bufferedReader()
                    reader.forEachLine { line ->
                        android.util.Log.e("HttpServer", line)
                    }
                }.start()

                handler.post {
                    _serverState.value = ServerState.Running(port)
                    onSuccess()
                }
            } catch (e: Exception) {
                serverProcess = null
                handler.post {
                    _serverState.value = ServerState.Stopped
                    onError("Failed to start server: ${e.message}")
                }
            }
        }.start()
    }

    fun stop(onSuccess: () -> Unit = {}) {
        if (serverProcess == null) {
            onSuccess()
            return
        }

        _serverState.value = ServerState.Stopping
        Thread {
            try {
                serverProcess?.destroy()
                // Wait up to 5 seconds for graceful shutdown
                val finished = serverProcess?.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) ?: true
                if (!finished) {
                    serverProcess?.destroyForcibly()
                }
                serverProcess = null

                handler.post {
                    _serverState.value = ServerState.Stopped
                    onSuccess()
                }
            } catch (e: Exception) {
                serverProcess = null
                handler.post {
                    _serverState.value = ServerState.Stopped
                    onSuccess()
                }
            }
        }.start()
    }

    sealed class ServerState {
        object Stopped : ServerState()
        object Starting : ServerState()
        data class Running(val port: Int) : ServerState()
        object Stopping : ServerState()
        data class Error(val message: String) : ServerState()
    }
}
