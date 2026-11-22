package io.github.tsutsu3.pi_hole_client.widget.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Settings and state of the "Internet access timer".
 *
 * - [disableHours] (X): how long a disable triggered from a widget lasts.
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

        const val DEFAULT_DISABLE_HOURS = 1
        const val DEFAULT_COOLDOWN_HOURS = 24
        const val MIN_DISABLE_HOURS = 1
        const val MAX_DISABLE_HOURS = 24
        const val MIN_COOLDOWN_HOURS = 0
        const val MAX_COOLDOWN_HOURS = 168

        private const val HOUR_MS = 3_600_000L

        @Volatile
        private var instance: AccessTimerPrefs? = null

        fun getInstance(context: Context): AccessTimerPrefs =
            instance ?: synchronized(this) {
                instance ?: AccessTimerPrefs(context.applicationContext).also { instance = it }
            }
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
}
