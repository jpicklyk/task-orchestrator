package io.github.jpicklyk.mcptask.current.infrastructure.config

/**
 * Retention windows, in days, read once at startup from the `RETENTION_*` variables. `0` keeps rows forever.
 *
 * @property callLogDays `RETENTION_CALL_LOG_DAYS` (default 90): `call_log` rows; pruned only once folded into
 *   `usage_daily` (see `CallLogFoldWatermark`).
 * @property eventsDays `RETENTION_EVENTS_DAYS` (default 180): activity-class `events` rows.
 * @property auditEventsDays `RETENTION_AUDIT_EVENTS_DAYS` (default 0 = forever): audit-class `events` rows.
 * @property leaseHistoryDays `RETENTION_LEASE_HISTORY_DAYS` (default 365): closed `resource_lease_history` intervals.
 */
data class RetentionSettings(
    val callLogDays: Int,
    val eventsDays: Int,
    val auditEventsDays: Int,
    val leaseHistoryDays: Int
) {
    init {
        require(callLogDays >= 0 && eventsDays >= 0 && auditEventsDays >= 0 && leaseHistoryDays >= 0) {
            "Retention days must be non-negative: $this"
        }
    }

    companion object {
        const val CALL_LOG_DAYS_VAR = "RETENTION_CALL_LOG_DAYS"
        const val EVENTS_DAYS_VAR = "RETENTION_EVENTS_DAYS"
        const val AUDIT_EVENTS_DAYS_VAR = "RETENTION_AUDIT_EVENTS_DAYS"
        const val LEASE_HISTORY_DAYS_VAR = "RETENTION_LEASE_HISTORY_DAYS"

        /** 90 / 180 / 0 / 365. */
        val DEFAULT: RetentionSettings =
            RetentionSettings(callLogDays = 90, eventsDays = 180, auditEventsDays = 0, leaseHistoryDays = 365)

        /**
         * Reads the four variables through [env]. Unset takes the default; a set value is trimmed and must be a
         * non-negative integer (`+90` and `090` are accepted). Anything else (blank, negative, non-integer) throws
         * [IllegalArgumentException] naming the variable and the raw value, which fails startup.
         */
        fun parse(env: (String) -> String?): RetentionSettings =
            RetentionSettings(
                callLogDays = days(env, CALL_LOG_DAYS_VAR, DEFAULT.callLogDays),
                eventsDays = days(env, EVENTS_DAYS_VAR, DEFAULT.eventsDays),
                auditEventsDays = days(env, AUDIT_EVENTS_DAYS_VAR, DEFAULT.auditEventsDays),
                leaseHistoryDays = days(env, LEASE_HISTORY_DAYS_VAR, DEFAULT.leaseHistoryDays)
            )

        private fun days(
            env: (String) -> String?,
            name: String,
            default: Int
        ): Int {
            val raw = env(name) ?: return default
            val parsed = raw.trim().toIntOrNull()
            require(parsed != null && parsed >= 0) {
                "Environment variable '$name' has invalid value '$raw': expected a non-negative whole number of days (0 = keep forever)"
            }
            return parsed
        }
    }
}
