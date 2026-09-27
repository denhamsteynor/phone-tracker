package app.locationtimeline

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.format.DateUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    companion object {
        private const val RC_FINE = 1
        private const val RC_ACTIVITY = 2
        private const val RC_NOTIFICATIONS = 3
        private const val RC_BACKGROUND = 4
        private const val REFRESH_MS = 3_000L

        private const val COLOR_BG = 0xFFF4F5F7.toInt()
        private const val COLOR_CARD = 0xFFFFFFFF.toInt()
        private const val COLOR_TEXT = 0xFF1B1D21.toInt()
        private const val COLOR_MUTED = 0xFF5F6670.toInt()
        private const val COLOR_OK = 0xFF1E7D34.toInt()
        private const val COLOR_BAD = 0xFFB3261E.toInt()
    }

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var signInForm: LinearLayout
    private lateinit var signedInBox: LinearLayout
    private lateinit var signedInText: TextView
    private lateinit var urlField: EditText
    private lateinit var keyField: EditText
    private lateinit var emailField: EditText
    private lateinit var passwordField: EditText
    private lateinit var signInButton: Button
    private lateinit var permissionsText: TextView
    private lateinit var batteryButton: Button
    private lateinit var trackButton: Button
    private lateinit var statusText: TextView

    private val refresher = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        setupEdgeToEdge()
        setContentView(buildUi())
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (prefs.trackingEnabled && TrackingService.instance == null && TrackingService.hasLocationPermission(this)) {
            TrackingService.start(this)
            UploadWorker.schedulePeriodic(this)
        }
        refreshAll()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    // ---- Layout ----

    private fun setupEdgeToEdge() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            val light = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(light, light)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or
                View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = ScrollView(this).apply {
            setBackgroundColor(COLOR_BG)
            isFillViewport = true
            clipToPadding = false
            addView(column)
        }
        val pad = dp(16)
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val sides: IntArray = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime()
                )
                intArrayOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(
                    insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom
                )
            }
            val (l, t, r, b) = sides
            v.setPadding(pad + l, pad + t, pad + r, pad + b)
            insets
        }

        column.addView(TextView(this).apply {
            text = "Location Timeline"
            textSize = 24f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, dp(12))
        })

        // 1) Account
        column.addView(card("1. Supabase account") { c ->
            signInForm = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            urlField = field("Project URL (https://xxxx.supabase.co)", InputType.TYPE_TEXT_VARIATION_URI)
            keyField = field("anon public key", InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)
            emailField = field("Email", InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
            passwordField = field("Password", InputType.TYPE_TEXT_VARIATION_PASSWORD)
            urlField.setText(prefs.supabaseUrl)
            keyField.setText(prefs.anonKey)
            emailField.setText(prefs.email)
            signInButton = button("Sign in") { signIn() }
            listOf(urlField, keyField, emailField, passwordField, signInButton).forEach { signInForm.addView(it) }
            c.addView(signInForm)

            signedInBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            signedInText = label("")
            signedInBox.addView(signedInText)
            signedInBox.addView(button("Sign out") { confirmSignOut() })
            c.addView(signedInBox)
        })

        // 2) Permissions
        column.addView(card("2. Permissions") { c ->
            permissionsText = label("")
            c.addView(permissionsText)
            c.addView(button("Grant permissions") { nextPermissionStep() })
            batteryButton = button("Allow running in background (battery)") { requestBatteryExemption() }
            c.addView(batteryButton)
        })

        // 3) Tracking
        column.addView(card("3. Tracking") { c ->
            trackButton = button("Start tracking") { toggleTracking() }
            c.addView(trackButton)
            c.addView(button("Upload now") {
                UploadWorker.uploadNow(this)
                Toast.makeText(this, "Upload queued (runs when online)", Toast.LENGTH_SHORT).show()
            })
            statusText = label("")
            statusText.setPadding(0, dp(8), 0, 0)
            c.addView(statusText)
        })

        return scroll
    }

    private fun card(title: String, content: (LinearLayout) -> Unit): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = GradientDrawable().apply {
                setColor(COLOR_CARD)
                cornerRadius = dp(12).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        box.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(COLOR_TEXT)
            setPadding(0, 0, 0, dp(6))
        })
        content(box)
        return box
    }

    private fun label(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setTextColor(COLOR_TEXT)
        setLineSpacing(0f, 1.15f)
    }

    private fun field(hintText: String, variation: Int) = EditText(this).apply {
        hint = hintText
        inputType = InputType.TYPE_CLASS_TEXT or variation
        isSingleLine = true
        textSize = 15f
        setTextColor(COLOR_TEXT)
        setHintTextColor(COLOR_MUTED)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
    }

    // ---- Account ----

    private fun signIn() {
        val url = urlField.text.toString().trim()
        val key = keyField.text.toString().trim()
        val email = emailField.text.toString().trim()
        val password = passwordField.text.toString()
        if (url.isEmpty() || key.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Fill in all four fields", Toast.LENGTH_SHORT).show()
            return
        }
        signInButton.isEnabled = false
        signInButton.text = "Signing in…"
        Thread {
            val result = try {
                Result.success(SupabaseClient(this).signIn(url, key, email, password))
            } catch (e: Exception) {
                Result.failure(e)
            }
            runOnUiThread {
                signInButton.isEnabled = true
                signInButton.text = "Sign in"
                result.onSuccess {
                    passwordField.setText("")
                    prefs.lastError = null
                    UploadWorker.uploadNow(this)
                    Toast.makeText(this, "Signed in as $it", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    AlertDialog.Builder(this)
                        .setTitle("Sign-in failed")
                        .setMessage(it.message ?: it.toString())
                        .setPositiveButton("OK", null)
                        .show()
                }
                refreshAll()
            }
        }.start()
    }

    private fun confirmSignOut() {
        AlertDialog.Builder(this)
            .setTitle("Sign out?")
            .setMessage("Tracking continues and points stay on the phone until you sign in again.")
            .setPositiveButton("Sign out") { _, _ ->
                SupabaseClient(this).signOut()
                refreshAll()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Permissions ----

    private fun granted(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    private fun notificationsGranted() =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    private fun batteryExempt(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun nextPermissionStep() {
        when {
            !granted(Manifest.permission.ACCESS_FINE_LOCATION) -> requestPermissions(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), RC_FINE
            )
            !granted(Manifest.permission.ACTIVITY_RECOGNITION) -> requestPermissions(
                arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), RC_ACTIVITY
            )
            !notificationsGranted() -> requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIFICATIONS
            )
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) -> AlertDialog.Builder(this)
                .setTitle("Allow location all the time")
                .setMessage(
                    "To record your timeline while the phone is in your pocket, Android needs location " +
                        "access \"all the time\".\n\nOn the next screen choose \"Allow all the time\"."
                )
                .setPositiveButton("Continue") { _, _ ->
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), RC_BACKGROUND)
                }
                .setNegativeButton("Not now", null)
                .show()
            !batteryExempt() -> requestBatteryExemption()
            else -> Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
        }
        refreshAll()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshAll()
        val ok = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        if (ok) {
            // Let a running service pick up newly granted permissions (e.g. activity recognition).
            TrackingService.instance?.onPermissionsChanged()
            nextPermissionStep()
            return
        }
        val what = when (requestCode) {
            RC_FINE -> "Precise location"
            RC_ACTIVITY -> "Physical activity"
            RC_NOTIFICATIONS -> "Notifications"
            else -> "Location \"Allow all the time\""
        }
        AlertDialog.Builder(this)
            .setTitle("$what not granted")
            .setMessage(
                "You can grant it in the app's settings: open Permissions, pick the item and allow it" +
                    (if (requestCode == RC_BACKGROUND || requestCode == RC_FINE) " (Location → \"Allow all the time\", Use precise location on)." else ".")
            )
            .setPositiveButton("Open app settings") { _, _ -> openAppSettings() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    @android.annotation.SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        if (batteryExempt()) {
            Toast.makeText(this, "Battery optimisation is already off for this app", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    // ---- Tracking ----

    private fun toggleTracking() {
        if (prefs.trackingEnabled) {
            prefs.trackingEnabled = false
            TrackingService.stop(this)
        } else {
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                Toast.makeText(this, "Grant location permission first", Toast.LENGTH_SHORT).show()
                nextPermissionStep()
                return
            }
            prefs.trackingEnabled = true
            TrackingService.start(this)
            UploadWorker.schedulePeriodic(this)
            if (!granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
                Toast.makeText(this, "Tip: allow location \"all the time\" or tracking stops in the background", Toast.LENGTH_LONG).show()
            }
        }
        refreshAll()
    }

    // ---- Status ----

    private fun refreshAll() {
        val signedIn = prefs.isSignedIn
        signInForm.visibility = if (signedIn) View.GONE else View.VISIBLE
        signedInBox.visibility = if (signedIn) View.VISIBLE else View.GONE
        signedInText.text = "Signed in as ${prefs.email}\n${prefs.supabaseUrl}"

        val rows = listOf(
            "Precise location" to granted(Manifest.permission.ACCESS_FINE_LOCATION),
            "Physical activity" to granted(Manifest.permission.ACTIVITY_RECOGNITION),
            "Notifications" to notificationsGranted(),
            "Location \"Allow all the time\"" to granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION),
            "Battery: unrestricted" to batteryExempt(),
        )
        permissionsText.text = rows.joinToString("\n") { (name, ok) -> (if (ok) "✓  " else "✗  ") + name }
        batteryButton.visibility = if (batteryExempt()) View.GONE else View.VISIBLE
        refreshStatus()
    }

    private fun ago(t: Long): String =
        if (t <= 0L) "never"
        else DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

    private fun refreshStatus() {
        val enabled = prefs.trackingEnabled
        val running = TrackingService.instance != null
        trackButton.text = if (enabled) "Stop tracking" else "Start tracking"
        val mode = when {
            !enabled -> "Off"
            !running -> "Not running (tap Start or reopen the app)"
            else -> (TrackingService.instance?.tier ?: Tier.fromKey(prefs.tierKey) ?: Tier.WALK).let {
                if (it == Tier.STILL) "Still (checking every 10 min)" else it.label
            }
        }
        val waiting = try { LocalDb.get(this).count() } catch (e: Exception) { -1L }
        val lastUpload = if (prefs.lastUploadAt > 0) "${ago(prefs.lastUploadAt)} (${prefs.lastUploadCount} points)" else "never"
        val sb = StringBuilder()
        sb.append("Mode: ").append(mode).append('\n')
        sb.append("Last fix: ").append(ago(prefs.lastFixAt)).append('\n')
        sb.append("Points waiting to upload: ").append(waiting).append('\n')
        sb.append("Last upload: ").append(lastUpload)
        val err = prefs.lastError
        if (!err.isNullOrEmpty()) sb.append("\nLast error (").append(ago(prefs.lastErrorAt)).append("): ").append(err)
        statusText.text = sb.toString()
        statusText.setTextColor(if (!err.isNullOrEmpty()) COLOR_BAD else if (running) COLOR_OK else COLOR_TEXT)
        statusText.gravity = Gravity.START
    }
}
