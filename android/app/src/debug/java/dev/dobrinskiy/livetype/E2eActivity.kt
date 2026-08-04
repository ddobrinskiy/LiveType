package dev.dobrinskiy.livetype

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A debug-only host field for the headless emulator runner. It gives the real
 * IME a focused editor without depending on a third-party app being installed
 * in the emulator image.
 */
class E2eActivity : Activity() {
    private lateinit var editor: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(48, 64, 48, 32)
            setBackgroundColor(Color.WHITE)
        }
        root.addView(TextView(this).apply {
            text = "LiveType Android simulator QA"
            textSize = 24f
            setTextColor(Color.rgb(20, 35, 35))
        })
        root.addView(TextView(this).apply {
            text = "The field below is the real IME host. Tap the microphone after the keyboard is visible."
            textSize = 15f
            setTextColor(Color.DKGRAY)
            setPadding(0, 16, 0, 20)
        })
        editor = EditText(this).apply {
            hint = "Transcript appears here"
            contentDescription = "E2E transcript field"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            gravity = Gravity.TOP
            setTextSize(18f)
            setPadding(20, 20, 20, 20)
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        root.addView(editor, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            260,
        ))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        editor.requestFocus()
        window.decorView.postDelayed({
            val manager = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            manager.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
        }, 500)
    }
}
