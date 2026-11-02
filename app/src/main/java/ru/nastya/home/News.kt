package ru.nastya.home

import android.content.Context
import android.text.Html
import android.util.Log
import org.json.JSONArray
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

data class NewsFeed(val name: String, val url: String)

/** Заголовки новостей Калининграда из RSS. Ленты выбраны пользователем и проверены с проектора. */
object NewsRepository {
    private const val TAG = "NastyaHome"
    private const val PREFS = "news"
    const val MAX_ITEMS = 6

    val FEEDS = listOf(
        NewsFeed("Новый Калининград", "https://www.newkaliningrad.ru/news/rss.xml"),
        // Запасные, тоже доступны с проектора:
        // NewsFeed("Клопс", "https://admin.klops.ru/rss/rss"),
        // NewsFeed("Калининград.Ru", "https://kgd.ru/news?format=feed&type=rss"),
    )

    fun cached(ctx: Context): List<String> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("titles", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            List(arr.length()) { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun lastFetchAt(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("fetched", 0L)

    /** Блокирующая загрузка; первая удачная лента побеждает. */
    fun fetch(ctx: Context): List<String>? {
        for (feed in FEEDS) {
            try {
                val titles = parseTitles(Http.get(feed.url)).take(MAX_ITEMS)
                if (titles.isEmpty()) continue
                ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("titles", JSONArray(titles).toString())
                    .putLong("fetched", System.currentTimeMillis())
                    .apply()
                Log.i(TAG, "news: ${titles.size} titles from ${feed.name}")
                return titles
            } catch (e: Exception) {
                Log.w(TAG, "news feed ${feed.name} failed: $e")
            }
        }
        return null
    }

    fun parseTitles(xml: String): List<String> {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val titles = ArrayList<String>()
        var inItem = false
        var tag: String? = null
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "item" || tag == "entry") inItem = true
                }
                XmlPullParser.TEXT -> if (inItem && tag == "title") {
                    val t = clean(parser.text)
                    if (t.isNotEmpty()) titles.add(t)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "item" || parser.name == "entry") inItem = false
                    tag = null
                }
            }
            event = parser.next()
        }
        return titles
    }

    @Suppress("DEPRECATION")
    private fun clean(s: String): String =
        Html.fromHtml(s.trim()).toString().replace(Regex("\\s+"), " ").trim()
}
