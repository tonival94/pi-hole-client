package io.github.tsutsu3.pi_hole_client.widget.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import io.github.tsutsu3.pi_hole_client.R
import io.github.tsutsu3.pi_hole_client.widget.common.WidgetStatus

/**
 * Settings and state of the "Internet access timer".
 *
 * - [disableHours] (X): how long a disable triggered from a widget lasts.
 *   0 = indefinitely (Pi-hole stays disabled until enabled by hand).
 * - [cooldownHours] (Y): Pi-hole can be disabled at most once every Y hours,
 *   counted from the start of the previous disable. 0 means no limit.
 * - last disable timestamp: shared by widgets and app, so the limit applies
 *   to both.
 *
 * This is the single source of truth: the Flutter side reads and writes it
 * through the `pihole/widget` MethodChannel.
 */
class AccessTimerPrefs private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "access_timer_prefs"
        private const val KEY_DISABLE_HOURS = "disable_hours"
        private const val KEY_COOLDOWN_HOURS = "cooldown_hours"
        private const val KEY_LAST_DISABLE_AT_MS = "last_disable_at_ms"
        private const val KEY_DISABLE_PRESETS_MIN = "disable_presets_min"
        private const val KEY_LAST_BLOCKING_PREFIX = "last_blocking_enabled_"

        const val DEFAULT_DISABLE_HOURS = 1
        const val DEFAULT_COOLDOWN_HOURS = 4
        /** 0 = indefinitely. */
        const val MIN_DISABLE_HOURS = 0
        const val MAX_DISABLE_HOURS = 24
        const val MIN_COOLDOWN_HOURS = 0
        const val MAX_COOLDOWN_HOURS = 168

        /** Durations (minutes) offered by the app's disable menu. */
        val DEFAULT_DISABLE_PRESETS_MIN = listOf(10, 20, 40, 60)
        const val DISABLE_PRESETS_COUNT = 4
        const val MIN_PRESET_MIN = 1
        const val MAX_PRESET_MIN = 1440

        private const val HOUR_MS = 3_600_000L

        // No cached instance: Android already caches SharedPreferences per
        // name, and a fresh object keeps unit tests independent.
        fun getInstance(context: Context): AccessTimerPrefs =
            AccessTimerPrefs(context.applicationContext)
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    val disableHours: Int
        get() = prefs.getInt(KEY_DISABLE_HOURS, DEFAULT_DISABLE_HOURS)
            .coerceIn(MIN_DISABLE_HOURS, MAX_DISABLE_HOURS)

    val cooldownHours: Int
        get() = prefs.getInt(KEY_COOLDOWN_HOURS, DEFAULT_COOLDOWN_HOURS)
            .coerceIn(MIN_COOLDOWN_HOURS, MAX_COOLDOWN_HOURS)

    val lastDisableAtMs: Long
        get() = prefs.getLong(KEY_LAST_DISABLE_AT_MS, 0L)

    val disableSeconds: Long
        get() = disableHours * 3600L

    /** Durations in minutes shown in the app's disable menu ("indefinitely" is always added). */
    val disablePresetsMinutes: List<Int>
        get() {
            val parsed = prefs.getString(KEY_DISABLE_PRESETS_MIN, null)
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.filter { it in MIN_PRESET_MIN..MAX_PRESET_MIN }
            return if (parsed != null && parsed.size == DISABLE_PRESETS_COUNT) parsed else DEFAULT_DISABLE_PRESETS_MIN
        }

    fun setDisablePresetsMinutes(minutes: List<Int>) {
        val clean = minutes.map { it.coerceIn(MIN_PRESET_MIN, MAX_PRESET_MIN) }.sorted()
        if (clean.size != DISABLE_PRESETS_COUNT) return
        prefs.edit().putString(KEY_DISABLE_PRESETS_MIN, clean.joinToString(",")).commit()
    }

    fun setHours(disableHours: Int, cooldownHours: Int) {
        prefs.edit()
            .putInt(KEY_DISABLE_HOURS, disableHours.coerceIn(MIN_DISABLE_HOURS, MAX_DISABLE_HOURS))
            .putInt(KEY_COOLDOWN_HOURS, cooldownHours.coerceIn(MIN_COOLDOWN_HOURS, MAX_COOLDOWN_HOURS))
            .commit()
    }

    /** Milliseconds until Pi-hole can be disabled again; 0 when allowed now. */
    fun cooldownRemainingMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastDisableAtMs
        if (cooldownHours == 0 || last <= 0L) return 0L
        val elapsed = (nowMs - last).coerceAtLeast(0L)
        return (cooldownHours * HOUR_MS - elapsed).coerceAtLeast(0L)
    }

    fun recordDisable(nowMs: Long = System.currentTimeMillis()) {
        prefs.edit().putLong(KEY_LAST_DISABLE_AT_MS, nowMs).commit()
    }

    /**
     * Remembers the last blocking status the widgets received, so a tap can
     * be checked against the cooldown even when Pi-hole is unreachable.
     * Errors and auth failures keep the previous value.
     */
    fun rememberStatus(serverId: String, status: WidgetStatus) {
        val enabled = when (status) {
            WidgetStatus.BLOCKING_ON -> true
            WidgetStatus.BLOCKING_OFF -> false
            else -> return
        }
        prefs.edit().putBoolean(KEY_LAST_BLOCKING_PREFIX + serverId, enabled).apply()
    }

    /** Last known blocking status: true = enabled, false = disabled, null = unknown. */
    fun lastKnownBlockingEnabled(serverId: String): Boolean? {
        val key = KEY_LAST_BLOCKING_PREFIX + serverId
        return if (prefs.contains(key)) prefs.getBoolean(key, true) else null
    }

    /**
     * If a widget tap would disable Pi-hole while the cooldown is active,
     * shows the "can be disabled again in ..." toast and returns true.
     * Works offline: it only uses the last known status.
     */
    fun blockTapDuringCooldown(context: Context, serverId: String): Boolean {
        val remainingMs = cooldownRemainingMs()
        if (remainingMs <= 0L || lastKnownBlockingEnabled(serverId) != true) return false
        showCooldownToast(context, remainingMs)
        return true
    }
}

/** Shows the cooldown toast ("Pi-hole can be disabled again in 3h 20m"). */
fun showCooldownToast(context: Context, remainingMs: Long) {
    val totalMinutes = ((remainingMs + 59_999L) / 60_000L).coerceAtLeast(1L)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val remaining = if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    val appContext = context.applicationContext
    val message = appContext.getString(R.string.access_timer_cooldown_toast, remaining)
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
}

/** Shows "Pi-hole is unreachable. Check your connection." */
fun showUnreachableToast(context: Context) {
    val appContext = context.applicationContext
    val message = appContext.getString(R.string.access_timer_unreachable_toast)
    Handler(Looper.getMainLooper()).post {
        Toast.makeText(appContext, message, Toast.LENGTH_LONG).show()
    }
}
