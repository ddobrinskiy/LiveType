package dev.dobrinskiy.livetype.ime

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration

/**
 * Press-and-hold auto-repeat for any view, with a second, faster gear.
 *
 * The view keeps its `OnClickListener`: a plain tap is still delivered as a
 * click (see [onTouch]), so the click path stays the single place that handles
 * a tap — TalkBack, `performClick()` and a finger tap all go through it and it
 * can never fire twice. Only a *hold* is handled here, and the first repeat
 * arrives after [initialDelayMs], long after the finger of a normal tap has
 * left the glass.
 *
 * The repeat is driven on the main thread and is stopped by every exit there
 * is: `ACTION_UP`, `ACTION_CANCEL`, the finger sliding off the button, the view
 * being detached, and a liveness check on each tick that catches the window
 * being taken away mid-hold (`onFinishInput` / `onDestroy` hide the IME window,
 * so `isShown` goes false). [cancel] removes the pending callback explicitly in
 * all of those cases; nothing is ever left queued.
 *
 * ### Timings
 *
 * The defaults are the ones a thumb is already trained on rather than invented
 * numbers:
 *
 * - [initialDelayMs] = 400 ms — AOSP LatinIME's `config_key_repeat_start_timeout`.
 *   It also sits under the platform's 500 ms long-press timeout
 *   ([ViewConfiguration.getLongPressTimeout]), so repeat starts before the hold
 *   would read as "stuck", while still being far longer than the ~100 ms a tap
 *   lasts.
 * - [intervalMs] = 50 ms — AOSP LatinIME's `config_key_repeat_interval`, i.e.
 *   20 characters per second. Roughly the middle of the desktop range as well
 *   (X11 defaults to 25 ms, macOS's default key-repeat is ~90 ms).
 * - [accelerateAfterMs] = 2000 ms — iOS switches a held delete key from
 *   characters to whole words after about two seconds. By then 400 ms + 32
 *   ticks have removed ~32 characters, which is about as much as anyone tracks
 *   character-by-character before they want bigger bites.
 * - [acceleratedIntervalMs] = 150 ms — a word averages ~5 characters, so a 3x
 *   longer tick still deletes text ~1.7x faster than the fine gear while
 *   leaving a reaction window: 150 ms is inside the ~200-250 ms it takes to see
 *   something and lift a finger, so overshoot stays around a single word.
 */
class HoldToRepeat private constructor(
    private val initialDelayMs: Long,
    private val intervalMs: Long,
    private val accelerateAfterMs: Long,
    private val acceleratedIntervalMs: Long,
    private val onRepeat: (Stage) -> Unit,
) : View.OnTouchListener {

    /** Which gear a repeat tick belongs to. */
    enum class Stage {
        /** The steady repeat that starts after the initial delay. */
        NORMAL,

        /** Reached once the hold passes `accelerateAfterMs`. */
        ACCELERATED,
    }

    private val handler = Handler(Looper.getMainLooper())

    /** The view currently under the finger, null whenever no hold is running. */
    private var target: View? = null

    /** False once the gesture has been abandoned (slid off, cancelled, stopped). */
    private var holding = false

    /** True once at least one repeat fired, which suppresses the tap click. */
    private var repeated = false

    private var downAtMs = 0L

    private val tick = object : Runnable {
        override fun run() {
            val view = target
            // The window can go away mid-hold without an ACTION_UP ever
            // arriving (the editor finishes input, the service is destroyed).
            if (view == null || !view.isAttachedToWindow || !view.isShown || !view.isEnabled) {
                cancel()
                return
            }
            val heldMs = SystemClock.uptimeMillis() - downAtMs
            val accelerated = heldMs >= accelerateAfterMs
            repeated = true
            onRepeat(if (accelerated) Stage.ACCELERATED else Stage.NORMAL)
            handler.postDelayed(this, if (accelerated) acceleratedIntervalMs else intervalMs)
        }
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!view.isEnabled) return false
                target = view
                holding = true
                repeated = false
                downAtMs = event.downTime
                view.isPressed = true
                handler.removeCallbacks(tick)
                handler.postDelayed(tick, initialDelayMs)
            }

            MotionEvent.ACTION_MOVE -> {
                // Sliding off the key ends the gesture for good; sliding back
                // on deliberately does not restart it, which is how the
                // platform treats a press that leaves a button's bounds.
                if (holding && !isInside(view, event)) {
                    view.isPressed = false
                    cancel()
                }
            }

            MotionEvent.ACTION_UP -> {
                val wasHolding = holding
                val didRepeat = repeated
                view.isPressed = false
                cancel()
                // A tap — nothing repeated — is delivered as a real click.
                // That keeps the OnClickListener the only code path for a
                // single activation (so no double action), and keeps
                // performClick() wired up for accessibility services.
                if (wasHolding && !didRepeat) view.performClick()
            }

            MotionEvent.ACTION_CANCEL -> {
                view.isPressed = false
                cancel()
            }

            else -> return false
        }
        return true
    }

    /** Stops any repeat in flight and removes the pending callback. */
    fun cancel() {
        holding = false
        target = null
        handler.removeCallbacks(tick)
    }

    private fun isInside(view: View, event: MotionEvent): Boolean {
        val slop = ViewConfiguration.get(view.context).scaledTouchSlop
        return event.x >= -slop &&
            event.y >= -slop &&
            event.x < view.width + slop &&
            event.y < view.height + slop
    }

    companion object {
        /** AOSP LatinIME `config_key_repeat_start_timeout`. */
        const val DEFAULT_INITIAL_DELAY_MS = 400L

        /** AOSP LatinIME `config_key_repeat_interval` — 20 repeats per second. */
        const val DEFAULT_INTERVAL_MS = 50L

        /** iOS switches a held delete key to whole words at about this point. */
        const val DEFAULT_ACCELERATE_AFTER_MS = 2000L

        /** Coarse gear: fewer, bigger edits, still inside a reaction window. */
        const val DEFAULT_ACCELERATED_INTERVAL_MS = 150L

        /**
         * Attaches auto-repeat to [view], leaving its click listener in place.
         *
         * Returns the listener so a caller that outlives the view can [cancel]
         * it by hand; that is optional — detaching the view stops it anyway.
         */
        fun attach(
            view: View,
            initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
            intervalMs: Long = DEFAULT_INTERVAL_MS,
            accelerateAfterMs: Long = DEFAULT_ACCELERATE_AFTER_MS,
            acceleratedIntervalMs: Long = DEFAULT_ACCELERATED_INTERVAL_MS,
            onRepeat: (Stage) -> Unit,
        ): HoldToRepeat {
            val listener = HoldToRepeat(
                initialDelayMs = initialDelayMs,
                intervalMs = intervalMs,
                accelerateAfterMs = accelerateAfterMs,
                acceleratedIntervalMs = acceleratedIntervalMs,
                onRepeat = onRepeat,
            )
            view.setOnTouchListener(listener)
            view.addOnAttachStateChangeListener(
                object : View.OnAttachStateChangeListener {
                    override fun onViewAttachedToWindow(v: View) = Unit

                    override fun onViewDetachedFromWindow(v: View) = listener.cancel()
                },
            )
            return listener
        }
    }
}
