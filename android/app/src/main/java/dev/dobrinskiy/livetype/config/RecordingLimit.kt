package dev.dobrinskiy.livetype.config

/**
 * How long one recording may run before the keyboard stops it by itself.
 *
 * This is a cost guard, not a feature. OpenAI bills per committed second, so a
 * dictation the user forgot to stop — text delivered, message sent, keyboard
 * left recording — is an unbounded charge for nothing. When the ceiling
 * elapses the phrase is finished exactly as a tap on the stop square finishes
 * it: the buffer is committed, the transcript arrives and is inserted. It is a
 * completion, never a cancellation, so nothing the user said is lost.
 *
 * Shaped like [EndpointMode]'s companion on purpose — a `from()` that rejects
 * an unusable stored value and a `default()` — but written as an object rather
 * than an enum: twenty constants would be twenty names for an integer, and the
 * dropdown wants the range anyway.
 */
object RecordingLimit {
    const val MIN_MINUTES = 1
    const val MAX_MINUTES = 20

    /**
     * Long enough for a paragraph of thinking out loud, short enough that an
     * abandoned recording costs cents rather than dollars.
     */
    const val DEFAULT_MINUTES = 3

    /** Every value the dropdown offers, in the order it shows them. */
    val OPTIONS: List<Int> = (MIN_MINUTES..MAX_MINUTES).toList()

    /**
     * A stored value outside the range falls back to [DEFAULT_MINUTES]: the
     * range can shrink between versions, and a hand-edited preference must not
     * be able to disable the guard by storing a day.
     */
    fun from(minutes: Int): Int =
        if (minutes in MIN_MINUTES..MAX_MINUTES) minutes else DEFAULT_MINUTES

    fun default(): Int = DEFAULT_MINUTES

    /** The ceiling as a `postDelayed` delay, clamped through [from] first. */
    fun millisFor(minutes: Int): Long = from(minutes) * 60_000L
}
