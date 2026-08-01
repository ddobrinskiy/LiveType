package dev.dobrinskiy.livetype.config

/**
 * Central switchboard for behaviour that is implemented but deliberately not
 * live.
 *
 * A flag here means "the code path is kept and maintained, but off". Prefer
 * flipping a flag over deleting a feature, and over commenting code out — the
 * implementation stays compiled and type-checked either way.
 *
 * When a flag gates something the user can also configure, the flag wins: the
 * stored preference is only consulted if the flag is on, and the corresponding
 * setting is hidden while it is off, so the UI never offers a toggle that does
 * nothing.
 */
object FeatureFlags {
    /**
     * Switch back to the previously selected keyboard once dictation
     * completes.
     *
     * Off by default: in practice the automatic switch fights the user, who
     * usually wants to keep dictating or to press Enter right after a phrase
     * lands. See [AppSettings] for the per-user preference this gates.
     */
    const val RETURN_TO_PREVIOUS_KEYBOARD = false
}
