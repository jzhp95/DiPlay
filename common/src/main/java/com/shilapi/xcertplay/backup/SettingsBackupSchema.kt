package com.shilapi.xcertplay.backup

/** Storage types follow the preference readers, never the imported JSON or previous contents.
 * Add entries when introducing a new backed-up setting. Unknown fresh keys are skipped.
 */
internal object SettingsBackupSchema {
    enum class Type { BOOLEAN, INT, LONG, FLOAT, STRING, STRING_SET }

    private val airplay = buildMap<String, Type> {
        listOf(
            "adapt_pip_resolution", "adb_cluster_activity_enabled", "advanced_audio_channel_mapping",
            "audio_focus_auto_yield", "audio_focus_enabled", "auto_start_on_boot",
            "bt_suspend_during_carplay", "call_echo_cancellation", "call_voice_filter",
            "car_bluetooth_audio", "center_map_auto_hide", "center_map_follows_dashboard",
            "center_map_overlay", "cluster_map_enabled", "cluster_speedometer_enabled",
            "direct_video_output", "disable_media_key_audio_focus", "external_controller",
            "fps_counter", "harman_fullscreen", "hevc_enabled",
            "hevc_software_decoder", "hide_bottom_bar", "hide_byd_call_popup",
            "hide_top_bar", "location_reporting_enabled", "low_latency_decoder",
            "main_buffered_audio", "main_display_disable_safe_area", "mtk_decoder_tuning",
            "right_hand_drive", "safe_area_draw_outside", "smooth_video",
            "speech_without_8_16_khz", "temporary_system_bars", "wifi_direct_scan_pause",
            "wireless_enabled",
        ).forEach { put(it, Type.BOOLEAN) }
        listOf(
            "ambient_lux_threshold", "black_corner_radius_dp", "bt_suspend_delay_seconds",
            "carplay_night_end_minute", "carplay_night_start_minute", "cluster_map_scale_percent",
            "cluster_marker_horizontal_step", "cluster_marker_vertical_step", "cluster_marker_x_percent",
            "cluster_marker_y_percent", "cluster_small_window_card_opacity_percent", "cluster_small_window_card_size",
            "cluster_small_window_card_theme", "cluster_small_window_card_x", "cluster_small_window_card_y",
            "cluster_small_window_marker_x", "cluster_small_window_marker_x_percent", "cluster_small_window_marker_y",
            "cluster_small_window_marker_y_percent", "cluster_small_window_mode", "cluster_speedometer_opacity",
            "cluster_speedometer_size", "cluster_speedometer_x", "cluster_speedometer_y",
            "cluster_turn_card_opacity_percent", "cluster_turn_card_overlay_size_percent", "cluster_turn_card_overlay_x_percent",
            "cluster_turn_card_overlay_y_percent", "cluster_turn_card_theme", "display_fps",
            "display_scale_tenths", "display_width_physical_mm", "manual_hotspot_channel",
            "media_audio_channel", "media_buffer_ms", "navigation_audio_channel",
            "navigation_stream_type", "settings_gesture_fingers", "ui_scale_percent",
            "wifi_p2p_preferred_channel", "wired_media_buffer_ms",
        ).forEach { put(it, Type.INT) }
        listOf(
            "app_appearance", "carplay_night_mode", "cluster_content",
            "cluster_safe_area_1920x720", "cluster_turn_card_overlay_position", "cluster_turn_card_overlay_size",
            "display_physical_size_basis", "manual_hotspot_band", "manual_hotspot_security",
            "usb_charging_current", "wireless_hotspot_mode",
        ).forEach { put(it, Type.STRING) }
    }

    fun type(file: String, key: String): Type? = when (file) {
        "xcertplay_airplay" -> airplay[key]
        "diplay" -> when (key) {
            "default_connection_mode" -> Type.STRING
            "settings_gesture_fingers" -> Type.INT
            "auto_connect", "enter_carplay_on_open", "connect_on_phone_bluetooth",
            "settings_force_full_layout", "app_appearance_button", "byd_vehicle_advanced",
            "picture_controls" -> Type.BOOLEAN
            else -> null
        }
        "carplay_picture" -> when (key) {
            "brightness", "contrast", "saturation", "warmth" -> Type.INT
            else -> null
        }
        "diplay_car_hotspot" -> when (key) {
            "auto_enable", "ignore_off_report" -> Type.BOOLEAN
            else -> null
        }
        else -> null
    }

    // Preserve the storage type of already-known extension keys, without inferring from input.
    fun storedType(value: Any?): Type? = when (value) {
        is Boolean -> Type.BOOLEAN
        is Int -> Type.INT
        is Long -> Type.LONG
        is Float -> Type.FLOAT
        is String -> Type.STRING
        is Set<*> -> Type.STRING_SET
        else -> null
    }
}
