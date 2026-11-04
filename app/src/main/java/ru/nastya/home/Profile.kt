package ru.nastya.home

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Профиль устройства: имя для приветствия. Хранится только на этом устройстве. */
object Profile {
    private const val PREFS = "profile"
    private const val KEY_NAME = "name"
    private const val KEY_ASKED = "asked"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun name(ctx: Context): String? =
        prefs(ctx).getString(KEY_NAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun setName(ctx: Context, name: String?) {
        val v = name?.trim()?.takeIf { it.isNotEmpty() }
        prefs(ctx).edit().apply { if (v == null) remove(KEY_NAME) else putString(KEY_NAME, v) }.apply()
    }

    fun shouldAsk(ctx: Context): Boolean = name(ctx) == null && !prefs(ctx).getBoolean(KEY_ASKED, false)

    fun markAsked(ctx: Context) {
        prefs(ctx).edit().putBoolean(KEY_ASKED, true).apply()
    }

    /** Диалог ввода имени. onDone(имя) после «Готово»; onDone(null) — «Позже», «Отмена» или «Назад» (имя не меняется). */
    fun showNameDialog(ctx: Context, onDone: (String?) -> Unit) {
        val current = name(ctx)
        val d = Dialog(ctx, R.style.Theme_NastyaHome_Dialog)
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        var finished = false
        fun finish(result: String?, save: Boolean) {
            if (finished) return
            finished = true
            if (save) setName(ctx, result)
            markAsked(ctx)
            d.dismiss()
            onDone(result)
        }

        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(14))
        }
        column.addView(TextView(ctx).apply {
            text = ctx.getString(if (current != null) R.string.profile_title_change else R.string.profile_title)
            textSize = 22f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(ctx.getColor(R.color.cream))
        })
        column.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.profile_hint)
            textSize = 14f
            setTextColor(ctx.getColor(R.color.peach))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4)
        })

        val input = EditText(ctx).apply {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS or InputType.TYPE_TEXT_VARIATION_PERSON_NAME
            imeOptions = EditorInfo.IME_ACTION_DONE
            hint = ctx.getString(R.string.profile_name_hint)
            setHintTextColor(ctx.getColor(R.color.peach))
            setTextColor(ctx.getColor(R.color.cream))
            textSize = 20f
            isFocusable = true
            isFocusableInTouchMode = true
            setText(current.orEmpty())
            setSelection(text.length)
        }
        column.addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(14)
        })

        fun button(label: String, onClick: () -> Unit) = Button(ctx, null, 0, R.style.HomeButton).apply {
            text = label
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { onClick() }
        }
        val done = button(ctx.getString(R.string.profile_done)) { finish(input.text.toString(), save = true) }
        val later = button(ctx.getString(if (current != null) R.string.profile_cancel else R.string.profile_later)) {
            finish(null, save = false)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        row.addView(later)
        row.addView(done, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(10)
        })
        column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(16)
        })

        done.id = android.view.View.generateViewId()
        later.id = android.view.View.generateViewId()
        input.nextFocusDownId = later.id
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { done.performClick(); true } else false
        }

        d.setContentView(column)
        d.setOnCancelListener { finish(null, save = false) }
        d.window?.setLayout(dp(460), ViewGroup.LayoutParams.WRAP_CONTENT)
        d.setOnShowListener { input.requestFocus() }
        d.show()
    }
}
