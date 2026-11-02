# Дом Насти — домашний экран проектора

Android TV-лаунчер для проектора Formovie (Android 9, armeabi-v7a, без Google-сервисов).
Показывает приветствие по времени суток, «Топ-10 рекомендаций кино» и ярлыки приложений.

## Как это работает

* Приложение при запуске (и раз в час) скачивает `movies.json` с этого репозитория:
  1. `https://raw.githubusercontent.com/nastyaklevzur/nastya-home/main/movies.json`
  2. запасной адрес: `https://cdn.jsdelivr.net/gh/nastyaklevzur/nastya-home@main/movies.json`
* Удачно скачанный список кэшируется на устройстве; если сети нет, показывается кэш,
  а если и его нет — список, вшитый в APK при сборке (копия `movies.json` и `posters/`).
* Постеры лежат в `posters/` и указываются относительным путём, поэтому берутся с того же
  адреса, откуда удалось скачать JSON. Если постер не загрузился, показывается вшитая копия
  или аккуратная заглушка.
* Нажатие на карточку открывает поиск фильма в LAMPA (`top.rootu.lampa`). Долгое нажатие —
  экран с описанием и кнопкой «Искать в LAMPA».
* Кнопка «Обновить» в ряду приложений принудительно перекачивает список.

## Формат `movies.json`

```json
{
  "title": "Топ-10 рекомендаций кино",
  "updated": "2026-11-02",
  "movies": [
    {
      "title": "Интерстеллар",
      "original_title": "Interstellar",
      "year": 2014,
      "rating": 8.6,
      "note": "Космос, время и любовь сильнее гравитации",
      "poster": "posters/interstellar.jpg",
      "tmdb_id": 157336,
      "kinopoisk_id": 258687
    }
  ]
}
```

Обязательные поля: `title`, `year`, `rating`, `note` (до 48 символов), `poster` или `poster_url`.
Необязательные: `original_title`, `tmdb_id`, `kinopoisk_id`, `search` (строка для поиска в LAMPA, по умолчанию `title`).

## Еженедельное обновление

Подборку обновляет запланированная задача в Claude Code на Mac (каждый понедельник).
Она подбирает 10 новых фильмов под вкусы Насти (драма/мелодрама, комедия, триллер/детектив,
фантастика/фэнтези; без ограничений по возрасту), пишет черновик и запускает:

```bash
python3 scripts/publish.py draft.json --push
```

`scripts/publish.py` проверяет черновик, скачивает постеры (w342) со страниц TMDB по `tmdb_id`,
записывает `movies.json`, ведёт `history.json` (чтобы фильмы не повторялись) и пушит коммит
с сообщением вида «🎬 Новая десятка для Насти (2026-11-09): …».

## Сборка и установка

```bash
./gradlew assembleRelease
~/Library/Android/sdk/platform-tools/adb connect 192.168.1.121:5555
~/Library/Android/sdk/platform-tools/adb -s 192.168.1.121:5555 install -r app/build/outputs/apk/release/app-release.apk
```

Домашним экраном приложение назначается через LauncherHijack (выбрать «Дом Насти» в его списке)
и через `cmd package set-home-activity ru.nastya.home/.MainActivity`.
