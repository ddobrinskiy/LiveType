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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.dobrinskiy.livetype.config.AppSettings
import dev.dobrinskiy.livetype.config.FeatureFlags
import dev.dobrinskiy.livetype.config.LiveTypeSettings
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

    private fun populateSettings() {
        val settings = AppSettings.load(this)
        endpointInput.setText(settings.tokenEndpoint)
        secretInput.setText(settings.deviceSecret)
        languagesInput.setText(settings.languages.joinToString(","))
        promptInput.setText(settings.prompt)
        keywordsInput.setText(settings.keywords.joinToString("\n"))
        returnCheck.isChecked = settings.returnToPreviousKeyboard
    }

    private fun saveSettings() {
        val endpoint = endpointInput.text.toString().trim()
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

    private fun label(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.DKGRAY)
        setPadding(0, dp(10), 0, dp(5))
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
