package ru.nastya.home

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Фон заставки: каждый раз новый свежий снимок — Земля с орбиты, Солнце, полярное сияние.
 * Все источники проверены с проектора 2 ноября 2026 (без ключей и заголовков).
 */
object SplashBackdrops {

    class Backdrop(val id: String, val caption: String, val nightOnly: Boolean = false, val url: () -> String)

    private val utc = TimeZone.getTimeZone("UTC")
    private val msk = TimeZone.getTimeZone("Europe/Moscow")

    val all: List<Backdrop> = listOf(
        Backdrop("goes-east", "Земля с орбиты · спутник GOES-East, Америка и Атлантика") {
            "https://cdn.star.nesdis.noaa.gov/GOES19/ABI/FD/GEOCOLOR/1808x1808.jpg"
        },
        Backdrop("sdo-304", "Солнце в ультрафиолете · обсерватория SDO, NASA") {
            "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_0304.jpg"
        },
        Backdrop("electro-l3", "Земля с орбиты · спутник «Электро-Л» №3, Роскосмос") {
            // Кадры слайдера подписаны московским временем с шагом 30 минут и появляются с опозданием около часа.
            "https://electro.ntsomz.ru/i/splash_l3/" + slot(msk, lagMinutes = 75, stepMinutes = 30, "yyyyMMdd-HHmm") + ".jpg"
        },
        Backdrop("aurora-tromso", "Ночное небо над Тромсё · камера полярного сияния", nightOnly = true) {
            "https://fox.phys.uit.no/ASC/BACC5.jpg"
        },
        Backdrop("goes-west", "Земля с орбиты · спутник GOES-West, Тихий океан") {
            "https://cdn.star.nesdis.noaa.gov/GOES18/ABI/FD/GEOCOLOR/1808x1808.jpg"
        },
        Backdrop("sdo-171", "Корона Солнца · SDO AIA 171, NASA") {
            "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_0171.jpg"
        },
        Backdrop("himawari", "Земля с орбиты · спутник Himawari-9, Япония") {
            "https://www.data.jma.go.jp/mscweb/data/himawari/img/fd_/fd__trm_" + slot(utc, lagMinutes = 25, stepMinutes = 10, "HHmm") + ".jpg"
        },
        Backdrop("aurora-sodankyla", "Небо над Лапландией · обсерватория Соданкюля", nightOnly = true) {
            "https://www.sgo.fi/Data/RealTime/Kuvat/UCL.jpg"
        },
        Backdrop("soho-c3", "Корона Солнца и солнечный ветер · коронограф SOHO") {
            "https://soho.nascom.nasa.gov/data/realtime/c3/1024/latest.jpg"
        },
        Backdrop("sdo-composite", "Солнце в трёх длинах волн · SDO, NASA") {
            "https://sdo.gsfc.nasa.gov/assets/img/latest/latest_1024_211193171.jpg"
        },
    )

    /** Ближайший «целый» слот времени с учётом задержки публикации снимка. */
    private fun slot(tz: TimeZone, lagMinutes: Int, stepMinutes: Int, pattern: String): String {
        val c = Calendar.getInstance(tz)
        c.add(Calendar.MINUTE, -lagMinutes)
        c.set(Calendar.MINUTE, c.get(Calendar.MINUTE) / stepMinutes * stepMinutes)
        c.set(Calendar.SECOND, 0)
        return SimpleDateFormat(pattern, Locale.US).apply { timeZone = tz }.format(c.time)
    }

    private fun suitable(now: Calendar): List<Backdrop> {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val night = hour >= 18 || hour < 8
        return all.filter { !it.nightOnly || night }
    }

    /** Снимок для ближайшей заставки (без сдвига очереди). */
    fun peek(ctx: Context): Backdrop {
        val list = suitable(Calendar.getInstance())
        val idx = prefs(ctx).getInt("index", 0)
        return list[Math.floorMod(idx, list.size)]
    }

    /** Заставка показана — переходим к следующему снимку. */
    fun advance(ctx: Context) {
        prefs(ctx).edit().putInt("index", prefs(ctx).getInt("index", 0) + 1).apply()
    }

    /** Ключ кэша: один и тот же снимок в пределах часа, чтобы предзагрузка и показ совпали. */
    fun cacheKey(b: Backdrop): String =
        b.id + "#" + SimpleDateFormat("yyyyMMddHH", Locale.US).apply { timeZone = utc }.format(Calendar.getInstance().time)

    /** Заранее скачать снимок для следующей заставки, чтобы при включении он появился мгновенно. */
    fun prefetchNext(ctx: Context) {
        val b = peek(ctx)
        val request = ImageRequest.Builder(ctx)
            .data(b.url())
            .diskCacheKey(cacheKey(b))
            .memoryCachePolicy(CachePolicy.DISABLED)
            .size(1920, 1080)
            .build()
        ctx.imageLoader.enqueue(request)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("splash", Context.MODE_PRIVATE)
}
