package ru.nastya.home

object Greetings {
    data class Greeting(val hello: String, val text: String)

    fun forHour(hour: Int, name: String? = null): Greeting {
        val n = name?.trim()?.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
        return when (hour) {
            in 5..11 -> Greeting("Доброе утро$n ☀️", "Как я рад тебя видеть! Начнём день с чего-нибудь крутого")
            in 12..16 -> Greeting("Добрый день$n 🌤️", "Как я рад тебя видеть! Устраивайся поудобнее — посмотрим кино")
            in 17..22 -> Greeting("Добрый вечер$n ✨", "Как я рад тебя видеть! Устраивайся поудобнее — посмотрим кино")
            else -> Greeting("Доброй ночи$n 🌙", "Как я рад тебя видеть! Посмотрим что-нибудь уютное перед сном")
        }
    }
}
