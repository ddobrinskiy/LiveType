package dev.dobrinskiy.livetype

import android.annotation.SuppressLint
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
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

@SuppressLint("SetTextI18n")
class MainActivity : Activity() {
    private lateinit var endpointInput: EditText
    private lateinit var secretInput: EditText
    private lateinit var languagesInput: EditText
    private lateinit var promptInput: EditText
    private lateinit var keywordsInput: EditText
    private lateinit var returnCheck: CheckBox
    private lateinit var permissionStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        populateSettings()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
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
    }

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
