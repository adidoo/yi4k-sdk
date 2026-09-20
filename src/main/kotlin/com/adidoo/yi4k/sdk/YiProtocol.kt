package com.adidoo.yi4k.sdk

/**
 * The Yi 4K exposes a JSON-over-TCP control channel on its own access point at
 * 192.168.42.1:7878. This isn't officially documented by Xiaoyi/YI for this exact model;
 * it was reverse-engineered from the older Yi Action Camera (which uses the same IP/port)
 * and cross-checked against YI's own official Android SDK (github.com/YITechnology/YIOpenAPI),
 * whose sample app connects to the identical "tcp:192.168.42.1:7878" endpoint for the Yi 4K.
 *
 * Wire format: newline-free JSON objects written back-to-back on the same TCP stream.
 * The camera answers most requests with an object carrying the same "msg_id", and also
 * pushes unsolicited status events at any time using "msg_id": 7 (e.g. "photo_taken",
 * "start_video_record", "vf_start"/"vf_stop").
 */
object YiProtocol {
    const val HOST = "192.168.42.1"
    const val PORT = 7878

    // Status/event push from the camera, carries a "type" describing what happened.
    const val MSG_STATUS_EVENT = 7

    // Requests
    const val MSG_GET_SETTING = 1
    const val MSG_SET_SETTING = 2
    const val MSG_GET_ALL_SETTINGS = 3
    const val MSG_GET_STORAGE_SPACE = 5
    const val MSG_GET_SETTING_CHOICES = 9
    const val MSG_GET_BATTERY = 13
    const val MSG_REQUEST_TOKEN = 257
    const val MSG_START_STREAM = 259
    const val MSG_STOP_STREAM = 260
    const val MSG_START_RECORD = 513
    const val MSG_STOP_RECORD = 514
    const val MSG_TAKE_PHOTO = 769
    const val MSG_DELETE_FILE = 1281

    // Known "type" values carried by MSG_STATUS_EVENT pushes.
    const val EVENT_PHOTO_TAKEN = "photo_taken"
    const val EVENT_START_PHOTO_CAPTURE = "start_photo_capture"
    const val EVENT_START_VIDEO_RECORD = "start_video_record"
    const val EVENT_VIDEO_RECORD_COMPLETE = "video_record_complete"
    const val EVENT_VIEWFINDER_STARTED = "vf_start"
    const val EVENT_VIEWFINDER_STOPPED = "vf_stop"

    // Well-known setting keys (the actual set/available values are read from the camera
    // at runtime via MSG_GET_ALL_SETTINGS / MSG_GET_SETTING_CHOICES, since they vary by
    // firmware and model rather than being hardcoded here).
    const val KEY_VIDEO_RESOLUTION = "video_resolution"
    const val KEY_VIDEO_STANDARD = "video_standard"
    const val KEY_SYSTEM_MODE = "system_mode"
    const val KEY_SD_CARD_STATUS = "sd_card_status"

    /** RTSP URL the live preview is published on once MSG_START_STREAM succeeds. */
    fun rtspUrl(): String = "rtsp://$HOST/live"
}
