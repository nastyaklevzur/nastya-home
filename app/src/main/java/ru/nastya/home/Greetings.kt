package ru.nastya.home

object Greetings {
    data class Greeting(val hello: String, val text: String)

    private const val BASE = "Настюшечка, золотце, как я рад тебя видеть! Скорее устраивайся поудобнее и давай "

    fun forHour(hour: Int): Greeting = when (hour) {
        in 5..11 -> Greeting("Доброе утро ☀️", BASE + "начнём день с чего-нибудь крутого")
        in 12..16 -> Greeting("Добрый день 🌤️", BASE + "посмотрим сегодня что-нибудь крутое")
        in 17..22 -> Greeting("Добрый вечер ✨", BASE + "посмотрим сегодня что-нибудь крутое")
        else -> Greeting("Доброй ночи 🌙", BASE + "посмотрим что-нибудь уютное перед сном")
    }
}
