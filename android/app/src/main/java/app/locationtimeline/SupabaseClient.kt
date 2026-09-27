package app.locationtimeline

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class SupabaseException(message: String, val status: Int = 0) : IOException(message)

/** Minimal Supabase REST client: password sign-in, token refresh and location inserts. */
class SupabaseClient(context: Context) {
    private val prefs = Prefs(context)

    private data class Response(val code: Int, val body: String)

    companion object {
        private val tokenLock = Any()

        fun normaliseUrl(raw: String): String {
            var u = raw.trim().trimEnd('/')
            if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
            return u
        }
    }

    /** Signs in with email + password. Only the resulting tokens are stored. Returns the user's email. */
    fun signIn(url: String, anonKey: String, email: String, password: String): String {
        val base = normaliseUrl(url)
        val body = JSONObject().put("email", email.trim()).put("password", password)
        val r = http("POST", "$base/auth/v1/token?grant_type=password", anonKey, null, body.toString(), emptyMap())
        if (r.code !in 200..299) throw SupabaseException("Sign-in failed: ${errorText(r)}", r.code)
        prefs.supabaseUrl = base
        prefs.anonKey = anonKey.trim()
        saveTokens(JSONObject(r.body))
        val userEmail = JSONObject(r.body).optJSONObject("user")?.optString("email").orEmpty().ifEmpty { email.trim() }
        prefs.email = userEmail
        return userEmail
    }

    fun signOut() {
        prefs.clearSession()
    }

    private fun saveTokens(json: JSONObject) {
        val access = json.getString("access_token")
        val refresh = json.getString("refresh_token")
        val expiresAt = when {
            json.has("expires_at") -> json.getLong("expires_at") * 1000L
            else -> System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L
        }
        prefs.saveSession(access, refresh, expiresAt)
    }

    private fun refresh() {
        val rt = prefs.refreshToken ?: throw SupabaseException("Not signed in")
        val body = JSONObject().put("refresh_token", rt)
        val r = http("POST", "${prefs.supabaseUrl}/auth/v1/token?grant_type=refresh_token", prefs.anonKey, null, body.toString(), emptyMap())
        if (r.code !in 200..299) {
            if (r.code == 400 || r.code == 401) {
                throw SupabaseException("Session expired, please sign in again (${errorText(r)})", r.code)
            }
            throw SupabaseException("Token refresh failed: ${errorText(r)}", r.code)
        }
        saveTokens(JSONObject(r.body))
    }

    /** Returns a usable access token, refreshing it if it expires within a minute (or if forced). */
    private fun accessToken(forceRefresh: Boolean): String = synchronized(tokenLock) {
        if (!prefs.isSignedIn) throw SupabaseException("Not signed in")
        val tok = prefs.accessToken
        if (forceRefresh || tok.isNullOrEmpty() || prefs.expiresAt - System.currentTimeMillis() < 60_000L) {
            refresh()
        }
        prefs.accessToken ?: throw SupabaseException("Not signed in")
    }

    /** Inserts rows into public.locations, ignoring rows already uploaded. */
    fun insertLocations(rows: JSONArray) {
        val url = "${prefs.supabaseUrl}/rest/v1/locations?on_conflict=user_id,recorded_at"
        val headers = mapOf("Prefer" to "resolution=ignore-duplicates,return=minimal")
        var r = http("POST", url, prefs.anonKey, accessToken(false), rows.toString(), headers)
        if (r.code == 401) {
            r = http("POST", url, prefs.anonKey, accessToken(true), rows.toString(), headers)
        }
        if (r.code !in 200..299) throw SupabaseException("Upload failed: ${errorText(r)}", r.code)
    }

    private fun errorText(r: Response): String {
        val msg = try {
            val j = JSONObject(r.body)
            listOf("error_description", "msg", "message", "error").firstNotNullOfOrNull { k ->
                j.optString(k).takeIf { it.isNotEmpty() }
            }
        } catch (_: Exception) {
            null
        }
        return "HTTP ${r.code}" + (msg?.let { ": $it" } ?: r.body.take(200).let { if (it.isBlank()) "" else ": $it" })
    }

    private fun http(
        method: String,
        url: String,
        apiKey: String,
        bearer: String?,
        body: String?,
        headers: Map<String, String>,
    ): Response {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = 20_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("apikey", apiKey)
            if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
            conn.setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code >= 400) conn.errorStream else conn.inputStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Response(code, text)
        } finally {
            conn.disconnect()
        }
    }
}
