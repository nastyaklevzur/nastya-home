package ru.nastya.home

object Greetings {
    data class Greeting(val hello: String, val text: String)

    private const val NAME = "Настюшечка, золотце"

    fun forHour(hour: Int): Greeting = when (hour) {
        in 5..11 -> Greeting("Доброе утро, $NAME ☀️", "Как я рад тебя видеть! Начнём день с чего-нибудь крутого")
        in 12..16 -> Greeting("Добрый день, $NAME 🌤️", "Как я рад тебя видеть! Устраивайся поудобнее — посмотрим кино")
        in 17..22 -> Greeting("Добрый вечер, $NAME ✨", "Как я рад тебя видеть! Устраивайся поудобнее — посмотрим кино")
        else -> Greeting("Доброй ночи, $NAME 🌙", "Как я рад тебя видеть! Посмотрим что-нибудь уютное перед сном")
    }
}
