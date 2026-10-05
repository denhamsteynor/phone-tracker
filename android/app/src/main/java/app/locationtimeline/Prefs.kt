package app.locationtimeline

import android.content.Context
import android.content.SharedPreferences

/** Small typed wrapper around the app's SharedPreferences. The password is never stored. */
class Prefs(context: Context) {
    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var supabaseUrl: String
        get() = sp.getString("supabase_url", "") ?: ""
        set(v) = sp.edit().putString("supabase_url", v).apply()

    var anonKey: String
        get() = sp.getString("anon_key", "") ?: ""
        set(v) = sp.edit().putString("anon_key", v).apply()

    var email: String
        get() = sp.getString("email", "") ?: ""
        set(v) = sp.edit().putString("email", v).apply()

    var accessToken: String?
        get() = sp.getString("access_token", null)
        set(v) = sp.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = sp.getString("refresh_token", null)
        set(v) = sp.edit().putString("refresh_token", v).apply()

    /** Access token expiry, epoch millis. */
    var expiresAt: Long
        get() = sp.getLong("expires_at", 0L)
        set(v) = sp.edit().putLong("expires_at", v).apply()

    val isSignedIn: Boolean
        get() = !refreshToken.isNullOrEmpty() && supabaseUrl.isNotEmpty() && anonKey.isNotEmpty()

    fun saveSession(access: String, refresh: String, expiresAtMs: Long) {
        sp.edit()
            .putString("access_token", access)
            .putString("refresh_token", refresh)
            .putLong("expires_at", expiresAtMs)
            .apply()
    }

    fun clearSession() {
        sp.edit().remove("access_token").remove("refresh_token").remove("expires_at").apply()
    }

    var trackingEnabled: Boolean
        get() = sp.getBoolean("tracking_enabled", false)
        set(v) = sp.edit().putBoolean("tracking_enabled", v).apply()

    var tierKey: String
        get() = sp.getString("tier", Tier.WALK.key) ?: Tier.WALK.key
        set(v) = sp.edit().putString("tier", v).apply()

    var lastUploadAt: Long
        get() = sp.getLong("last_upload_at", 0L)
        set(v) = sp.edit().putLong("last_upload_at", v).apply()

    var lastUploadCount: Int
        get() = sp.getInt("last_upload_count", 0)
        set(v) = sp.edit().putInt("last_upload_count", v).apply()

    var lastError: String?
        get() = sp.getString("last_error", null)
        set(v) = sp.edit().putString("last_error", v).apply()

    var lastErrorAt: Long
        get() = sp.getLong("last_error_at", 0L)
        set(v) = sp.edit().putLong("last_error_at", v).apply()

    fun recordError(message: String) {
        sp.edit().putString("last_error", message).putLong("last_error_at", System.currentTimeMillis()).apply()
    }

    /** Adds time spent in a tracking mode to today's totals (shown on the status screen). */
    fun addModeTime(tierKey: String, ms: Long) {
        if (ms <= 0) return
        val today = java.time.LocalDate.now().toString()
        val e = sp.edit()
        if (sp.getString("mode_day", null) != today) {
            Tier.entries.forEach { e.remove("mode_ms_${it.key}") }
            e.putString("mode_day", today)
            e.putLong("mode_ms_$tierKey", ms)
        } else {
            e.putLong("mode_ms_$tierKey", sp.getLong("mode_ms_$tierKey", 0L) + ms)
        }
        e.apply()
    }

    /** Milliseconds spent in each mode today (by tier key). */
    fun modeTimesToday(): Map<String, Long> {
        if (sp.getString("mode_day", null) != java.time.LocalDate.now().toString()) return emptyMap()
        return Tier.entries.associate { it.key to sp.getLong("mode_ms_${it.key}", 0L) }
    }

    var lastFixAt: Long
        get() = sp.getLong("last_fix_at", 0L)
        set(v) = sp.edit().putLong("last_fix_at", v).apply()

    /** Last point written to the local buffer, so a restarted service doesn't re-record the same place. */
    fun saveLastRecorded(lat: Double, lon: Double, time: Long) {
        sp.edit()
            .putString("last_rec_lat", lat.toString())
            .putString("last_rec_lon", lon.toString())
            .putLong("last_rec_time", time)
            .apply()
    }

    fun lastRecorded(): Triple<Double, Double, Long>? {
        val lat = sp.getString("last_rec_lat", null)?.toDoubleOrNull() ?: return null
        val lon = sp.getString("last_rec_lon", null)?.toDoubleOrNull() ?: return null
        return Triple(lat, lon, sp.getLong("last_rec_time", 0L))
    }
}
