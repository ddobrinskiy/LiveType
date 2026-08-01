package dev.dobrinskiy.livetype.ime

import android.os.Handler

/**
 * The last recognised phrase, held so the user can put it somewhere after the
 * fact — the recovery path for the case that loses text outright: dictation
 * finishes while no editor has focus, `currentInputConnection` is null or has
 * no target, and the commit lands nowhere at all.
 *
 * ### Privacy
 *
 * **Memory only, and only for [DEFAULT_TTL_MS].** `README.md` promises
 * "LiveType keeps no dictation history", so this must never reach
 * `SharedPreferences`, a file, a database or the system clipboard — the
 * clipboard in particular is readable by other apps and by the clipboard
 * history UI, which would be a different privacy posture than the one the
 * product advertises. One phrase, in one field, gone in five minutes.
 *
 * Expiry drops the **reference**, it does not merely hide the text behind a
 * timestamp comparison: after [clear] the string is unreachable and the
 * transcript is not sitting in the heap waiting for a memory dump. That is why
 * there is a timer here at all rather than a `rememberedAtMs` check at read
 * time.
 *
 * ### Threading and lifetime
 *
 * Everything runs on [handler]'s thread (the IME's main handler), so no
 * synchronisation is needed. The single pending callback is removed by every
 * path that touches the phrase, and [release] removes it unconditionally —
 * the owner must call that from `onDestroy` so nothing outlives the service.
 */
class RecentPhrase(
    private val handler: Handler,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val onChanged: () -> Unit = {},
) {
    private var phrase: String? = null

    private val expiryRunnable = Runnable { clear() }

    /** True while there is a phrase that has not expired. */
    val isAvailable: Boolean
        get() = phrase != null

    /**
     * Holds [text] for [ttlMs], replacing whatever was held before and
     * restarting the clock. Empty text is not worth remembering and is ignored
     * rather than clearing what is already there.
     */
    fun remember(text: String) {
        if (text.isEmpty()) return
        phrase = text
        handler.removeCallbacks(expiryRunnable)
        handler.postDelayed(expiryRunnable, ttlMs)
        onChanged()
    }

    /**
     * The remembered phrase, or null once it has expired or was never set.
     *
     * Reading does **not** consume it: the first paste may well have gone into
     * the wrong field, and asking the user to dictate again would be the very
     * failure this class exists to prevent.
     */
    fun peek(): String? = phrase

    /** Drops the text and the pending timer, and notifies the owner. */
    fun clear() {
        handler.removeCallbacks(expiryRunnable)
        if (phrase == null) return
        phrase = null
        onChanged()
    }

    /**
     * Teardown: same as [clear] but silent, for use from `onDestroy` where the
     * views the callback would refresh are already going away.
     */
    fun release() {
        handler.removeCallbacks(expiryRunnable)
        phrase = null
    }

    companion object {
        /**
         * Five minutes, the window the user asked for.
         *
         * Long enough to switch app, find the right field and paste; short
         * enough that a phone left on a table is not holding a transcript.
         */
        const val DEFAULT_TTL_MS = 5 * 60_000L
    }
}
