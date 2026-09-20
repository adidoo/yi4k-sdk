package com.adidoo.yi4k.sdk

/** High-level connection lifecycle exposed to the UI. */
sealed interface CameraConnectionState {
    data object Disconnected : CameraConnectionState
    data object Connecting : CameraConnectionState
    data class Connected(val settings: Map<String, String>) : CameraConnectionState
    data class Failed(val message: String) : CameraConnectionState
}

/** Unsolicited status pushes the camera can send at any time (msg_id 7). */
sealed interface CameraEvent {
    data class PhotoTaken(val path: String) : CameraEvent
    data object RecordingStarted : CameraEvent
    data object RecordingStopped : CameraEvent
    data class Unknown(val type: String) : CameraEvent
}
