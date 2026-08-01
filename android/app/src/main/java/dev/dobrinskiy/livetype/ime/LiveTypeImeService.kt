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
import android.view.Gravity
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
import java.util.concurrent.Executors

class LiveTypeImeService : InputMethodService() {
    private enum class State {
        IDLE,
        CONNECTING,
        RECORDING,
        FINISHING,
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val tokenExecutor = Executors.newSingleThreadExecutor()
    private val tokenProvider = TokenProvider()

    private lateinit var statusText: TextView
    private lateinit var primaryButton: ImageButton
    private lateinit var enterButton: ImageButton
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
    @Volatile
    private var generation = 0

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KEYBOARD_BACKGROUND)
        }

        // Horizontal split: light-weight status and secondary actions on the
        // left, the two big thumb targets pinned to the right edge.
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

        // The recognised text is no longer mirrored here — it goes straight
        // into the editor. This line only carries status and errors.
        statusText = TextView(this).apply {
            setText(R.string.status_ready)
            textSize = 15f
            setTextColor(STATUS_COLOR)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        centreBlock.addView(statusText)
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
            setOnClickListener { cancelDictation(clearComposingText = true) }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                marginEnd = dp(6)
            }
        }
        settingsButton = Button(this).apply {
            setText(R.string.action_settings)
            isAllCaps = false
            setOnClickListener { openSettings() }
            layoutParams = LinearLayout.LayoutParams(0, dp(52), 1f)
        }
        actions.addView(cancelButton)
        actions.addView(settingsButton)
        leftColumn.addView(actions)
        content.addView(leftColumn)

        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                dp(THUMB_BUTTON_DP),
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        primaryButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_mic)
            setColorFilter(BUTTON_ICON_COLOR, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            contentDescription = getString(R.string.cd_start_dictation)
            setOnClickListener {
                when (state) {
                    State.IDLE -> startDictation()
                    State.RECORDING -> finishDictation()
                    State.CONNECTING, State.FINISHING -> Unit
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                dp(THUMB_BUTTON_DP),
                dp(THUMB_BUTTON_DP),
            ).apply { bottomMargin = dp(8) }
        }
        enterButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_enter)
            setColorFilter(BUTTON_ICON_COLOR, PorterDuff.Mode.SRC_IN)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(30), dp(30), dp(30), dp(30))
            contentDescription = getString(R.string.cd_newline)
            setOnClickListener { insertNewline() }
            layoutParams = LinearLayout.LayoutParams(
                dp(THUMB_BUTTON_DP),
                dp(THUMB_BUTTON_DP),
            )
        }
        rightColumn.addView(primaryButton)
        rightColumn.addView(enterButton)
        content.addView(rightColumn)
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
        cancelDictation(clearComposingText = false)

        if (info != null && isPasswordField(info)) {
            statusText.setText(R.string.status_password_field)
            primaryButton.isEnabled = false
        } else {
            statusText.setText(R.string.status_ready)
            primaryButton.isEnabled = true
        }
        refreshButtonAlpha()
    }

    override fun onFinishInput() {
        cancelDictation(clearComposingText = false)
        activeEditorInfo = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        cancelDictation(clearComposingText = false)
        tokenExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun startDictation() {
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

        val settings = AppSettings.load(this)
        if (!settings.isConfigured) {
            showError(getString(R.string.error_not_configured))
            return
        }

        generation += 1
        val thisGeneration = generation
        partialTranscript = StringBuilder()
        committedChars = 0
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
                        beginRecording(thisGeneration)
                    }
                }

                override fun onTranscriptDelta(itemId: String, delta: String) {
                    mainHandler.post {
                        if (thisGeneration != generation) return@post
                        partialTranscript.append(delta)
                        currentInputConnection?.setComposingText(pendingTranscript(), 1)
                    }
                }

                override fun onTranscriptCompleted(itemId: String, transcript: String) {
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

    private fun beginRecording(thisGeneration: Int) {
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
        generation += 1
        // After a mid-dictation Enter the head of the transcript is already in
        // the editor, so commit only what is still pending. The local delta
        // buffer — not the server transcript — is what the user actually saw.
        val finalText =
            if (committedChars > 0) pendingTranscript().trim() else transcript.trim()
        val recognised = finalText.isNotEmpty()
        if (recognised) {
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

    private fun cancelDictation(clearComposingText: Boolean) {
        generation += 1
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
            setState(State.IDLE, getString(R.string.status_ready))
            setConnectionStates(ConnectionState.IDLE, ConnectionState.IDLE)
        } else {
            state = State.IDLE
        }
    }

    private fun failSession(message: String) {
        Log.e(TAG, "Session failed: $message")
        generation += 1
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

    private fun setState(newState: State, status: String) {
        state = newState
        statusText.setTextColor(STATUS_COLOR)
        statusText.text = status
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
                primaryButton.setImageResource(R.drawable.ic_mic)
                primaryButton.contentDescription = getString(R.string.cd_connecting)
                primaryButton.isEnabled = false
                cancelButton.isEnabled = true
                settingsButton.isEnabled = false
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
        primaryButton.imageAlpha = if (primaryButton.isEnabled) 255 else 80
        enterButton.imageAlpha = if (enterButton.isEnabled) 255 else 80
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    /**
     * Bottom breathing room for the gesture pill / IME switcher. Some editors
     * report no navigation-bar inset to the IME window, so keep a floor under
     * it rather than trusting the inset alone.
     */
    private fun systemInsetPadding(navBarInsetPx: Int): Int =
        maxOf(navBarInsetPx, dp(24)) + dp(16)

    companion object {
        private const val TAG = "LiveTypeIme"

        /** Square thumb targets on the right edge. */
        private const val THUMB_BUTTON_DP = 104

        /** Status dots for the token worker and the OpenAI socket. */
        private const val INDICATOR_DP = 30
        private const val INDICATOR_ICON_DP = 18

        private val KEYBOARD_BACKGROUND = Color.parseColor("#E0EAEC")
        private val STATUS_COLOR = Color.rgb(35, 60, 60)
        private val ERROR_COLOR = Color.rgb(160, 40, 40)

        private val INDICATOR_IDLE = Color.parseColor("#9AAAB0")
        private val INDICATOR_LOADING = Color.parseColor("#33474D")
        private val INDICATOR_OK = Color.parseColor("#1E9E5A")
        private val INDICATOR_ERROR = Color.parseColor("#C0392B")
        private val BUTTON_ICON_COLOR = Color.parseColor("#1E2E33")
    }
}
