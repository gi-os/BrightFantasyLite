package com.gios.brightfantasylite.data

/**
 * When a refresh is allowed to go out.
 *
 * The tool refreshes when it is opened and when you press refresh, and never on a timer.
 * Opening it twice in a minute reuses what it has; pressing refresh again within a few
 * seconds of the last one is ignored. ESPN's numbers move once a play is scored, not
 * faster, so hammering the button only burns data.
 */
object Refresh {
    /** Opening the tool within this long of the last load shows what is already there. */
    const val ON_OPEN_MS = 60_000L

    /** The fastest a manual refresh can repeat. */
    const val MANUAL_MS = 15_000L

    fun dueOnOpen(lastMillis: Long, nowMillis: Long): Boolean =
        lastMillis <= 0L || nowMillis - lastMillis >= ON_OPEN_MS

    fun manualAllowed(lastMillis: Long, nowMillis: Long): Boolean =
        lastMillis <= 0L || nowMillis - lastMillis >= MANUAL_MS

    /** "updated 2 min ago", for the one line that says whether to trust the screen. */
    fun ago(millis: Long, nowMillis: Long): String {
        if (millis <= 0) return ""
        val seconds = (nowMillis - millis) / 1000
        return when {
            seconds < 45 -> "just now"
            seconds < 90 -> "1 min ago"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 7200 -> "1 hr ago"
            else -> "${seconds / 3600} hr ago"
        }
    }
}
