package dev.dobrinskiy.livetype

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import dev.dobrinskiy.livetype.config.AppSettings
import dev.dobrinskiy.livetype.config.EndpointMode
import dev.dobrinskiy.livetype.config.FeatureFlags
import dev.dobrinskiy.livetype.config.LiveTypeSettings
import dev.dobrinskiy.livetype.config.RecordingLimit
import dev.dobrinskiy.livetype.config.isAllowedTokenEndpoint
import dev.dobrinskiy.livetype.network.UsageOutcome
import dev.dobrinskiy.livetype.network.UsageReporter
import dev.dobrinskiy.livetype.network.UsageSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.Executors

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var endpointInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var languagesInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var keywordsInput: EditText
    private lateinit var returnCheck: CheckBox
    private lateinit var permissionStatus: TextView

    /**
     * Present in every build, unlike [endpointModeSpinner]: the recording
     * ceiling is a cost guard for every user, not a development affordance.
     */
    private lateinit var recordingLimitSpinner: Spinner

    /** The ceiling currently shown, in minutes. See [RecordingLimit]. */
    private var maxRecordingMinutes: Int = RecordingLimit.default()

    /**
     * Null in release builds, which never show the dropdown and keep the
     * endpoint a plain typed field — see [EndpointMode.isSelectable].
     */
    private var endpointModeSpinner: Spinner? = null

    /** The mode currently shown. Release builds leave it at the default. */
    private var endpointMode: EndpointMode = EndpointMode.default()

    /**
     * The last URL typed while on [EndpointMode.CUSTOM]. Switching to a derived
     * mode overwrites the field, so the typed value is parked here and offered
     * back on the way in instead of being silently destroyed.
     *
     * Parked in `SharedPreferences` too, not just here: now that picking `DEV`
     * writes the dev URL over the stored endpoint straight away, memory alone
     * would lose the hand-typed URL the moment the Activity went away.
     */
    private var customEndpoint: String = ""

    /**
     * Raised while the spinner is being pointed at the stored mode, and lowered
     * by the callback that reports it.
     *
     * `Spinner` does not report its selection when it is set — it reports it at
     * the next layout pass, long after [onCreate] has returned, and by then a
     * programmatic selection is indistinguishable from a tap. Persisting that
     * callback would rewrite the endpoint every time the screen opened, which
     * is precisely the bug this screen already had in a different form.
     *
     * The flag alone is not the test: the callback also has to name the mode
     * that is already on screen. A tap never can, because re-picking the
     * selected row changes no selection and fires no callback. Requiring both
     * means a lost initial callback cannot swallow the user's first real
     * choice.
     */
    private var restoringEndpointMode: Boolean = false

    private lateinit var billingStatus: TextView
    private lateinit var billingPrice: TextView
    private lateinit var billingEstimated: TextView
    private lateinit var billingFooter: TextView
    private lateinit var billingToday: TextView
    private lateinit var billingLast7d: TextView
    private lateinit var billingLast30d: TextView

    private val usageReporter = UsageReporter()

    /**
     * Billing is loaded off the main thread on every [onResume]; the network
     * call must never run on it.
     */
    private val billingExecutor = Executors.newSingleThreadExecutor()

    /**
     * Only the newest request may write the table. Touched on the main thread
     * only, so a slow reply from a previous visit cannot overwrite a fresh one.
     */
    private var billingRequest = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        populateSettings()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
        refreshBilling()
    }

    override fun onDestroy() {
        billingExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildContent(): View {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(36))
        }

        content.addView(TextView(this).apply {
            text = "LiveType"
            textSize = 32f
            setTextColor(Color.rgb(20, 35, 35))
        })
        content.addView(TextView(this).apply {
            setText(R.string.app_tagline)
            textSize = 16f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(4), 0, dp(24))
        })

        permissionStatus = helperText()
        content.addView(permissionStatus)
        content.addView(primaryButton(getString(R.string.action_allow_mic)) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
        })
        content.addView(secondaryButton(getString(R.string.action_enable_livetype)) {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        content.addView(secondaryButton(getString(R.string.action_pick_keyboard)) {
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showInputMethodPicker()
        })

        content.addView(sectionTitle(getString(R.string.section_connection)))
        endpointInput = field(
            hint = getString(R.string.hint_endpoint),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
        )
        // Debug builds pick the worker from a list; release builds get the bare
        // field they have always had, with the URL typed by hand.
        if (EndpointMode.isSelectable) {
            content.addView(label(getString(R.string.label_endpoint_mode)))
            content.addView(buildEndpointModeSpinner())
            content.addView(noteText(getString(R.string.note_endpoint_mode)))
        }
        content.addView(label(getString(R.string.label_worker_endpoint)))
        content.addView(endpointInput)

        secretInput = field(
            hint = getString(R.string.hint_device_secret),
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        content.addView(label(getString(R.string.label_device_secret)))
        content.addView(secretInput)

        content.addView(sectionTitle(getString(R.string.section_recognition)))
        languagesInput = field(
            hint = getString(R.string.default_languages),
            inputType = InputType.TYPE_CLASS_TEXT,
        )
        content.addView(label(getString(R.string.label_languages)))
        content.addView(languagesInput)

        promptInput = multilineField(3)
        content.addView(label(getString(R.string.label_context)))
        content.addView(promptInput)

        keywordsInput = multilineField(6)
        content.addView(label(getString(R.string.label_keywords)))
        content.addView(keywordsInput)

        // The cost guard: a recording nobody stopped is billed until something
        // stops it. Shown in release too — see recordingLimitSpinner.
        content.addView(label(getString(R.string.label_max_recording)))
        content.addView(buildRecordingLimitSpinner())
        content.addView(noteText(getString(R.string.note_max_recording)))

        returnCheck = CheckBox(this).apply {
            setText(R.string.checkbox_return_keyboard)
            isChecked = true
            setPadding(0, dp(8), 0, dp(16))
            // Hidden while the feature is flagged off, so the UI never offers
            // a toggle that changes nothing.
            visibility =
                if (FeatureFlags.RETURN_TO_PREVIOUS_KEYBOARD) View.VISIBLE else View.GONE
        }
        content.addView(returnCheck)

        content.addView(primaryButton(getString(R.string.action_save_settings)) {
            saveSettings()
        })

        content.addView(sectionTitle(getString(R.string.section_billing)))
        content.addView(buildBillingSection())

        content.addView(TextView(this).apply {
            setText(R.string.privacy_note)
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, dp(20), 0, 0)
        })

        return ScrollView(this).apply {
            addView(content)
        }
    }

    private fun buildEndpointModeSpinner(): Spinner {
        val modeAdapter = EndpointModeAdapter()
        val spinner = Spinner(this).apply {
            adapter = modeAdapter
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    val mode = modeAdapter.getItem(position) ?: return
                    // The adapter refuses to enable an unavailable row, but
                    // isEnabled only governs taps: a selection set in code —
                    // or restored by the framework — walks straight past it.
                    // Bounce back to the mode already in force.
                    //
                    // This check used to be the only thing standing between the
                    // spinner's own initial callback and the endpoint field,
                    // and it worked purely because PROD sat at position 0 and
                    // was unavailable. PROD is deployed now, so it stops
                    // nothing; see restoringEndpointMode for the real guard.
                    if (!mode.isAvailable) {
                        setSelection(modeAdapter.getPosition(endpointMode))
                        return
                    }
                    // A callback that arrives during the restore and names the
                    // mode already on screen is the spinner echoing
                    // populateSettings, not a choice. Nothing is written for it.
                    val restoring = restoringEndpointMode && mode == endpointMode
                    restoringEndpointMode = false
                    selectEndpointMode(mode, persist = !restoring)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        endpointModeSpinner = spinner
        return spinner
    }

    /**
     * The recording ceiling, 1 to 20 minutes.
     *
     * A plain `ArrayAdapter<String>` of pre-formatted labels rather than an
     * adapter over the numbers: every row is selectable — unlike
     * [EndpointModeAdapter], nothing here can be unavailable — so the position
     * indexes straight into [RecordingLimit.OPTIONS] and the only work left is
     * the plural.
     */
    private fun buildRecordingLimitSpinner(): Spinner {
        val labels = RecordingLimit.OPTIONS.map {
            resources.getQuantityString(R.plurals.recording_limit_minutes, it, it)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, labels)
                .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long,
                ) {
                    maxRecordingMinutes =
                        RecordingLimit.OPTIONS.getOrElse(position) { RecordingLimit.default() }
                }

                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            }
        }
        recordingLimitSpinner = spinner
        return spinner
    }

    /**
     * Applies a mode to the endpoint field. A mode with an implied URL owns the
     * field — the URL follows from the choice, so it is filled in and locked
     * rather than typed. CUSTOM hands the field back, restoring the parked URL
     * if there is one and otherwise keeping whatever is on screen.
     *
     * With [persist] the choice is written to `SharedPreferences` there and
     * then, endpoint included, so leaving the screen without pressing Save no
     * longer discards it. What is stored is exactly what the field ends up
     * showing — including, on CUSTOM, a URL carried over from the mode being
     * left, because that is the URL the user is looking at and the one they are
     * about to edit. Keystrokes after that are a draft and stay one: they reach
     * storage through Save, which validates them, or through the parking below
     * when the user moves to another mode.
     */
    private fun selectEndpointMode(mode: EndpointMode, persist: Boolean) {
        if (endpointMode == EndpointMode.CUSTOM && mode != EndpointMode.CUSTOM) {
            customEndpoint = endpointInput.text.toString().trim()
        }
        endpointMode = mode
        val derived = EndpointMode.endpointFor(mode)
        when {
            derived != null -> endpointInput.setText(derived)
            customEndpoint.isNotBlank() -> endpointInput.setText(customEndpoint)
        }
        endpointInput.isEnabled = derived == null
        // The old complaint belonged to the old URL.
        endpointInput.error = null
        if (persist) {
            AppSettings.saveEndpointSelection(
                context = this,
                mode = mode,
                endpoint = derived ?: endpointInput.text.toString().trim(),
                customEndpoint = customEndpoint,
            )
        }
    }

    private fun populateSettings() {
        val settings = AppSettings.load(this)
        endpointInput.setText(settings.tokenEndpoint)
        secretInput.setText(settings.deviceSecret)
        languagesInput.setText(settings.languages.joinToString(","))
        promptInput.setText(settings.prompt)
        keywordsInput.setText(settings.keywords.joinToString("\n"))
        returnCheck.isChecked = settings.returnToPreviousKeyboard

        // AppSettings already clamped the stored value into the offered range,
        // so indexOf finds it; the fallback only covers a range that shrinks.
        maxRecordingMinutes = settings.maxRecordingMinutes
        recordingLimitSpinner.setSelection(
            RecordingLimit.OPTIONS.indexOf(settings.maxRecordingMinutes)
                .takeIf { it >= 0 }
                ?: RecordingLimit.OPTIONS.indexOf(RecordingLimit.default()),
        )

        val spinner = endpointModeSpinner
        if (spinner != null) {
            // Assign before setSelection: the listener treats a change away
            // from CUSTOM as "park what is in the field", and at this point the
            // field holds the stored endpoint, not something the user typed.
            endpointMode = settings.endpointMode
            // The fallback covers installs that predate the stored custom URL:
            // back then the only record of it was the endpoint itself, and only
            // while CUSTOM was the stored mode.
            customEndpoint = settings.customEndpoint.ifBlank {
                if (settings.endpointMode == EndpointMode.CUSTOM) settings.tokenEndpoint else ""
            }
            restoringEndpointMode = true
            spinner.setSelection(EndpointMode.entries.indexOf(settings.endpointMode))
            // The stored endpoint can still disagree with the stored mode — a
            // mode stored before the baked URL changed, say, or before the two
            // were written together. The mode wins, and because the screen now
            // shows a URL that is not the stored one, the reconciliation is
            // written back rather than left for a Save that may never come.
            val derived = EndpointMode.endpointFor(settings.endpointMode)
            selectEndpointMode(
                settings.endpointMode,
                persist = derived != null && derived != settings.tokenEndpoint,
            )
        }
    }

    private fun saveSettings() {
        // A derived mode is the authority on its URL, so what gets stored can
        // never drift from the mode stored beside it. CUSTOM takes the field.
        val endpoint = EndpointMode.endpointFor(endpointMode)
            ?: endpointInput.text.toString().trim()
        val secret = secretInput.text.toString()
        val languages = languagesInput.text
            .toString()
            .split(',')
            .map(String::trim)
            .filter(String::isNotBlank)

        // Release builds reject http:// outright; debug builds keep it for the
        // local wrangler dev loop. See isAllowedTokenEndpoint.
        if (!isAllowedTokenEndpoint(endpoint)) {
            endpointInput.error = getString(R.string.error_need_url)
            return
        }
        if (secret.length < 16) {
            secretInput.error = getString(R.string.error_secret_short)
            return
        }
        if (languages.isEmpty()) {
            languagesInput.error = getString(R.string.error_need_language)
            return
        }

        // Committed before the write, so the blob below carries the same three
        // endpoint keys saveEndpointSelection would have written. The two paths
        // must not be able to leave a different picture behind.
        if (endpointMode == EndpointMode.CUSTOM) {
            customEndpoint = endpoint
        }
        AppSettings.save(
            this,
            LiveTypeSettings(
                tokenEndpoint = endpoint,
                deviceSecret = secret,
                languages = languages,
                prompt = promptInput.text.toString(),
                keywords = keywordsInput.text
                    .lineSequence()
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .toList(),
                returnToPreviousKeyboard = returnCheck.isChecked,
                endpointMode = endpointMode,
                customEndpoint = customEndpoint,
                maxRecordingMinutes = maxRecordingMinutes,
            ),
        )
        Toast.makeText(this, R.string.toast_settings_saved, Toast.LENGTH_SHORT).show()
        // The endpoint or the secret may have just been fixed; the section
        // below is showing the old verdict until it is asked again.
        refreshBilling()
    }

    /**
     * Price per minute plus today / 7 days / 30 days.
     *
     * Every figure in here is rendered exactly as the worker sends it. The app
     * holds no price table, converts nothing and decides nothing about money —
     * see ARCHITECTURE.md §3.8.
     */
    private fun buildBillingSection(): View {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        billingPrice = TextView(this).apply {
            setText(R.string.billing_price_unknown)
            textSize = 15f
            setTextColor(Color.rgb(20, 35, 35))
            setPadding(0, dp(2), 0, dp(6))
        }
        section.addView(billingPrice)

        // Only shown when the worker admits the price is an estimate, so the
        // user never reads a fabricated precision as fact.
        billingEstimated = TextView(this).apply {
            setText(R.string.billing_price_estimated)
            textSize = 13f
            setTextColor(Color.rgb(150, 90, 20))
            setPadding(0, 0, 0, dp(6))
            visibility = View.GONE
        }
        section.addView(billingEstimated)

        // Loading, "not set up yet" and each failure land here. It stays
        // visible until real numbers replace it: a blank table would read as
        // "you have spent nothing".
        billingStatus = TextView(this).apply {
            setText(R.string.billing_loading)
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(2), 0, dp(6))
        }
        section.addView(billingStatus)

        billingToday = billingRow(section, getString(R.string.billing_window_today))
        billingLast7d = billingRow(section, getString(R.string.billing_window_7d))
        billingLast30d = billingRow(section, getString(R.string.billing_window_30d))

        billingFooter = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        section.addView(billingFooter)

        section.addView(TextView(this).apply {
            setText(R.string.billing_note)
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, dp(6), 0, 0)
        })

        return section
    }

    /** One `label … amount` line of the three-row table; returns the amount view. */
    private fun billingRow(container: LinearLayout, labelText: String): TextView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }
        row.addView(TextView(this).apply {
            text = labelText
            textSize = 15f
            setTextColor(Color.DKGRAY)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f,
            )
        })
        val amount = TextView(this).apply {
            setText(R.string.billing_unknown)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.END
            setTextColor(Color.rgb(20, 35, 35))
        }
        row.addView(amount)
        container.addView(row)
        return amount
    }

    private fun refreshBilling() {
        if (!::billingStatus.isInitialized) return
        val settings = AppSettings.load(this)
        if (!settings.isConfigured) {
            // Distinct from a failure: nothing is wrong, there is simply
            // nowhere to ask yet.
            showBillingProblem(getString(R.string.billing_not_configured), failure = false)
            return
        }

        showBillingProblem(getString(R.string.billing_loading), failure = false)
        billingRequest += 1
        val request = billingRequest
        runCatching {
            billingExecutor.execute {
                val outcome = usageReporter.fetchSummary(settings)
                runOnUiThread {
                    if (request != billingRequest || isFinishing || isDestroyed) {
                        return@runOnUiThread
                    }
                    renderBilling(outcome)
                }
            }
        }.onFailure {
            showBillingProblem(getString(R.string.billing_error_network), failure = true)
        }
    }

    private fun renderBilling(outcome: UsageOutcome) {
        when (outcome) {
            is UsageOutcome.Loaded -> showBillingSummary(outcome.summary)

            UsageOutcome.Unauthorized ->
                showBillingProblem(
                    getString(R.string.billing_error_unauthorized),
                    failure = true,
                )

            UsageOutcome.Unreachable ->
                showBillingProblem(getString(R.string.billing_error_network), failure = true)

            UsageOutcome.Malformed ->
                showBillingProblem(getString(R.string.billing_error_malformed), failure = true)

            // Covers the current real state: 500 "Worker is misconfigured"
            // while D1 is not provisioned. The worker's own wording is shown
            // rather than a guess, and the table stays visibly unknown.
            is UsageOutcome.WorkerError -> showBillingProblem(
                if (outcome.detail.isNotBlank()) {
                    getString(R.string.billing_error_worker, outcome.detail)
                } else {
                    getString(R.string.billing_error_http, outcome.status)
                },
                failure = true,
            )
        }
    }

    private fun showBillingSummary(summary: UsageSummary) {
        billingStatus.visibility = View.GONE
        // The model comes from the worker and can change without an app
        // rebuild, so it is displayed, never assumed.
        billingPrice.text = getString(
            R.string.billing_price,
            summary.model,
            MoneyFormat.usd(summary.price.usdMicrosPerMinute),
        )
        billingEstimated.visibility =
            if (summary.price.estimated) View.VISIBLE else View.GONE
        billingToday.text = MoneyFormat.usd(summary.today.usdMicros)
        billingLast7d.text = MoneyFormat.usd(summary.last7d.usdMicros)
        billingLast30d.text = MoneyFormat.usd(summary.last30d.usdMicros)
        billingFooter.visibility = View.VISIBLE
        billingFooter.text =
            getString(R.string.billing_source, summary.source, formatAsOf(summary.asOf))
    }

    /**
     * Anything short of real numbers: the amounts go back to the unknown
     * placeholder so "we could not find out" never masquerades as "zero".
     */
    private fun showBillingProblem(message: String, failure: Boolean) {
        billingStatus.visibility = View.VISIBLE
        billingStatus.text = message
        billingStatus.setTextColor(if (failure) Color.rgb(170, 45, 45) else Color.DKGRAY)
        billingPrice.setText(R.string.billing_price_unknown)
        billingEstimated.visibility = View.GONE
        billingFooter.visibility = View.GONE
        billingToday.setText(R.string.billing_unknown)
        billingLast7d.setText(R.string.billing_unknown)
        billingLast30d.setText(R.string.billing_unknown)
    }

    /** The worker's ISO timestamp, shown in the phone's own zone and format. */
    private fun formatAsOf(asOf: String): String = runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.parse(asOf))
    }.getOrDefault(asOf)

    private fun updatePermissionStatus() {
        val granted = checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        permissionStatus.setText(
            if (granted) R.string.permission_granted else R.string.permission_missing,
        )
        permissionStatus.setTextColor(if (granted) Color.rgb(0, 105, 92) else Color.rgb(170, 45, 45))
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_MICROPHONE) {
            updatePermissionStatus()
        }
    }

    /**
     * Lists every mode, including ones that cannot be chosen yet. PROD is shown
     * greyed out and labelled with the reason rather than hidden: that tells the
     * reader the plan without pretending the worker is there. `isEnabled` is
     * what actually blocks the tap; the colour only explains it.
     */
    private inner class EndpointModeAdapter : ArrayAdapter<EndpointMode>(
        this,
        android.R.layout.simple_spinner_item,
        EndpointMode.entries.toList(),
    ) {
        init {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

        override fun isEnabled(position: Int): Boolean =
            getItem(position)?.isAvailable ?: false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            super.getView(position, convertView, parent).also {
                (it as? TextView)?.text = labelOf(position)
            }

        override fun getDropDownView(
            position: Int,
            convertView: View?,
            parent: ViewGroup,
        ): View = super.getDropDownView(position, convertView, parent).also { view ->
            val available = isEnabled(position)
            (view as? TextView)?.apply {
                text = labelOf(position)
                isEnabled = available
                setTextColor(if (available) Color.rgb(20, 35, 35) else Color.GRAY)
            }
        }

        private fun labelOf(position: Int): CharSequence {
            val mode = getItem(position) ?: return ""
            val name = getString(
                when (mode) {
                    EndpointMode.PROD -> R.string.endpoint_mode_prod
                    EndpointMode.DEV -> R.string.endpoint_mode_dev
                    EndpointMode.CUSTOM -> R.string.endpoint_mode_custom
                },
            )
            return if (mode.isAvailable) {
                name
            } else {
                getString(R.string.endpoint_mode_not_deployed, name)
            }
        }
    }

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(10), 0, dp(5))
    }

    /** Small grey caption under a control, explaining why it is there. */
    private fun noteText(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(Color.GRAY)
        setPadding(0, dp(6), 0, 0)
    }

    private fun helperText() = TextView(this).apply {
        textSize = 14f
        setPadding(0, 0, 0, dp(8))
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 21f
        setTextColor(Color.rgb(20, 35, 35))
        setPadding(0, dp(28), 0, dp(6))
    }

    private fun field(hint: String, inputType: Int) = EditText(this).apply {
        this.hint = hint
        this.inputType = inputType
        setSingleLine(true)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun multilineField(lines: Int) = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        gravity = Gravity.TOP
        minLines = lines
        maxLines = lines + 3
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )
    }

    private fun primaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = buttonLayoutParams()
    }

    private fun secondaryButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = buttonLayoutParams()
    }

    private fun buttonLayoutParams() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(52),
    ).apply {
        bottomMargin = dp(8)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_MICROPHONE = 100
    }
}
