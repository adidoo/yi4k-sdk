package com.adidoo.yi4k.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Raw TCP transport for the Yi camera's JSON control protocol. Owns the socket, serializes
 * outgoing commands, and streams every parsed JSON object (both direct replies and
 * unsolicited status pushes) through [messages].
 */
class YiCameraClient {
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var readerJob: Job? = null
    private var socket: Socket? = null
    private var output: OutputStream? = null

    private val sendMutex = Mutex()
    private val _messages = MutableSharedFlow<JSONObject>(extraBufferCapacity = 64)
    val messages: SharedFlow<JSONObject> = _messages

    val isConnected: Boolean
        get() = socket?.isConnected == true && socket?.isClosed == false

    suspend fun connect(
        host: String = YiProtocol.HOST,
        port: Int = YiProtocol.PORT,
        timeoutMs: Int = 5_000,
    ) = withContext(Dispatchers.IO) {
        disconnect()
        val newSocket = Socket()
        newSocket.connect(InetSocketAddress(host, port), timeoutMs)
        socket = newSocket
        output = newSocket.getOutputStream()
        readerJob = ioScope.launch { readLoop(newSocket) }
    }

    fun disconnect() {
        readerJob?.cancel()
        readerJob = null
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        output = null
    }

    /**
     * Sends [payload] and suspends until a message matching [predicate] arrives (this may be
     * the direct reply, or - for commands like start/stop record - a later async status push).
     * Only one command is ever in flight at a time so the predicate can't race a reply to a
     * different, concurrently-sent command.
     */
    suspend fun sendCommand(
        payload: JSONObject,
        timeoutMs: Long = 10_000,
        predicate: (JSONObject) -> Boolean,
    ): JSONObject = sendMutex.withLock {
        val reply = CompletableDeferred<JSONObject>()
        val subscribed = CompletableDeferred<Unit>()
        val collectJob = ioScope.launch {
            _messages
                .onSubscription { subscribed.complete(Unit) }
                .collect { msg -> if (!reply.isCompleted && predicate(msg)) reply.complete(msg) }
        }
        try {
            subscribed.await()
            writeRaw(payload.toString())
            withTimeout(timeoutMs) { reply.await() }
        } finally {
            collectJob.cancel()
        }
    }

    private suspend fun writeRaw(text: String) = withContext(Dispatchers.IO) {
        val stream = output ?: throw IOException("Not connected to camera")
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.flush()
    }

    private suspend fun readLoop(activeSocket: Socket) {
        val reader = BufferedReader(InputStreamReader(activeSocket.getInputStream(), Charsets.UTF_8))
        val buffer = StringBuilder()
        var braceDepth = 0
        var inString = false
        var escapeNext = false

        try {
            while (ioScope.isActive) {
                val chunk = CharArray(1024)
                val read = reader.read(chunk)
                if (read < 0) break

                for (i in 0 until read) {
                    val c = chunk[i]
                    if (braceDepth == 0 && c.isWhitespace()) continue
                    buffer.append(c)

                    if (escapeNext) {
                        escapeNext = false
                        continue
                    }
                    when {
                        inString && c == '\\' -> escapeNext = true
                        c == '"' -> inString = !inString
                        !inString && c == '{' -> braceDepth++
                        !inString && c == '}' -> {
                            braceDepth--
                            if (braceDepth == 0) {
                                emitObject(buffer.toString())
                                buffer.setLength(0)
                            }
                        }
                    }
                }
            }
        } catch (_: IOException) {
            // Socket closed, either by us (disconnect) or the camera dropping the connection.
        }
    }

    private suspend fun emitObject(text: String) {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            return
        }
        _messages.emit(json)
    }
}
