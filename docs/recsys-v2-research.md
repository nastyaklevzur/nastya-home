# Рек.система v2 — исследование и черновик предложений (2 ноября 2026)

Материал для PRD. Два блока: (1) как это сделано у аналогов, (2) источники данных и интеграции,
(3) предлагаемая архитектура v2 и открытые вопросы. Все URL проверены 2 ноября 2026.

## 1. Как это сделано у аналогов

### Netflix
- Оценки: Thumbs Up («I like this»), Double Thumbs Up («Love this!»), Thumbs Down («Not for me»). Thumbs Up → больше похожего, Double → ещё точнее (те же актёры/студия). Thumbs Down убирает тайтл с главной, в поиске остаётся.
- Отдельного «Not interested» нет — его роль играет Thumbs Down. «Remove from row» только для Continue Watching: без подтверждения и без undo, синхронизируется на все устройства; повторный запуск возвращает тайтл в ряд.
- My List на ТВ: «Add to / Remove from My List», порядок «по релевантности», ручной сортировки нет. Pin отсутствует.
- Ранжирование: недавние действия весят больше старых; лучшие ряды сверху, лучшие тайтлы слева. Cold start: просят выбрать несколько тайтлов, иначе «diverse and popular set». Баланс «fresh vs stability» на уровне страницы, явный recency-boost не раскрыт.

### Apple TV (tvOS)
- Up Next: «Add to Up Next» на карточке с уведомлением-подтверждением; удаление — press-and-hold на карточке → «Remove from Up Next». «Not interested»/«Mark as watched» в гайде нет. Контекстные меню tvOS вызываются long-press.

### Кинопоиск
- Действия на карточке: «Буду смотреть», «Неинтересно» (toggle, «скрыть этот контент»), «Просмотрен», «Оценить» (iOS 1–5, сайт 1–10). Фильтр «Скрывать просмотренные».
- Справка: на рекомендации влияют оценки, просмотры, «Неинтересно», «Буду смотреть», лайки онлайн-кинотеатров, любимые звёзды, вкусы друзей.
- Лента «Что смотреть» (2025): CatBoost → нейроранкер, >1000 признаков; позитив — трейлер >30 с, карточка, «Буду смотреть», оценка, «Смотреть»; негатив — «Неинтересно». Учитывает популярность и новизну; премьеры смешаны с персональным.

### YouTube / Google TV / SmartTube
- ТВ: press-and-hold OK → «Not interested» → «Tell us why» (уже смотрел / не нравится / не рекомендовать канал). Отмена — только в My Activity (на ТВ нет). Google TV: hold OK → «Hide» в Continue watching.
- Mozilla (2022, RCT 567 млн рекомендаций): «Don't recommend channel» снимает 43 % нежелательного, «Not interested» — 11 %, Dislike — 12 %: это точечные сигналы, не «подавить похожее».

### Plex / Jellyfin / Trakt / Letterboxd / Spotify / Steam
- Plex: Watchlist, авто-удаление из него после просмотра; «Remove from Continue Watching» точечно. Jellyfin: «Mark Played» тащит тайтл в Next Up — антипример.
- Trakt: watchlist, оценки 1–10, «hide from recommendations» per-title, обратимо.
- Spotify: pin через long-press, лимит 20, порядок редактируется. Steam Discovery Queue: «Wishlist / Not interested (Ignore) / Next», Ignored-список редактируется постфактум; «Played on another platform» — позитивный сигнал без блокировки.

### Подтверждение vs undo
- NN/g: не ставить подтверждение на рутинные действия («cry wolf»), подтверждать только необратимое и давать undo; кнопки называть по результату. Material: snackbar с единственным действием «Undo».
- Ни один стриминг не ставит диалог на «скрыть/не интересно»: Netflix/Google TV/Apple — мгновенно без undo, YouTube — undo-тост.

### Выводы
1. Вход в меню — long-press OK (стандарт Google TV/YouTube/tvOS) + дубль через Menu. На нашем проекторе long-press OK перехвачен системой — остаётся Menu и/или экран тайтла с кнопками.
2. Пункты — toggle, названия по результату.
3. «Не буду смотреть» и «Просмотрен» — без диалога, карточка исчезает, на место встаёт следующая из пула, snackbar «Вернуть» 5–8 с + список «Скрытые» для отмены позже.
4. «Не буду смотреть» = точечное исключение. Подавлять похожее только по «Не нравится», мягко.
5. Pin: лимит 2–3 слота слева. Квота новинок в ряду (например 3/10), дедуп по франшизе/режиссёру.
6. «Просмотрен» не должен возвращать тайтл в ряд.
7. Один пользователь: до ~10–15 сигналов ранжировать «проверенное + новое» без персонализации; cold start — «выбери 3–5 любимых».

Источники: help.netflix.com/en/node/9977, about.netflix.com/en/news/two-thumbs-up-even-better-recommendations,
help.netflix.com/en/node/115312, help.netflix.com/en/node/100639, support.apple.com/guide/tv/atvbe160da08/17.0/tvos/17.0,
inclusion.yandex.ru/tutorials/kinopoisk-ios, m.yandex.ru/support/kinopoisk/ru/viewing/what, kinopoisk.ru/media/article/4012863/,
support.google.com/youtube/answer/6342839, support.google.com/googletv/answer/10059390,
mozillafoundation.org/blog/mozilla-investigation-youtubes-dislike-button-other-user-controls-largely-fail-to-stop-unwanted-recommendations/,
support.plex.tv/articles/universal-watchlist/, github.com/jellyfin/jellyfin-meta/discussions/50, nngroup.com/articles/confirmation-dialog/,
grouplens.org/blog/onboarding-new-users-in-recommender-systems/, developer.android.com/training/tv/start/controllers.

## 2. Источники данных и интеграции (проверено curl с Mac 02.11.2026)

| Источник | Что даёт | Ключ / лимит | RU | Из РФ | Оценка |
|---|---|---|---|---|---|
| TMDB API | discover/trending/now_playing/upcoming (`region=RU`), reviews, release_dates (цифровой релиз), watch/providers RU (JustWatch), videos, external_ids→imdb_id | бесплатно, ~40 req/s | `language=ru-RU` | **TMDB сам заблокировал РФ (2022)**; провайдер режет DNS → 127.0.0.1; с Mac работает через DoH + VPN. Проектор к TMDB не ходит — всё в репо | 5 (Mac) / 1 (проектор) |
| OMDb | imdbRating, imdbVotes, Metascore, Ratings (RT/MC), строка `Awards` («Won 4 Oscars. 159 wins & 220 nominations total») | 1000/день бесплатно | нет | да | 4 |
| IMDb datasets | title.ratings/basics/akas (русские названия, region=RU), ежедневно; наград нет | бесплатно | частично | да | 4 |
| kinopoiskapiunofficial.tech | `/films/{id}`: ratingKinopoisk+ratingImdb, русские описания; `/awards`, `/reviews`, `/videos`, `/external_sources`, `/films/premieres?year&month`, `/films/collections`, `/api/v1/media_posts` (новости Кинопоиск Медиа) | FREE ≈ 500/день, безлимит ≈ 1000 ₽ разово | да | да | 5 |
| poiskkino.dev (бывш. kinopoisk.dev) | рейтинги КП/IMDb, awards, watchability, premiere.russia, ids TMDB/IMDb | Demo 200/день | да | да | 4 |
| Wikidata SPARQL | награды (P166) и номинации (P1411) с русскими метками и imdb_id; Оскар Q102427, Пальмовая ветвь Q179808, Глобус Q1011509, BAFTA Q139184, Золотой лев Q209459 | без ключа, нужен UA | да | да | 5 |
| Trakt API | sync/ratings, watchlist, history, favorites, recommendations; OAuth Device Code для ТВ | GET 1000/5 мин; free: списки по 100, оценки/история без лимита | нет | доступен | 4 (обратный канал) |
| RSS о кино | film.ru/rss.xml, daily.afisha.ru/rss/ (фильтр «Кино»), kinoreporter.ru/feed/, kg-portal.ru/rss/news.rss, kinonews.ru/rss/, kommersant.ru/RSS/section-culture.xml, editorial.rottentomatoes.com/feed/, rogerebert.com/reviews/feed | — | да/англ | да | 3–4 |
| Не работает | imdbapi.dev (домен мёртв), Metacritic RSS (410), Кинопоиск RSS и Кино-Театр.ру (антибот), kinoart.ru (сброс), Letterboxd API (не выдаётся для LLM-проектов), публичные Invidious/Piped | | | | 0 |

Трейлеры: ключи YouTube из TMDB/Кинопоиск → интент `https://youtu.be/{key}` в SmartTube (на проекторе есть). Где смотреть: TMDB watch/providers RU или `external_sources` Кинопоиска → «Есть в Кинопоиске/Okko».

Ключевые URL: developer.themoviedb.org/docs/rate-limiting, blog.themoviedb.org/the-movie-database-suspends-access-from-russia-and-belarus-b6a1a96fb35b,
omdbapi.com/apikey.aspx, data.imdb.com/non-commercial-datasets/, kinopoiskapiunofficial.tech/documentation/api/openapi.json, poiskkino.dev,
query.wikidata.org, trakt.docs.apiary.io, trakt.tv/oauth/applications.

## 3. Предлагаемая архитектура v2 (черновик, обсуждается)

### Файлы в репозитории
- `movies.json` — **пул 30–40 фильмов** с рангом, формирует Mac. Новые поля: `imdb_id`, `kinopoisk_id`, `genres`, `runtime`, `imdb_rating`, `kp_rating`, `rt`, `metascore`, `awards[]`, `is_new`, `why_you` (почему тебе), `critics` (строка из рецензий), `trailer_key`, `providers[]`, `added_at`.
- `state.json` — **состояние, которым владеет проектор**: `pinned[]`, `favorites[]`, `removed[{id, reason: watched|skip, liked, date}]`, `likes[]`, `dislikes[]`, `started[]`. Ключ — `tmdb_id` (сейчас title|year).
- `history.json` → сливается в state/pool.

### Обратный канал (главный открытый вопрос)
- **A. GitHub Contents API с проектора** — fine-grained PAT (contents:write только на этот репозиторий) в APK; приложение делает GET sha → PUT state.json после каждого действия, с очередью и повтором офлайн. Нулевая инфраструктура, Mac читает тот же репозиторий. Риск: токен извлекаем из APK → вандализм домашнего репо; APK не публикуется, токен ротируется.
- **B. Trakt** — device-code логин один раз, оценки/история/избранное через API, бонус: готовые рекомендации и приложение на телефоне. Минус: третья сторона, английский, лимиты списков 100.
- **C. adb с Mac** — работает только когда Mac в домашней сети и проектор включён; релизная сборка не debuggable, prefs не читаются (проверено 02.11) — приложению пришлось бы само выгружать состояние. Хрупко.

### Проектор
- Раз в час тянет movies.json + state.json (как сейчас). Ряд = закреплённые (по порядку закрепления, ≤3) + пул по рангу минус removed; квота новинок 3/10; значок 📌 на закреплённых, «Новое» на добавленных за 3 дня.
- Действия: Закрепить/Открепить, В избранное/Убрать, Не буду смотреть, Просмотрен → «Понравился?» (Нравится / Не нравится / Пропустить). Удалённая карточка исчезает, следующая из пула встаёт на её место; snackbar «Вернуть» 6 с; экран «Избранное» со вкладкой «Скрытые» для отмены позже.
- Порядок ряда не перетасовывается «под рукой»: новый пул применяется при холодном старте или когда лаунчер не видели >10 мин (та же логика, что у заставки).

### Mac (задача Claude, ежедневно/через день, пока Claude открыт)
1. pull → читает state.json → обновляет профиль вкусов (`taste` в state или отдельный файл): жанры/десятилетия/страны/настроение из likes/dislikes/favorites/removed.
2. Кандидаты: TMDB discover/trending/upcoming (`region=RU`, `ru-RU`), Кинопоиск премьеры РФ, Wikidata лауреаты/номинанты сезона.
3. Обогащение: OMDb (IMDb/RT/MC/Awards), Кинопоиск (ratingKinopoisk, awards, reviews), release_dates (цифровой релиз состоялся → есть шанс на озвучку в LAMPA).
4. Claude ранжирует с учётом профиля и 2–3 рецензий на кандидата, пишет `why_you` и `critics`; новинкам (≤18 мес.) буст и квота; порог для новинок мягче (IMDb ≥7.0 при ≥10k голосов) — иначе 2026 год почти не пройдёт 7.5.
5. publish.py v2: стабильность — закреплённые и непросмотренные остаются, за один прогон заменяется ≤1/3 пула; пушит movies.json.

### Интеграции в лаунчере
- Значок наград на карточке: «🏆 Оскар», «Номинант на Оскар», «Канны» (Wikidata + OMDb).
- «Где смотреть»: «Есть в Кинопоиске / Okko» из watch/providers RU, LAMPA — запасной путь.
- Трейлер в SmartTube по ключу YouTube (VPN на роутере уже есть).
- Строка кино-новостей/рецензий: RSS film.ru / Афиша Daily / Кинорепортёр — чередовать с калининградскими новостями или показывать «Премьеры недели в России» из Кинопоиск-API.
- Trakt — опционально, позже.

## 4. Открытые вопросы к продукту
1. Обратный канал: GitHub-токен в APK (рекомендую), Trakt или adb?
2. Подтверждения: оставить диалоги, как в запросе, или «мгновенно + Вернуть», как у всех сервисов?
3. Семантика: Закрепить = «смотрю на днях» (слот в ряду), Избранное = долгий список «хочу посмотреть» или «любимые просмотренные»? Нравится/Не нравится — только после просмотра или и на непросмотренных?
4. OK на карточке: сразу LAMPA (как сейчас) или экран фильма с кнопками (Смотреть / Закрепить / В избранное / …)?
5. Ежедневный прогон требует открытого Claude на Mac и тратит токены на чтение рецензий; достаточно ли 2–3 раз в неделю при пуле на 40?
6. Куда ставить «Избранное» и кино-новости на экране без прокрутки?
7. «Новинки» — только текущий год или ≤18 месяцев? Снижаем ли порог рейтинга для них?
8. Нужна ли метка «Начала смотреть» дальше, и показывать ли просмотренные где-то (история)?
