package ru.nastya.home

import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup

/**
 * Верхние вкладки: [chips] — фокусируемые чипы, [pages] — страницы (видна одна), [entries] — с чего начинается фокус на странице.
 *
 * Страница переключается по фокусу чипа (как на Google TV) и по OK. Стрелка вниз с чипа уходит в [entries] страницы,
 * стрелка вверх из верхнего ряда страницы ([handleUp]) возвращается на чип текущей вкладки, а не на соседнюю.
 */
class Tabs(
    private val chips: List<View>,
    private val pages: List<View>,
    private val entries: List<View>,
) {
    var selected = 0
        private set

    /** Пока переключаем страницы, смена фокуса чипов не должна запускать новое переключение. */
    private var switching = false

    init {
        chips.forEachIndexed { i, chip ->
            chip.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && !switching) select(i) }
            chip.setOnClickListener { select(i) }
            chip.setOnKeyListener { _, keyCode, event -> onChipKey(i, keyCode, event) }
        }
        select(0)
    }

    /** Показать страницу [i]. Если фокус был на скрываемой странице, он сначала переходит на чип [i]. */
    fun select(i: Int) {
        switching = true
        try {
            val focused = pages.getOrNull(selected)?.takeIf { it.visibility == View.VISIBLE }?.findFocus()
            pages[i].visibility = View.VISIBLE
            if (focused != null && i != selected) chips[i].requestFocus()
            pages.forEachIndexed { j, p -> if (j != i) p.visibility = View.GONE }
            chips.forEachIndexed { j, c ->
                c.isSelected = j == i
                c.alpha = if (j == i) 1f else 0.7f
            }
            selected = i
        } finally {
            switching = false
        }
    }

    /** Перейти на вкладку и поставить фокус на её чип. */
    fun selectAndFocusChip(i: Int) {
        select(i)
        chips[i].requestFocus()
    }

    /** Перейти на вкладку и поставить фокус внутрь страницы. */
    fun selectAndFocusContent(i: Int) {
        select(i)
        focusEntry(i)
    }

    private fun focusEntry(i: Int) {
        if (!entries[i].requestFocus()) pages[i].requestFocus()
    }

    private fun onChipKey(i: Int, keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) focusEntry(i)
                return true
            }
            // Вверх и за края ряда фокус не уходит с чипов.
            KeyEvent.KEYCODE_DPAD_UP -> return true
            KeyEvent.KEYCODE_DPAD_LEFT -> return i == 0
            KeyEvent.KEYCODE_DPAD_RIGHT -> return i == chips.lastIndex
        }
        return false
    }

    /** Нажата «вверх» при фокусе [focused]: если выше на странице ничего нет, ставим фокус на чип текущей вкладки. */
    fun handleUp(focused: View): Boolean {
        val page = pages[selected]
        if (!isInside(focused, page)) return false
        val next = focused.focusSearch(View.FOCUS_UP)
        if (next != null && isInside(next, page)) return false
        chips[selected].requestFocus()
        return true
    }

    private fun isInside(v: View, ancestor: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p === ancestor) return true
            p = p.parent as? View
        }
        return false
    }
}
