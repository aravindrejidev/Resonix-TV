package com.resonix.tv.ui.network

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout

/**
 * A small system dialog with a text box. On a TV this is the most dependable way to type:
 * the dialog is its own window, so the system keyboard (or the remote's D-pad letter picker)
 * works as in every other TV app, and ResonixTV's own key handling does not get in the way.
 * OK / the keyboard's Done key saves, BACK cancels.
 */
fun showTextPrompt(
    context: Context,
    title: String,
    initial: String,
    secret: Boolean,
    placeholder: String,
    onResult: (String) -> Unit,
) {
    val density = context.resources.displayMetrics.density
    fun px(dp: Int): Int = (dp * density).toInt()

    val input = EditText(context)
    input.setSingleLine(true)
    input.setText(initial)
    input.hint = placeholder
    input.imeOptions = EditorInfo.IME_ACTION_DONE
    input.inputType = if (secret) {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    } else {
        InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
    input.setSelection(input.text.length)

    val box = LinearLayout(context)
    box.orientation = LinearLayout.VERTICAL
    box.setPadding(px(24), px(8), px(24), 0)
    box.addView(
        input,
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    if (secret) {
        // Typing a password with a remote is easy to get wrong: let the person look at it.
        val show = CheckBox(context)
        show.text = "Show what I type"
        show.setOnCheckedChangeListener { _, checked ->
            input.transformationMethod = if (checked) {
                HideReturnsTransformationMethod.getInstance()
            } else {
                PasswordTransformationMethod.getInstance()
            }
            input.setSelection(input.text.length)
        }
        box.addView(show)
    }

    val dialog = AlertDialog.Builder(context)
        .setTitle(title)
        .setView(box)
        .setPositiveButton("OK") { _, _ -> onResult(input.text.toString()) }
        .setNegativeButton("Cancel", null)
        .create()
    input.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
            true
        } else {
            false
        }
    }
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    dialog.show()
}
