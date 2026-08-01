package dev.dobrinskiy.livetype.ime

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Typeface
import android.util.Log
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import dev.dobrinskiy.livetype.MainActivity
import dev.dobrinskiy.livetype.R
import dev.dobrinskiy.livetype.audio.PcmAudioRecorder
import dev.dobrinskiy.livetype.config.AppSettings
import dev.dobrinskiy.livetype.config.FeatureFlags
import dev.dobrinskiy.livetype.config.LiveTypeSettings
import dev.dobrinskiy.livetype.network.RealtimeTranscriber
import dev.dobrinskiy.livetype.network.TokenProvider
import dev.dobrinskiy.livetype.network.UsageReporter
import org.json.JSONObject
import java.util.concurrent.Executors

class LiveTypeImeService : InputMethodService() {
    private enum class State {
        /** Nothing open: no token, no socket. */
        IDLE,

        /** Fetching a token or opening the socket. */
        CONNECTING,

        /** Socket is up and idling — waiting for the mic tap. */
        READY,

        RECORDING,
        FINISHING,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenExecutor = Executors.newSingleThreadExecutor()
    private val tokenProvider = TokenProvider()
    private val usageReporter = UsageReporter()

    private lateinit var statusText: TextView
    private lateinit var warningIcon: ImageView
    private lateinit var primaryButton: ImageButton
    private lateinit var enterButton: ImageButton
    private lateinit var backspaceButton: ImageButton
    private lateinit var keyboardButton: ImageButton
    private lateinit var pasteButton: ImageButton
    private lateinit var cancelButton: Button
    private lateinit var settingsButton: Button
    private lateinit var serverIndicator: Indicator
    private lateinit var openAiIndicator: Indicator

    private var state = State.IDLE
    private var activeEditorInfo: EditorInfo? = null
    private var transcriber: RealtimeTranscriber? = null
    private var recorder: PcmAudioRecorder? = null
    private var partialTranscript = StringBuilder()
    /** Chars of [partialTranscript] already committed by a mid-dictation Enter. */
    private var committedChars = 0

    /**
     * Last recognised phrase, kept in memory for five minutes so the Paste
     * button can recover a transcript that was committed nowhere — see
     * [RecentPhrase] for why it is memory-only. Declared after [mainHandler]
     * because it borrows it; released in [onDestroy].
     */
    private val recentPhrase = RecentPhrase(mainHandler) {
        // Availability changed: the button has to stop looking tappable.
        if (::pasteButton.isInitialized) refreshButtonAlpha()
    }

    /**
     * Set when the user asks to record before the socket is up: either the mic
     * tap itself opened the session, or it landed mid-[State.CONNECTING] on a
     * prewarmed one. Either way [State.READY] is skipped and `onReady` starts
     * recording straight away, so no tap is ever swallowed.
     */
    private var autoStartOnReady = false

    @Volatile
    private var generation = 0

    /**
     * Trailing-edge debounce for [prewarm]. Every [onStartInputView] reschedules
     * it, so a burst of re-focus events collapses into a single connection
     * attempt instead of one per event.
     */
    private val prewarmRunnable = Runnable { prewarm() }

    /**
     * Armed by [onFinishInput], disarmed by the next [onStartInputView]. This
     * is what lets a warm socket survive brief focus churn.
     */
    private val graceTeardownRunnable = Runnable { tearDownIdleSession("grace-expired") }

    /**
     * Hard ceiling on a warm-but-unused session, armed in [openSession] and
     * dropped in [beginRecording].
     */
    private val warmCeilingRunnable = Runnable {
        // The user already tapped the mic and is waiting on this very socket;
        // closing it here would swallow that tap. Let the connect finish or
        // fail on its own.
        if (autoStartOnReady) return@Runnable
        tearDownIdleSession("idle-ceiling")
    }

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KEYBOARD_BACKGROUND)
        }

        // Horizontal split: light-weight status and secondary actions on the
        // left, the grid of big thumb targets pinned to the right edge.
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        val leftColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.MATCH_PARENT,
                1f,
            ).apply { marginEnd = dp(10) }
        }

        // Indicators sit above the status line, both vertically centred in
        // the free space left of the thumb buttons.
        val centreBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        val indicators = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(INDICATOR_DP),
            ).apply { bottomMargin = dp(10) }
        }
        serverIndicator = createIndicator(
            R.drawable.ic_server,
            R.string.indicator_server,
        )
        openAiIndicator = createIndicator(
            R.drawable.ic_openai,
            R.string.indicator_openai,
        )
        (serverIndicator.container.layoutParams as LinearLayout.LayoutParams)
            .marginEnd = dp(14)
        indicators.addView(serverIndicator.container)
        indicators.addView(openAiIndicator.container)
        centreBlock.addView(indicators)

        // The alarm icon shares a row with the status text and toggles between
        // VISIBLE and INVISIBLE — never GONE. It therefore holds its column
        // whether or not it is showing, so the text keeps the same wrapping
        // width and the line does not jump every time the mic is taken away
        // and handed back.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        warningIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_warning)
            setColorFilter(ERROR_COLOR, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            visibility = View.INVISIBLE
            // The status text already says what is wrong; announcing the icon
            // too would just repeat it.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = LinearLayout.LayoutParams(
                dp(STATUS_ICON_DP),
                dp(STATUS_ICON_DP),
            ).apply {
                marginEnd = dp(6)
                // Sits level with the first line when the status wraps.
                topMargin = dp(1)
            }
        }

        // The recognised text is no longer mirrored here — it goes straight
        // into the editor. This line only carries status and errors.
        statusText = TextView(this).apply {
            // Honest default: nothing is connected until onStartInputView
            // prewarms and the socket reports ready.
            setText(R.string.status_not_connected)
            textSize = 15f
            setTextColor(STATUS_COLOR)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        }
        statusRow.addView(warningIcon)
        statusRow.addView(statusText)
        centreBlock.addView(statusRow)
        leftColumn.addView(centreBlock)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52),
            )
        }
        cancelButton = Button(this).apply {
            setText(R.string.action_cancel)
            isAllCaps = false
            isEnabled = false
            fitLabel()
            setOnClickListener {
                cancelDictation(clearComposingText = true, reason = "user-cancelled")
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(6)
            }
        }
        settingsButton = Button(this).apply {
            setText(R.string.action_settings)
            isAllCaps = false
            fitLabel()
            setOnClickListener { openSettings() }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        }
        actions.addView(cancelButton)
        actions.addView(settingsButton)
        leftColumn.addView(actions)
        content.addView(leftColumn)

        // Thumb grid:  [paste] [keyboard] [mic]
        //                      [backspace] [enter]
        //
        // Paste joins the top row on the left, as asked; the bottom row keeps
        // two buttons and is pinned to the END so backspace and Enter stay
        // directly under keyboard and mic — the thumb finds them where it
        // always did. See THUMB_BUTTON_DP for the width arithmetic that forced
        // the squares down from 88dp to 72dp.
        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        val topRow = thumbRow().apply {
            (layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(THUMB_GAP_DP)
        }
        val bottomRow = thumbRow().apply {
            (layoutParams as LinearLayout.LayoutParams).gravity = Gravity.END
        }

        keyboardButton = createThumbButton(
            R.drawable.ic_keyboard,
            R.string.cd_previous_keyboard,
        ) {
            // Leaving the keyboard mid-session would strand the socket and the
            // composing region, so tear the session down first. No grace here:
            // the user is deliberately walking away from LiveType.
            mainHandler.removeCallbacks(prewarmRunnable)
            cancelDictation(clearComposingText = false, reason = "keyboard-switched")
            switchToPreviousInputMethod()
        }
        primaryButton = createThumbButton(R.drawable.ic_mic, R.string.cd_start_dictation) {
            onMicTapped()
        }
        backspaceButton = createThumbButton(R.drawable.ic_backspace, R.string.cd_backspace) {
            // A key event, not deleteSurroundingText: it is the only variant
            // that behaves correctly while a composing region is live.
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        // Hold to repeat, escalating to whole words. The click listener above
        // stays in place and remains the only path a single tap takes.
        HoldToRepeat.attach(backspaceButton) { stage ->
            when (stage) {
                HoldToRepeat.Stage.NORMAL -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                HoldToRepeat.Stage.ACCELERATED -> deleteWordBackwards()
            }
        }
        enterButton = createThumbButton(R.drawable.ic_enter, R.string.cd_newline) {
            insertNewline()
        }
        pasteButton = createThumbButton(R.drawable.ic_paste, R.string.cd_paste) {
            pasteRecentPhrase()
        }

        (pasteButton.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(THUMB_GAP_DP)
        (keyboardButton.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(THUMB_GAP_DP)
        (backspaceButton.layoutParams as LinearLayout.LayoutParams).marginEnd = dp(THUMB_GAP_DP)
        topRow.addView(pasteButton)
        topRow.addView(keyboardButton)
        topRow.addView(primaryButton)
        bottomRow.addView(backspaceButton)
        bottomRow.addView(enterButton)
        rightColumn.addView(topRow)
        rightColumn.addView(bottomRow)
        content.addView(rightColumn)
        // Paste starts inert unless a phrase is already being held — the input
        // view can be rebuilt mid-session, and setState is not guaranteed to
        // run before the first frame.
        refreshButtonAlpha()
        root.addView(content)

        // No coloured strip: the blue background already gives the system
        // glyphs (⌄ and the globe) enough contrast. Just reserve the space so
        // the action row does not sit under them.
        root.setPadding(0, 0, 0, systemInsetPadding(0))
        root.setOnApplyWindowInsetsListener { view, insets ->
            val navBar = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            } else {
                @Suppress("DEPRECATION")
                insets.systemWindowInsetBottom
            }
            view.setPadding(0, 0, 0, systemInsetPadding(navBar))
            insets
        }

        return root
    }

    /** One row of the thumb grid. */
    private fun thumbRow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun createThumbButton(
        iconRes: Int,
        contentDescriptionRes: Int,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(this).apply {
        setImageResource(iconRes)
        setColorFilter(BUTTON_ICON_COLOR, PorterDuff.Mode.SRC_IN)
        scaleType = ImageView.ScaleType.FIT_CENTER
        setPadding(
            dp(THUMB_ICON_PADDING_DP),
            dp(THUMB_ICON_PADDING_DP),
            dp(THUMB_ICON_PADDING_DP),
            dp(THUMB_ICON_PADDING_DP),
        )
        contentDescription = getString(contentDescriptionRes)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            dp(THUMB_BUTTON_DP),
            dp(THUMB_BUTTON_DP),
        )
    }

    override fun onWindowShown() {
        super.onWindowShown()
        applyNavigationBarAppearance()
    }

    /**
     * Match the navigation bar to the keyboard and ask the system to draw the
     * IME switcher glyphs (⌄ and the globe) dark instead of light.
     *
     * Note: the platform only exposes a light/dark switch here, not an exact
     * colour, so the glyphs land on the system's dark tone rather than an
     * arbitrary hex.
     */
    private fun applyNavigationBarAppearance() {
        val imeWindow = window?.window ?: return
        imeWindow.navigationBarColor = KEYBOARD_BACKGROUND
        if (Build.VERSION.SDK_INT < 30) return
        imeWindow.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        activeEditorInfo = info

        // Whatever happens below, the previous field's grace timer must not
        // fire and close a socket we are about to hand to a new field.
        mainHandler.removeCallbacks(graceTeardownRunnable)

        if (info != null && isPasswordField(info)) {
            // Never hold a live socket over a password field, and never let a
            // queued prewarm open one behind our back.
            mainHandler.removeCallbacks(prewarmRunnable)
            cancelDictation(clearComposingText = false, reason = "password-field")
            setState(State.IDLE, getString(R.string.status_password_field))
            return
        }
        if (state == State.IDLE) {
            // The previous field may have left the mic disabled.
            primaryButton.isEnabled = true
            refreshButtonAlpha()
        }
        // Deliberately no cancelDictation() here: this fires on every restart
        // of the same field, and tearing the session down would throw away the
        // socket we are trying to keep warm. onFinishInput owns the teardown.
        if (state != State.IDLE || transcriber != null) {
            // A session survived the churn (or the grace timer we just
            // cancelled). Reuse it as it stands: no token, no socket, and no
            // generation bump — see the note in [openSession].
            Log.i(TAG, "Session reused (state=$state)")
            if (state == State.READY) {
                // The input view may have been rebuilt underneath us, in which
                // case its status line still claims we are not connected.
                setState(State.READY, getString(R.string.status_ready))
                setConnectionStates(ConnectionState.OK, ConnectionState.OK)
                if (autoStartOnReady) {
                    // A mic tap that landed mid-connect and whose field went
                    // away before the socket was up. The field is back, so
                    // honour it now instead of dropping it.
                    autoStartOnReady = false
                    startDictation()
                }
            }
            return
        }
        schedulePrewarm()
    }

    override fun onFinishInput() {
        // A prewarm queued for a field that has already gone away would open a
        // session nobody asked for.
        mainHandler.removeCallbacks(prewarmRunnable)
        when (state) {
            // Nothing open: nothing to hold and nothing to close.
            State.IDLE -> Unit

            // Mid-dictation the editor is gone, so there is nowhere left to
            // deliver the text. End it now rather than hold the mic open.
            State.RECORDING, State.FINISHING ->
                cancelDictation(clearComposingText = false, reason = "input-finished-mid-dictation")

            // The expensive part — the token and the open socket — is exactly
            // what the next field will need. Hold it briefly instead of paying
            // for it again; [graceTeardownRunnable] closes it if no field comes.
            State.CONNECTING, State.READY -> {
                Log.i(TAG, "Input finished; holding warm session for ${TEARDOWN_GRACE_MS}ms")
                mainHandler.postDelayed(graceTeardownRunnable, TEARDOWN_GRACE_MS)
            }
        }
        activeEditorInfo = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        cancelDictation(clearComposingText = false, reason = "service-destroyed")
        // Explicitly, before the blanket removal below: this both drops the
        // pending five-minute expiry callback and releases the transcript, so
        // no recognised text survives the service even by a few milliseconds.
        recentPhrase.release()
        // cancelDictation drops the session timers; this also catches the
        // delayed keyboard switch posted by completeSession. Nothing of ours
        // may outlive the service.
        mainHandler.removeCallbacksAndMessages(null)
        tokenExecutor.shutdownNow()
        super.onDestroy()
    }

    /**
     * Debounced entry point for [prewarm]. `onStartInputView` fires in bursts —
     * a messenger re-focuses its editor on every tap, scroll and keyboard
     * animation — and connecting on the leading edge of each one costs a real
     * (billed) Realtime session per event. Connecting on the trailing edge of
     * the burst costs one.
     */
    private fun schedulePrewarm() {
        mainHandler.removeCallbacks(prewarmRunnable)
        mainHandler.postDelayed(prewarmRunnable, PREWARM_DEBOUNCE_MS)
    }

    /**
     * Opens the token + socket session shortly after the keyboard appears, so
     * the mic tap only has to start the recorder. Silent on success and cheap
     * to call: anything but a cold [State.IDLE] with no live transcriber is
     * left alone rather than duplicated.
     */
    private fun prewarm() {
        if (state != State.IDLE || transcriber != null) return
        val editorInfo = activeEditorInfo ?: return
        if (isPasswordField(editorInfo)) return

        val settings = AppSettings.load(this)
        if (!settings.isConfigured) {
            showError(getString(R.string.error_not_configured))
            return
        }
        autoStartOnReady = false
        openSession(settings, reason = "prewarm")
    }

    /**
     * Closes a warm-but-unused session and returns cleanly to [State.IDLE], so
     * the next mic tap simply reconnects. Deliberately not a [failSession]:
     * nothing failed, so there is no error status and no red text.
     */
    private fun tearDownIdleSession(reason: String) {
        // Only ever touches a session nobody is using; recording and finishing
        // own themselves.
        if (state != State.CONNECTING && state != State.READY) return
        cancelDictation(clearComposingText = false, reason = reason)
    }

    private fun onMicTapped() {
        when (state) {
            State.RECORDING -> finishDictation()
            State.FINISHING -> Unit
            State.IDLE, State.CONNECTING, State.READY -> startDictation()
        }
    }

    private fun startDictation() {
        // A tap outranks the debounce timer. Drop the queued prewarm so this
        // tap connects now instead of waiting for it — and so it cannot fire a
        // second session behind the one we are about to open.
        mainHandler.removeCallbacks(prewarmRunnable)

        val editorInfo = activeEditorInfo
        if (editorInfo == null || isPasswordField(editorInfo)) {
            showError(getString(R.string.error_field_unavailable))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            showError(getString(R.string.error_no_mic_permission))
            settingsButton.isEnabled = true
            return
        }

        // Prewarmed socket already up: skip straight to the microphone.
        if (state == State.READY && transcriber != null) {
            beginRecording(generation)
            return
        }
        // Tapped mid-connect. Do not restart the session — just remember that
        // the user wants to record the moment it is ready.
        if (state == State.CONNECTING) {
            autoStartOnReady = true
            return
        }

        val settings = AppSettings.load(this)
        if (!settings.isConfigured) {
            showError(getString(R.string.error_not_configured))
            return
        }
        autoStartOnReady = true
        openSession(settings, reason = "mic-tap")
    }

    /**
     * Fetch a token, then open the realtime socket. Shared by both entry points.
     *
     * This is the only place a [RealtimeTranscriber] is created, and therefore
     * the only place [generation] is bumped on the way up: the bump belongs to
     * the lifetime of the transcriber object, whose listener captures the value
     * at construction. Teardown bumps it again to orphan those callbacks.
     * Reusing a live session creates and destroys nothing, so it must not bump
     * — doing so would leave the socket open but every one of its callbacks
     * silently discarded.
     */
    private fun openSession(settings: LiveTypeSettings, reason: String) {
        Log.i(TAG, "Session opening ($reason)")
        generation += 1
        val thisGeneration = generation
        partialTranscript = StringBuilder()
        committedChars = 0
        // Realtime sessions expire server-side anyway; do not hold an unused
        // one open indefinitely. Dropped again as soon as recording starts.
        mainHandler.removeCallbacks(warmCeilingRunnable)
        mainHandler.postDelayed(warmCeilingRunnable, WARM_SESSION_MAX_MS)
        setState(State.CONNECTING, getString(R.string.status_getting_key))
        setConnectionStates(ConnectionState.LOADING, ConnectionState.IDLE)

        tokenExecutor.execute {
            val result = tokenProvider.fetch(settings)
            mainHandler.post {
                if (thisGeneration != generation || state != State.CONNECTING) return@post
                result.fold(
                    onSuccess = {
                        setIndicator(serverIndicator, ConnectionState.OK)
                        connectRealtime(it, settings, thisGeneration)
                    },
                    onFailure = {
                        setIndicator(serverIndicator, ConnectionState.ERROR)
                        failSession(it.message ?: getString(R.string.error_token_failed))
                    },
                )
            }
        }
    }

    private fun connectRealtime(
        clientSecret: String,
        settings: LiveTypeSettings,
        thisGeneration: Int,
    ) {
        val realtime = RealtimeTranscriber(
            object : RealtimeTranscriber.Listener {
                override fun onReady() {
                    mainHandler.post {
                        if (thisGeneration != generation || state != State.CONNECTING) return@post
                        setIndicator(openAiIndicator, ConnectionState.OK)
                        if (autoStartOnReady && activeEditorInfo != null) {
                            autoStartOnReady = false
                            beginRecording(thisGeneration)
                        } else {
                            // Prewarmed: socket is up, wait for the mic tap.
                            // Or: the field went away mid-connect and we are
                            // inside the grace window, in which case the tap
                            // stays pending rather than opening the microphone
                            // with nowhere to type. onStartInputView honours it
                            // if the field comes back.
                            setState(State.READY, getString(R.string.status_ready))
                        }
                    }
                }

                override fun onTranscriptDelta(itemId: String, delta: String) {
                    mainHandler.post {
                        if (thisGeneration != generation) return@post
                        partialTranscript.append(delta)
                        currentInputConnection?.setComposingText(pendingTranscript(), 1)
                    }
                }

                override fun onTranscriptCompleted(
                    itemId: String,
                    transcript: String,
                    usage: JSONObject?,
                ) {
                    // Deliberately outside the generation check and outside the
                    // main-thread hop: OpenAI billed for this audio whether or
                    // not these callbacks still belong to the live session, and
                    // metering must not wait on the UI.
                    reportUsage(settings, itemId, usage)
                    mainHandler.post {
                        if (thisGeneration != generation) return@post
                        completeSession(transcript, settings.returnToPreviousKeyboard)
                    }
                }

                override fun onError(message: String) {
                    mainHandler.post {
                        if (thisGeneration != generation) return@post
                        setIndicator(openAiIndicator, ConnectionState.ERROR)
                        failSession(message)
                    }
                }

                override fun onClosed() {
                    mainHandler.post {
                        if (thisGeneration == generation && state != State.IDLE) {
                            setIndicator(openAiIndicator, ConnectionState.ERROR)
                            failSession(getString(R.string.error_openai_closed))
                        }
                    }
                }
            },
        )
        transcriber = realtime
        realtime.connect(clientSecret)
        setState(State.CONNECTING, getString(R.string.status_connecting_openai))
    }

    /**
     * Hands the billable quantity OpenAI reported to the worker, which prices
     * it and keeps the ledger.
     *
     * Fire-and-forget on [tokenExecutor], never awaited and never surfaced: a
     * failed metering call is logged and dropped. Losing a usage row is
     * acceptable; breaking a dictation because a metering call failed is not.
     * That includes the executor already being shut down under us.
     */
    private fun reportUsage(settings: LiveTypeSettings, itemId: String, usage: JSONObject?) {
        if (usage == null) {
            Log.w(TAG, "Transcription completed without usage; nothing to meter")
            return
        }
        runCatching { tokenExecutor.execute { usageReporter.report(settings, itemId, usage) } }
            .onFailure { Log.w(TAG, "Usage report not queued: ${it.message}") }
    }

    private fun beginRecording(thisGeneration: Int) {
        // The session is in use now: the idle ceiling no longer applies.
        mainHandler.removeCallbacks(warmCeilingRunnable)
        val audioRecorder = PcmAudioRecorder(
            context = this,
            onAudio = { bytes ->
                if (thisGeneration == generation) {
                    transcriber?.appendAudio(bytes)
                }
            },
            onError = { message ->
                mainHandler.post {
                    if (thisGeneration == generation) failSession(message)
                }
            },
            // A microphone lost to another app is temporary and recoverable, so
            // it must never reach failSession: the socket stays up and the
            // transcript so far is kept. Only the status line changes, and it
            // changes back on its own the moment the microphone returns.
            onSilencedChanged = { silenced ->
                mainHandler.post {
                    if (thisGeneration != generation || state != State.RECORDING) return@post
                    setState(
                        State.RECORDING,
                        getString(
                            if (silenced) {
                                R.string.status_mic_in_use
                            } else {
                                R.string.status_listening
                            },
                        ),
                        warning = silenced,
                    )
                }
            },
        )
        recorder = audioRecorder

        audioRecorder.start().fold(
            onSuccess = {
                setState(State.RECORDING, getString(R.string.status_listening))
            },
            onFailure = { failSession(it.message ?: getString(R.string.error_mic_start)) },
        )
    }

    private fun finishDictation() {
        if (state != State.RECORDING) return
        recorder?.stop()
        recorder = null
        setState(State.FINISHING, getString(R.string.status_finishing))
        if (transcriber?.commit() != true) {
            failSession(getString(R.string.error_closed_before_commit))
        }
    }

    private fun completeSession(transcript: String, returnToPrevious: Boolean) {
        Log.i(TAG, "Session torn down (transcript-completed) from $state")
        // The socket closes below, so neither session timer applies any more.
        mainHandler.removeCallbacks(graceTeardownRunnable)
        mainHandler.removeCallbacks(warmCeilingRunnable)
        generation += 1
        autoStartOnReady = false
        // After a mid-dictation Enter the head of the transcript is already in
        // the editor, so commit only what is still pending. The local delta
        // buffer — not the server transcript — is what the user actually saw.
        val finalText =
            if (committedChars > 0) pendingTranscript().trim() else transcript.trim()
        val recognised = finalText.isNotEmpty()
        if (recognised) {
            // Remember before committing, not after: the commit below is
            // exactly the step that silently does nothing when no editor has
            // focus, and this is the copy that survives that.
            recentPhrase.remember(finalText)
            currentInputConnection?.commitText(finalText, 1)
        } else {
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
        }

        recorder?.stop()
        recorder = null
        transcriber?.close()
        transcriber = null
        partialTranscript = StringBuilder()
        committedChars = 0
        // setState writes the status line, so pick the wording here rather
        // than setting it above where setState would overwrite it.
        setState(
            State.IDLE,
            getString(if (recognised) R.string.status_done else R.string.status_no_speech),
        )
        setConnectionStates(ConnectionState.IDLE, ConnectionState.IDLE)

        // The flag gates the stored preference: while it is off the switch
        // never happens, whatever the user saved earlier.
        if (FeatureFlags.RETURN_TO_PREVIOUS_KEYBOARD && returnToPrevious) {
            mainHandler.postDelayed({ switchToPreviousInputMethod() }, 250)
        }
    }

    /**
     * The single teardown path: closes whatever is open and returns to
     * [State.IDLE]. [reason] is logged so a reconnect can be told apart from a
     * reuse in the logs alone.
     */
    private fun cancelDictation(clearComposingText: Boolean, reason: String) {
        // No timer may outlive the session it was armed for.
        mainHandler.removeCallbacks(graceTeardownRunnable)
        mainHandler.removeCallbacks(warmCeilingRunnable)
        if (transcriber != null || state != State.IDLE) {
            Log.i(TAG, "Session torn down ($reason) from $state")
        }
        generation += 1
        autoStartOnReady = false
        recorder?.stop()
        recorder = null
        transcriber?.clear()
        transcriber?.close()
        transcriber = null

        if (clearComposingText && partialTranscript.isNotEmpty()) {
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
        }
        partialTranscript = StringBuilder()
        committedChars = 0
        if (::statusText.isInitialized) {
            // The socket is gone, so the status must not claim readiness.
            setState(State.IDLE, getString(R.string.status_not_connected))
            setConnectionStates(ConnectionState.IDLE, ConnectionState.IDLE)
        } else {
            state = State.IDLE
        }
    }

    private fun failSession(message: String) {
        Log.e(TAG, "Session torn down (failed from $state): $message")
        mainHandler.removeCallbacks(graceTeardownRunnable)
        mainHandler.removeCallbacks(warmCeilingRunnable)
        generation += 1
        autoStartOnReady = false
        recorder?.stop()
        recorder = null
        transcriber?.close()
        transcriber = null

        // Preserve any already visible partial transcript instead of losing the user's words.
        currentInputConnection?.finishComposingText()
        partialTranscript = StringBuilder()
        committedChars = 0
        setState(State.IDLE, getString(R.string.status_error))
        statusText.text = message
        statusText.setTextColor(ERROR_COLOR)
    }

    private fun showError(message: String) {
        Log.e(TAG, "Config error: $message")
        statusText.setText(R.string.status_setup_needed)
        statusText.text = message
        statusText.setTextColor(ERROR_COLOR)
    }

    /**
     * The single writer of the status line — text, colour and alarm icon alike.
     *
     * @param warning marks the status as a live problem the user should act on:
     *   red text plus the alarm icon, which no other state shows. The session
     *   itself stays up — a failure goes through [failSession] instead — and
     *   the next plain [setState] clears both again.
     */
    private fun setState(newState: State, status: String, warning: Boolean = false) {
        state = newState
        statusText.setTextColor(if (warning) ERROR_COLOR else STATUS_COLOR)
        statusText.text = status
        warningIcon.visibility = if (warning) View.VISIBLE else View.INVISIBLE
        when (newState) {
            State.IDLE -> {
                primaryButton.setImageResource(R.drawable.ic_mic)
                primaryButton.contentDescription = getString(R.string.cd_start_dictation)
                primaryButton.isEnabled = activeEditorInfo?.let { !isPasswordField(it) } ?: false
                cancelButton.isEnabled = false
                settingsButton.isEnabled = true
                enterButton.isEnabled = true
            }

            State.CONNECTING -> {
                // Stays tappable: a tap here is remembered by autoStartOnReady
                // instead of being swallowed while the socket comes up.
                primaryButton.setImageResource(R.drawable.ic_mic)
                primaryButton.contentDescription = getString(R.string.cd_connecting)
                primaryButton.isEnabled = true
                cancelButton.isEnabled = true
                settingsButton.isEnabled = false
            }

            State.READY -> {
                primaryButton.setImageResource(R.drawable.ic_mic)
                primaryButton.contentDescription = getString(R.string.cd_start_dictation)
                primaryButton.isEnabled = true
                cancelButton.isEnabled = false
                settingsButton.isEnabled = true
                enterButton.isEnabled = true
            }

            State.RECORDING -> {
                primaryButton.setImageResource(R.drawable.ic_stop)
                primaryButton.contentDescription = getString(R.string.cd_stop_dictation)
                primaryButton.isEnabled = true
                cancelButton.isEnabled = true
                settingsButton.isEnabled = false
            }

            State.FINISHING -> {
                primaryButton.setImageResource(R.drawable.ic_mic)
                primaryButton.contentDescription = getString(R.string.cd_transcribing)
                primaryButton.isEnabled = false
                cancelButton.isEnabled = true
                settingsButton.isEnabled = false
            }
        }
        refreshButtonAlpha()
    }

    /** Transcript not yet pushed into the editor. */
    private fun pendingTranscript(): String =
        partialTranscript.substring(committedChars.coerceIn(0, partialTranscript.length))

    /** ImageButton does not fade its drawable on its own. */
    private fun refreshButtonAlpha() {
        // Paste is the one thumb button whose availability is data-driven
        // rather than state-driven: it is inert until something has been
        // recognised, and inert again the moment that phrase expires. Deciding
        // it here keeps the enabled flag and the faded look in one place.
        pasteButton.isEnabled = recentPhrase.isAvailable
        // Backspace and the keyboard switch are never disabled, but they go
        // through the same path so the grid stays visually uniform.
        for (button in listOf(
            primaryButton,
            enterButton,
            backspaceButton,
            keyboardButton,
            pasteButton,
        )) {
            button.imageAlpha = if (button.isEnabled) 255 else 80
        }
    }

    private fun openSettings() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val type = info.inputType
        val inputClass = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION

        return when (inputClass) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            )

            InputType.TYPE_CLASS_NUMBER ->
                variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD

            else -> false
        }
    }

    /**
     * A tinted glyph with a spinner ring drawn over it while connecting and a
     * red "!" badge whenever the connection is not up. Tapping it reports the
     * current state.
     */
    private class Indicator(
        val container: FrameLayout,
        val icon: ImageView,
        val spinner: ProgressBar,
        val badge: TextView,
        val labelRes: Int,
    ) {
        var connection: ConnectionState = ConnectionState.IDLE
    }

    private enum class ConnectionState { IDLE, LOADING, OK, ERROR }

    private fun createIndicator(iconRes: Int, labelRes: Int): Indicator {
        val icon = ImageView(this).apply {
            setImageResource(iconRes)
            contentDescription = getString(labelRes)
            layoutParams = FrameLayout.LayoutParams(
                dp(INDICATOR_ICON_DP),
                dp(INDICATOR_ICON_DP),
                Gravity.CENTER,
            )
        }
        val spinner = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = View.GONE
            indeterminateDrawable?.setColorFilter(
                INDICATOR_LOADING,
                PorterDuff.Mode.SRC_IN,
            )
            layoutParams = FrameLayout.LayoutParams(
                dp(INDICATOR_DP),
                dp(INDICATOR_DP),
                Gravity.CENTER,
            )
        }
        val badge = TextView(this).apply {
            text = "!"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(INDICATOR_ERROR)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END,
            )
        }
        val container = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(INDICATOR_DP), dp(INDICATOR_DP))
            isClickable = true
            isFocusable = true
            addView(icon)
            addView(spinner)
            addView(badge)
        }
        val indicator = Indicator(container, icon, spinner, badge, labelRes)
        container.setOnClickListener { showConnectionStatus(indicator) }
        setIndicator(indicator, ConnectionState.IDLE)
        return indicator
    }

    private fun setIndicator(indicator: Indicator, connection: ConnectionState) {
        indicator.connection = connection
        // "Not connected yet" and "failed" both read as a problem to the user,
        // so both get the red treatment plus the badge.
        val faulty =
            connection == ConnectionState.IDLE || connection == ConnectionState.ERROR
        val tint = when (connection) {
            ConnectionState.LOADING -> INDICATOR_LOADING
            ConnectionState.OK -> INDICATOR_OK
            else -> INDICATOR_ERROR
        }
        indicator.icon.setColorFilter(tint, PorterDuff.Mode.SRC_IN)
        indicator.spinner.visibility =
            if (connection == ConnectionState.LOADING) View.VISIBLE else View.GONE
        indicator.badge.visibility = if (faulty) View.VISIBLE else View.GONE
    }

    private fun showConnectionStatus(indicator: Indicator) {
        val statusRes = when (indicator.connection) {
            ConnectionState.OK -> R.string.connection_ok
            ConnectionState.LOADING -> R.string.connection_loading
            else -> R.string.connection_error
        }
        Toast.makeText(
            this,
            getString(R.string.connection_toast, getString(indicator.labelRes), getString(statusRes)),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun setConnectionStates(server: ConnectionState, openAi: ConnectionState) {
        if (!::serverIndicator.isInitialized) return
        setIndicator(serverIndicator, server)
        setIndicator(openAiIndicator, openAi)
    }

    /**
     * Inserts a plain line break. Deliberately not a KEYCODE_ENTER key event:
     * in single-line fields that would fire the editor action (send / search)
     * instead of breaking the line.
     */
    private fun insertNewline() {
        val connection = currentInputConnection ?: return
        // Mid-dictation the transcript lives in a composing region. Freeze what
        // has arrived so far and remember how much was committed, so the
        // remaining deltas render as a fresh region and the final commit does
        // not duplicate the frozen part.
        connection.finishComposingText()
        if (state == State.RECORDING || state == State.CONNECTING || state == State.FINISHING) {
            committedChars = partialTranscript.length
        }
        connection.commitText("\n", 1)
    }

    /**
     * Inserts the last recognised phrase, the recovery path for a transcript
     * that was committed nowhere because no editor had focus at the time.
     *
     * The composing region is handled exactly as [insertNewline] handles it:
     * pasting mid-dictation freezes what has arrived and marks it committed,
     * so the final commit appends only the remainder instead of duplicating
     * the frozen text.
     *
     * The phrase is **not** consumed — the first target may have been the
     * wrong one, and re-dictating is the failure this whole feature exists to
     * avoid. It goes away on its own five minutes after it was recognised.
     */
    private fun pasteRecentPhrase() {
        // Guard rather than trust the enabled flag: the expiry timer could
        // land between the touch and this callback.
        val phrase = recentPhrase.peek()
        if (phrase.isNullOrEmpty()) {
            refreshButtonAlpha()
            return
        }
        val connection = currentInputConnection ?: return
        connection.finishComposingText()
        if (state == State.RECORDING || state == State.CONNECTING || state == State.FINISHING) {
            committedChars = partialTranscript.length
        }
        connection.commitText(phrase, 1)
    }

    /**
     * Deletes the whole word before the cursor — the coarse gear of the held
     * backspace. Falls back to a single character whenever [WordDelete] cannot
     * measure a word (no connection, empty field, start of the text, or a live
     * selection, which the key event deletes correctly on its own).
     *
     * The composing region is handled exactly as [insertNewline] handles it:
     * `deleteSurroundingText` is not defined over a composing region, so the
     * region is frozen first, and the frozen part is marked as committed so the
     * final commit at the end of dictation does not paste it in a second time.
     */
    private fun deleteWordBackwards() {
        val connection = currentInputConnection ?: return
        connection.finishComposingText()
        if (state == State.RECORDING || state == State.CONNECTING || state == State.FINISHING) {
            committedChars = partialTranscript.length
        }
        if (!WordDelete.beforeCursor(connection)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Makes a left-column action label survive the narrower column that Paste
     * cost it — see [THUMB_BUTTON_DP] for the arithmetic.
     *
     * Cancel and Settings now share 141dp, so each gets ~67dp. The stock
     * button padding alone is 32dp of that, and Russian "Настройки" needs
     * ~65dp at 14sp: without this it wraps to two lines or ellipsises. Trimming
     * the horizontal padding and letting the label scale down (never up — 14sp
     * stays the ceiling, so English is unchanged) keeps both labels on one
     * readable line. Autosizing is API 26; minSdk is 28.
     */
    private fun Button.fitLabel() {
        maxLines = 1
        setPadding(dp(4), 0, dp(4), 0)
        setAutoSizeTextTypeUniformWithConfiguration(
            ACTION_TEXT_MIN_SP,
            ACTION_TEXT_MAX_SP,
            1,
            TypedValue.COMPLEX_UNIT_SP,
        )
    }

    /**
     * Bottom breathing room for the gesture pill / IME switcher. Some editors
     * report no navigation-bar inset to the IME window, so keep a floor under
     * it rather than trusting the inset alone.
     */
    private fun systemInsetPadding(navBarInsetPx: Int): Int =
        maxOf(navBarInsetPx, dp(24)) + dp(16)

    companion object {
        private const val TAG = "LiveTypeIme"

        /**
         * Trailing-edge debounce before a prewarm connects.
         *
         * Measured on the device, a messenger fired `onStartInputView` twice
         * inside the same second while the keyboard animated in, and each one
         * honestly opened a billed Realtime session. Waiting out the burst
         * collapses it into one. The cost is bounded: the socket still comes up
         * long before a thumb reaches the mic, and a mic tap cancels the timer
         * and connects immediately rather than waiting for it.
         */
        private const val PREWARM_DEBOUNCE_MS = 400L

        /**
         * How long a warm session outlives `onFinishInput`.
         *
         * The measured churn re-focused the editor 2s and then 5s apart, so
         * this absorbs that pattern with margin while still guaranteeing that a
         * genuinely abandoned session — keyboard dismissed, app switched — is
         * closed within eight seconds rather than billed for the whole time the
         * user is away.
         */
        private const val TEARDOWN_GRACE_MS = 8_000L

        /**
         * Hard ceiling on a warm session that is never used.
         *
         * Applies while the keyboard stays visible, where no `onFinishInput`
         * ever arrives to arm the grace timer. Realtime sessions expire
         * server-side anyway, so holding one past a few minutes buys nothing:
         * somebody who has stared at the keyboard for five minutes without
         * tapping the mic is not about to, and reconnecting costs a second.
         */
        private const val WARM_SESSION_MAX_MS = 5 * 60_000L

        /**
         * Square thumb targets on the right edge:
         *
         * ```
         * [paste] [keyboard] [mic]
         *         [backspace] [enter]
         * ```
         *
         * **Why 72dp and not the 88dp this grid used to be.** Width budget on a
         * 411dp screen (the reference phone): 411 − 14 − 14 of content padding
         * = 383dp usable, minus the 10dp gap to the left column = 373dp to
         * share between the status column and the widest grid row.
         *
         * | squares | widest row (3 wide) | left column |
         * |---|---|---|
         * | 88dp | 3×88 + 2×8 = 280 | **93dp — does not fit** |
         * | 80dp | 3×80 + 2×8 = 256 | 117dp — status ~12 chars/line |
         * | **72dp** | 3×72 + 2×8 = **232** | **141dp** |
         * | 64dp | 3×64 + 2×8 = 208 | 165dp, but at the accessibility floor |
         *
         * At 72dp the left column keeps 141dp, which covers:
         * - both indicators: 30 + 14 + 30 = 74dp, with room to spare;
         * - the status line: 141 − 18 (alarm icon) − 6 (its margin) = 117dp,
         *   about 15 characters per line at 15sp, so the longest string —
         *   Russian `status_mic_in_use`, 59 chars — wraps to four lines and
         *   still fits inside the 152dp the grid is tall;
         * - Cancel and Settings at (141 − 6) / 2 ≈ 67dp each, which needs the
         *   label autosizing in `fitLabel()` but no wrapping.
         *
         * 72dp is 1.5× Material's 48dp minimum touch target and above the ~64dp
         * floor for a primary control, so the thumb loses nothing that matters.
         * The grid also gets shorter — 72 + 8 + 72 = 152dp against the old
         * 184dp — so the keyboard does not grow to pay for the new button.
         */
        private const val THUMB_BUTTON_DP = 72
        private const val THUMB_GAP_DP = 8

        /** Keeps the glyph the same fraction of the square as before (25/88). */
        private const val THUMB_ICON_PADDING_DP = 20

        /**
         * Autosize range for the Cancel / Settings labels. 14sp is the ceiling
         * so nothing grows past the size they had; 10sp is a floor that is
         * never actually reached by either language at ~67dp.
         */
        private const val ACTION_TEXT_MIN_SP = 10
        private const val ACTION_TEXT_MAX_SP = 14

        /** Status dots for the token worker and the OpenAI socket. */
        private const val INDICATOR_DP = 30
        private const val INDICATOR_ICON_DP = 18

        /** Alarm glyph beside the status line; sized against the 15sp text. */
        private const val STATUS_ICON_DP = 18

        private val KEYBOARD_BACKGROUND = Color.parseColor("#E0EAEC")
        private val STATUS_COLOR = Color.rgb(35, 60, 60)

        /**
         * The one red for status-line text: failures and the recoverable
         * microphone warning alike. INDICATOR_ERROR stays a glyph colour — on
         * this background it only reaches 4.4:1 against the 4.5:1 that 15sp
         * body text needs, where this red measures 6.1:1.
         */
        private val ERROR_COLOR = Color.rgb(160, 40, 40)

        private val INDICATOR_IDLE = Color.parseColor("#9AAAB0")
        private val INDICATOR_LOADING = Color.parseColor("#33474D")
        private val INDICATOR_OK = Color.parseColor("#1E9E5A")
        private val INDICATOR_ERROR = Color.parseColor("#C0392B")
        private val BUTTON_ICON_COLOR = Color.parseColor("#1E2E33")
    }
}
