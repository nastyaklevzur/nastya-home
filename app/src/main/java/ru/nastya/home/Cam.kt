package ru.nastya.home

import org.json.JSONArray
import org.json.JSONObject

/**
 * Онлайн-камера из cams.json.
 * type: "hls" (прямой m3u8, при необходимости referer), "ipeye" (dev + server), "ivideon" (server + cam),
 * "skyline" (page — страница skylinewebcams.com, токен берётся с неё перед запуском),
 * "earthcam" (url плейлиста videos-N.earthcam.com; Referer и браузерный User-Agent добавляются сами),
 * "ipcamlive" (cam — alias плеера ipcamlive.com), "moszoo" (page — страница вольера zoo.mos.ru, cam — data-id виджета).
 */
data class Cam(
    val id: String,
    val title: String,
    val subtitle: String?,
    val type: String,
    val url: String?,
    val referer: String?,
    val dev: String?,
    val server: String?,
    val cam: String?,
    val page: String?,
    val previewUrl: String?,
    val previewFallbackUrl: String?,
    val hidden: Boolean,
    val home: Boolean = false,
) {
    val label: String get() = if (subtitle.isNullOrBlank()) title else "$title · $subtitle"

    /** Имя файла для поиска вшитой копии превью. */
    val previewFileName: String?
        get() = (previewFallbackUrl ?: previewUrl)?.substringAfterLast('/')?.substringBefore('?')?.takeIf { it.isNotEmpty() }
}

data class CamsData(val title: String, val cams: List<Cam>, val source: String) {
    /** Камеры для ряда на «Главной»: видимые с `home`; если `home` нет ни у одной (старый кэш) — все видимые. */
    val homeCams: List<Cam>
        get() {
            val visible = cams.filter { !it.hidden }
            return if (cams.any { it.home }) visible.filter { it.home } else visible
        }
}

object CamsParser {
    private const val DEFAULT_TITLE = "Онлайн-камеры"

    fun parse(json: String, baseUrl: String, source: String): CamsData {
        val obj = JSONObject(json.trim())
        val title = str(obj, "title") ?: DEFAULT_TITLE
        val arr: JSONArray = obj.optJSONArray("cams") ?: JSONArray()
        val list = ArrayList<Cam>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = str(o, "title") ?: continue
            val type = (str(o, "type") ?: "hls").lowercase()
            list.add(
                Cam(
                    id = str(o, "id") ?: "cam$i",
                    title = t,
                    subtitle = str(o, "subtitle"),
                    type = type,
                    url = str(o, "url"),
                    referer = str(o, "referer"),
                    dev = str(o, "dev"),
                    server = str(o, "server"),
                    cam = str(o, "cam"),
                    page = str(o, "page"),
                    previewUrl = RemoteStore.resolve(baseUrl, str(o, "preview")),
                    previewFallbackUrl = RemoteStore.resolve(baseUrl, str(o, "preview_fallback")),
                    hidden = o.optBoolean("hidden", false),
                    home = o.optBoolean("home", false),
                )
            )
        }
        require(list.isNotEmpty()) { "cams.json: пустой список" }
        return CamsData(title, list, source)
    }

    private fun str(o: JSONObject, key: String): String? {
        if (!o.has(key) || o.isNull(key)) return null
        return o.optString(key).trim().takeIf { it.isNotEmpty() }
    }
}
