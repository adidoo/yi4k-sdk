package com.adidoo.yi4k.sdk

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Higher-level, stateful API on top of [YiCameraClient]: handles the token handshake,
 * exposes camera state as flows for the UI, and turns the raw JSON protocol into
 * suspend functions with sane failure modes.
 */
class YiCameraController(
    private val client: YiCameraClient = YiCameraClient(),
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var token: Int = 0
    private var eventCollectorJob: Job? = null

    private val _connectionState = MutableStateFlow<CameraConnectionState>(CameraConnectionState.Disconnected)
    val connectionState: StateFlow<CameraConnectionState> = _connectionState

    private val _batteryPercent = MutableStateFlow<Int?>(null)
    val batteryPercent: StateFlow<Int?> = _batteryPercent

    private val _isRecording = MutableStateFlow(false)
    val isRecording: StateFlow<Boolean> = _isRecording

    private val _events = MutableSharedFlow<CameraEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<CameraEvent> = _events

    suspend fun connect() {
        _connectionState.value = CameraConnectionState.Connecting
        try {
            client.connect()
            eventCollectorJob = scope.launch { collectPushEvents() }
            token = requestToken()
            val settings = fetchAllSettings()
            // The camera may already be recording from before this app (re)connected to it
            // (e.g. started from the physical shutter button, or a previous app session) —
            // reflect that instead of assuming a fresh, idle camera.
            _isRecording.value = settings[YiProtocol.KEY_APP_STATUS]?.contains("record", ignoreCase = true) == true
            _connectionState.value = CameraConnectionState.Connected(settings)
            refreshBattery()
        } catch (e: Exception) {
            eventCollectorJob?.cancel()
            client.disconnect()
            _connectionState.value = CameraConnectionState.Failed(
                e.message ?: "Connexion à la caméra impossible"
            )
        }
    }

    fun disconnect() {
        eventCollectorJob?.cancel()
        client.disconnect()
        _connectionState.value = CameraConnectionState.Disconnected
        _isRecording.value = false
    }

    suspend fun fetchAllSettings(): Map<String, String> {
        val reply = client.sendCommand(baseRequest(YiProtocol.MSG_GET_ALL_SETTINGS)) {
            it.optInt("msg_id") == YiProtocol.MSG_GET_ALL_SETTINGS
        }
        val array = reply.optJSONArray("param") ?: JSONArray()
        val map = LinkedHashMap<String, String>()
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            entry.keys().forEach { key -> map[key] = entry.optString(key) }
        }
        return map
    }

    suspend fun getSettingChoices(key: String): List<String> {
        val request = baseRequest(YiProtocol.MSG_GET_SETTING_CHOICES).put("param", key)
        val reply = client.sendCommand(request) {
            it.optInt("msg_id") == YiProtocol.MSG_GET_SETTING_CHOICES && it.optString("param") == key
        }
        val options = reply.optJSONArray("options") ?: JSONArray()
        return (0 until options.length()).map { options.getString(it) }
    }

    suspend fun setSetting(key: String, value: String) {
        val request = baseRequest(YiProtocol.MSG_SET_SETTING).put("type", key).put("param", value)
        val reply = client.sendCommand(request) {
            it.optInt("msg_id") == YiProtocol.MSG_SET_SETTING && it.optString("type") == key
        }
        check(reply.optInt("rval", -1) == 0) { "La caméra a refusé $key=$value" }
    }

    suspend fun refreshBattery() {
        val reply = client.sendCommand(baseRequest(YiProtocol.MSG_GET_BATTERY)) {
            it.optInt("msg_id") == YiProtocol.MSG_GET_BATTERY
        }
        _batteryPercent.value = reply.optString("param").toIntOrNull()
    }

    /** Free space left on the SD card, in bytes. */
    suspend fun getFreeStorageBytes(): Long {
        val request = baseRequest(YiProtocol.MSG_GET_STORAGE_SPACE).put("type", YiProtocol.STORAGE_FREE)
        val reply = client.sendCommand(request) {
            it.optInt("msg_id") == YiProtocol.MSG_GET_STORAGE_SPACE
        }
        return reply.optLong("param", -1L)
    }

    /** Triggers a photo capture; returns the on-camera SD card path once it's saved. */
    suspend fun takePhoto(): String {
        val reply = client.sendCommand(baseRequest(YiProtocol.MSG_TAKE_PHOTO), timeoutMs = 15_000) {
            it.optInt("msg_id") == YiProtocol.MSG_STATUS_EVENT && it.optString("type") == YiProtocol.EVENT_PHOTO_TAKEN
        }
        return reply.optString("param")
    }

    suspend fun startRecording() {
        client.sendCommand(baseRequest(YiProtocol.MSG_START_RECORD)) {
            it.optInt("msg_id") == YiProtocol.MSG_STATUS_EVENT && it.optString("type") == YiProtocol.EVENT_START_VIDEO_RECORD
        }
        _isRecording.value = true
    }

    suspend fun stopRecording() {
        client.sendCommand(baseRequest(YiProtocol.MSG_STOP_RECORD), timeoutMs = 15_000) {
            it.optInt("msg_id") == YiProtocol.MSG_STATUS_EVENT && it.optString("type") == YiProtocol.EVENT_VIDEO_RECORD_COMPLETE
        }
        _isRecording.value = false
    }

    /** Asks the camera to (re)start its RTSP live preview and returns the URL to play. */
    suspend fun startLiveView(): String {
        client.sendCommand(baseRequest(YiProtocol.MSG_START_STREAM)) {
            (it.optInt("msg_id") == YiProtocol.MSG_START_STREAM && it.has("rval")) ||
                (it.optInt("msg_id") == YiProtocol.MSG_STATUS_EVENT && it.optString("type") == YiProtocol.EVENT_VIEWFINDER_STARTED)
        }
        return YiProtocol.rtspUrl()
    }

    suspend fun stopLiveView() {
        client.sendCommand(baseRequest(YiProtocol.MSG_STOP_STREAM)) {
            (it.optInt("msg_id") == YiProtocol.MSG_STOP_STREAM && it.has("rval")) ||
                (it.optInt("msg_id") == YiProtocol.MSG_STATUS_EVENT && it.optString("type") == YiProtocol.EVENT_VIEWFINDER_STOPPED)
        }
    }

    private suspend fun requestToken(): Int {
        val request = JSONObject().put("msg_id", YiProtocol.MSG_REQUEST_TOKEN).put("token", 0)
        val reply = client.sendCommand(request) {
            it.optInt("msg_id") == YiProtocol.MSG_REQUEST_TOKEN && it.has("param")
        }
        return reply.getInt("param")
    }

    private fun baseRequest(msgId: Int): JSONObject =
        JSONObject().put("msg_id", msgId).put("token", token)

    private suspend fun collectPushEvents() {
        client.messages.collect { msg ->
            if (msg.optInt("msg_id") != YiProtocol.MSG_STATUS_EVENT) return@collect
            when (val type = msg.optString("type")) {
                YiProtocol.EVENT_PHOTO_TAKEN ->
                    _events.emit(CameraEvent.PhotoTaken(msg.optString("param")))
                YiProtocol.EVENT_START_VIDEO_RECORD -> {
                    _isRecording.value = true
                    _events.emit(CameraEvent.RecordingStarted)
                }
                YiProtocol.EVENT_VIDEO_RECORD_COMPLETE -> {
                    _isRecording.value = false
                    _events.emit(CameraEvent.RecordingStopped)
                }
                else -> _events.emit(CameraEvent.Unknown(type))
            }
        }
    }
}
