package ru.nastya.home

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingsTest {
    @Test fun withName() {
        assertEquals("Доброе утро, Настя ☀️", Greetings.forHour(8, "Настя").hello)
        assertEquals("Добрый день, Настя 🌤️", Greetings.forHour(13, "Настя").hello)
    }

    @Test fun withoutName() {
        assertEquals("Доброе утро ☀️", Greetings.forHour(8).hello)
        assertEquals("Доброе утро ☀️", Greetings.forHour(8, null).hello)
    }

    @Test fun blankNameIsIgnored() {
        assertEquals("Добрый вечер ✨", Greetings.forHour(19, "   ").hello)
        assertEquals("Добрый вечер, Аня ✨", Greetings.forHour(19, "  Аня ").hello)
    }

    @Test fun hourBoundaries() {
        assertEquals("Доброй ночи 🌙", Greetings.forHour(4).hello)
        assertEquals("Доброе утро ☀️", Greetings.forHour(5).hello)
        assertEquals("Доброе утро ☀️", Greetings.forHour(11).hello)
        assertEquals("Добрый день 🌤️", Greetings.forHour(12).hello)
        assertEquals("Добрый день 🌤️", Greetings.forHour(16).hello)
        assertEquals("Добрый вечер ✨", Greetings.forHour(17).hello)
        assertEquals("Добрый вечер ✨", Greetings.forHour(22).hello)
        assertEquals("Доброй ночи 🌙", Greetings.forHour(23).hello)
        assertEquals("Доброй ночи 🌙", Greetings.forHour(0).hello)
    }

    @Test fun secondLineUnchanged() {
        assertEquals("Как я рад тебя видеть! Начнём день с чего-нибудь крутого", Greetings.forHour(6, "Настя").text)
    }
}
