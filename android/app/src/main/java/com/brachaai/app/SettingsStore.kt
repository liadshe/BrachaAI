package com.brachaai.app

import android.content.Context
import android.content.SharedPreferences

/**
 * Sole owner of device-local user settings.
 *
 * Deliberately backed by plain SharedPreferences rather than the
 * EncryptedSharedPreferences that AuthStore uses: there is no secret here, and
 * AuthStore swallows read failures by design — acceptable for a token that can be
 * re-fetched, wrong for a flag that decides whether to destroy a recording.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * When true, the original call recording is deleted once its transcript is secured.
     *
     * Defaults to true so storage is reclaimed from first boot — CallMonitorService can
     * process a call before the user has ever opened Settings, so there is no opportunity
     * for a first-run write.
     */
    var deleteAudioAfterProcessing: Boolean
        get() = prefs.getBoolean(KEY_DELETE_AUDIO, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DELETE_AUDIO, value).apply()
        }

    companion object {
        private const val PREFS_NAME = "bracha_settings"
        private const val KEY_DELETE_AUDIO = "delete_audio_after_processing"
    }
}
