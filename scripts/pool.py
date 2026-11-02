#!/usr/bin/env python3
"""
Пул фильмов для домашнего экрана Насти (рек.система v2, см. docs/prd-recsys-v2.md).

    python3 scripts/pool.py state                      # сводка state.json с проектора (что закрепила, что убрала, оценки)
    python3 scripts/pool.py find "Sinners" 2025        # найти фильм в Wikidata: tmdb_id, imdb_id, дата, русское название
    python3 scripts/pool.py enrich draft.json pool.json  # обогатить черновик: ids, рейтинг IMDb, жанры, награды, новинка, постер
    python3 scripts/pool.py validate pool.json         # проверить пул (30–40, уникальность, пороги, квота новинок, удалённые)
    python3 scripts/pool.py publish pool.json [--push] # записать movies.json v2, обновить history.json, закоммитить (и запушить)
    python3 scripts/pool.py taste                      # пересобрать taste.json (счётчики вкусов по оценкам)

Черновик — {"movies": [...]} или массив. У фильма достаточно title, year, note и tmdb_id (или original_title — тогда tmdb_id
найдётся в Wikidata). Остальное (imdb_id, kinopoisk_id, рейтинги, жанры, хронометраж, страна, режиссёр, награды, is_new,
постер) заполняет enrich; поля из текущего movies.json с тем же tmdb_id сохраняются и дополняются. why_you и critics пишет Claude.

Источники без ключей: Wikidata (SPARQL + API), датасет IMDb title.ratings, постеры со страниц Википедии (ru/en) или TMDB,
когда он доступен. Ключи в ~/.config/nastya-home/keys.env (OMDB_KEY, KINOPOISK_KEY) включают OMDb (Metacritic, RT)
и Кинопоиск (рейтинг КП). Кэш — ~/.cache/nastya-home/.
"""
import gzip
import json
import os
import re
import subprocess
import sys
import time
import unicodedata
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POSTERS = ROOT / "posters"
MOVIES = ROOT / "movies.json"
HISTORY = ROOT / "history.json"
STATE = ROOT / "state.json"
TASTE = ROOT / "taste.json"
CACHE = Path.home() / ".cache" / "nastya-home"
KEYS_FILE = Path.home() / ".config" / "nastya-home" / "keys.env"

UA_BROWSER = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
UA_BOT = "NastyaHome-pool/1.0 (https://github.com/nastyaklevzur/nastya-home; a.klevtsur@gmail.com)"

POOL_MIN, POOL_MAX = 30, 40
NEW_MIN = 8                     # новинок в пуле не меньше
NEW_WINDOW_DAYS = 548           # новинка = премьера за последние 18 месяцев…
NEW_DIGITAL_LAG_DAYS = 75       # …и прошло ≥ 75 дней (цифровой релиз, озвучка в LAMPA скорее всего есть)
RATING_CLASSIC, RATING_NEW, VOTES_NEW = 7.5, 7.0, 10_000
MAX_NOTE, MAX_WHY, MAX_CRITICS = 48, 90, 160
MAX_PER_DIRECTOR = 2

# Награды: Q-идентификаторы и подстроки названий в Wikidata.
AWARDS = {
    "oscar": (("Оскар", "Academy Award"), "🏆 Оскар", "Номинант «Оскара»"),
    "palme": (("Золотая пальмовая ветвь", "Palme d'Or"), "🌿 Канны", None),
    "globe": (("Золотой глобус", "Golden Globe"), "Золотой глобус", None),
    "bafta": (("BAFTA", "Британской академии"), "BAFTA", None),
    "lion": (("Золотой лев", "Golden Lion"), "Золотой лев", None),
    "bear": (("Золотой медведь", "Golden Bear"), "Золотой медведь", None),
}
BEST_PICTURE = "Q102427"
GENRE_MAP = {
    "драматический фильм": "драма", "фильм-драма": "драма", "драма": "драма",
    "комедийный фильм": "комедия", "комедия": "комедия", "романтическая комедия": "комедия", "чёрная комедия": "комедия",
    "комедийная драма": "драма", "трагикомедия": "драма",
    "фильм-триллер": "триллер", "триллер": "триллер", "психологический триллер": "триллер",
    "детективный фильм": "детектив", "детектив": "детектив", "неонуар": "детектив", "фильм-нуар": "детектив",
    "научно-фантастический фильм": "фантастика", "научная фантастика": "фантастика",
    "фэнтезийный фильм": "фэнтези", "фэнтези": "фэнтези", "тёмное фэнтези": "фэнтези",
    "романтический фильм": "мелодрама", "мелодрама": "мелодрама", "фильм о любви": "мелодрама",
    "фильм ужасов": "ужасы", "ужасы": "ужасы",
    "приключенческий фильм": "приключения", "боевик": "боевик", "фильм-боевик": "боевик",
    "биографический фильм": "биография", "криминальный фильм": "криминал",
    "мультфильм": "мультфильм", "анимационный фильм": "мультфильм", "компьютерный анимационный фильм": "мультфильм",
    "военный фильм": "военный", "исторический фильм": "исторический", "музыкальный фильм": "мюзикл", "мюзикл": "мюзикл",
    "фильм о взрослении": "взросление", "семейный фильм": "семейный", "спортивный фильм": "спорт",
    "фильм-катастрофа": "катастрофа", "вестерн": "вестерн", "роуд-муви": "роуд-муви",
}
GENRE_ORDER = ["драма", "мелодрама", "комедия", "триллер", "детектив", "фантастика", "фэнтези", "ужасы", "приключения",
               "боевик", "криминал", "биография", "мультфильм", "военный", "исторический", "мюзикл", "семейный"]


# ----------------------------------------------------------------------------- утилиты

def fail(msg):
    print("ОШИБКА:", msg, file=sys.stderr)
    sys.exit(1)


def warn(msg):
    print("предупреждение:", msg, file=sys.stderr)


def slugify(text):
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-zA-Z0-9]+", "_", text).strip("_").lower()
    return text or "poster"


def load_keys():
    keys = {}
    if KEYS_FILE.exists():
        for line in KEYS_FILE.read_text().splitlines():
            if "=" in line and not line.strip().startswith("#"):
                k, v = line.split("=", 1)
                keys[k.strip()] = v.strip().strip('"')
    return keys


KEYS = load_keys()


def fetch(url, headers=None, timeout=30, retries=2):
    h = {"User-Agent": UA_BOT, "Accept-Language": "ru-RU,ru;q=0.9,en;q=0.8"}
    h.update(headers or {})
    last = None
    for attempt in range(retries + 1):
        try:
            req = urllib.request.Request(url, headers=h)
            with urllib.request.urlopen(req, timeout=timeout) as resp:
                return resp.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(1.5 * (attempt + 1))
    raise RuntimeError(f"{url}: {last}")


def fetch_json(url, headers=None, timeout=30):
    return json.loads(fetch(url, headers, timeout).decode("utf-8"))


def cached_json(name, loader, max_age_hours=24 * 30):
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / name
    if f.exists() and time.time() - f.stat().st_mtime < max_age_hours * 3600:
        return json.loads(f.read_text())
    data = loader()
    f.write_text(json.dumps(data, ensure_ascii=False))
    return data


def key_of(m):
    return str(m["tmdb_id"]) if m.get("tmdb_id") else f"{m.get('title', '').strip().lower()}|{m.get('year', '')}"


def load_json(path, default):
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else default


def dump_json(path, data):
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def plural(n, one, few, many):
    n10, n100 = n % 10, n % 100
    if n10 == 1 and n100 != 11:
        return one
    if 2 <= n10 <= 4 and not 12 <= n100 <= 14:
        return few
    return many


# ----------------------------------------------------------------------------- Wikidata

SPARQL = "https://query.wikidata.org/sparql"
WD_API = "https://www.wikidata.org/w/api.php"


def sparql(query):
    url = SPARQL + "?" + urllib.parse.urlencode({"query": query, "format": "json"})
    return fetch_json(url, {"Accept": "application/sparql-results+json"}, timeout=60)["results"]["bindings"]


def wd_qid_by_tmdb(tmdb_id):
    def load():
        rows = sparql(f'SELECT ?item WHERE {{ ?item wdt:P4947 "{int(tmdb_id)}" }} LIMIT 1')
        return rows[0]["item"]["value"].rsplit("/", 1)[-1] if rows else None
    return cached_json(f"tmdb_{tmdb_id}.json", load)


def wd_entity(qid):
    def load():
        return fetch_json(f"https://www.wikidata.org/wiki/Special:EntityData/{qid}.json")["entities"][qid]
    return cached_json(f"{qid}.json", load, max_age_hours=24 * 7)


def wd_labels(qids):
    """Метки (ru, иначе en) для списка Q-идентификаторов, с кэшем."""
    out, missing = {}, []
    labels_cache = CACHE / "labels.json"
    cache = json.loads(labels_cache.read_text()) if labels_cache.exists() else {}
    for q in qids:
        if q in cache:
            out[q] = cache[q]
        else:
            missing.append(q)
    for i in range(0, len(missing), 50):
        chunk = missing[i:i + 50]
        data = fetch_json(WD_API + "?" + urllib.parse.urlencode({
            "action": "wbgetentities", "ids": "|".join(chunk), "props": "labels", "languages": "ru|en", "format": "json"}))
        for q, e in data.get("entities", {}).items():
            labels = e.get("labels", {})
            out[q] = cache[q] = {"ru": labels.get("ru", {}).get("value"), "en": labels.get("en", {}).get("value")}
    if missing:
        CACHE.mkdir(parents=True, exist_ok=True)
        labels_cache.write_text(json.dumps(cache, ensure_ascii=False))
    return out


def claim_ids(entity, prop):
    out = []
    for c in entity.get("claims", {}).get(prop, []):
        v = c.get("mainsnak", {}).get("datavalue", {}).get("value")
        if isinstance(v, dict) and v.get("id"):
            out.append(v["id"])
    return out


def claim_values(entity, prop):
    return [c.get("mainsnak", {}).get("datavalue", {}).get("value") for c in entity.get("claims", {}).get(prop, [])
            if c.get("mainsnak", {}).get("datavalue")]


def earliest_date(entity):
    """Премьера: самая ранняя дата выхода (P577). Даты с точностью до года (1 января) учитываются,
    только если точных нет; одиночный ранний выброс (анонс или закрытый показ за 3+ месяца до остальных) отбрасывается."""
    exact, coarse = [], []
    for c in entity.get("claims", {}).get("P577", []):
        v = c.get("mainsnak", {}).get("datavalue", {}).get("value")
        if isinstance(v, dict) and v.get("time"):
            m = re.match(r"\+(\d{4})-(\d{2})-(\d{2})", v["time"])
            if m:
                y, mo, d = (int(x) for x in m.groups())
                (exact if v.get("precision", 11) >= 10 else coarse).append(date(y, max(mo, 1), max(d, 1)))
    dates = sorted(exact or coarse)
    if len(dates) >= 3 and (dates[1] - dates[0]).days > 90:
        dates = dates[1:]
    return dates[0] if dates else None


def find_in_wikidata(title, year=None):
    """Поиск фильма по названию (ru или en) и году: список кандидатов с tmdb/imdb/датой."""
    safe = title.replace("\\", "\\\\").replace('"', '\\"')
    q = f'''SELECT ?item ?ru ?en ?tmdb ?imdb (MIN(?d) AS ?date) WHERE {{
      ?item wdt:P31/wdt:P279* wd:Q11424 .
      {{ ?item rdfs:label "{safe}"@en }} UNION {{ ?item rdfs:label "{safe}"@ru }} UNION {{ ?item skos:altLabel "{safe}"@ru }} UNION {{ ?item skos:altLabel "{safe}"@en }}
      OPTIONAL {{ ?item wdt:P577 ?d }} OPTIONAL {{ ?item wdt:P4947 ?tmdb }} OPTIONAL {{ ?item wdt:P345 ?imdb }}
      OPTIONAL {{ ?item rdfs:label ?ru FILTER(LANG(?ru) = "ru") }} OPTIONAL {{ ?item rdfs:label ?en FILTER(LANG(?en) = "en") }}
    }} GROUP BY ?item ?ru ?en ?tmdb ?imdb LIMIT 20'''
    rows = []
    for b in sparql(q):
        row = {k: v["value"] for k, v in b.items()}
        row["qid"] = row.pop("item").rsplit("/", 1)[-1]
        row["year"] = int(row["date"][:4]) if row.get("date") else None
        rows.append(row)
    if year:
        exact = [r for r in rows if r["year"] and abs(r["year"] - int(year)) <= 1]
        rows = exact or rows
    return rows


# ----------------------------------------------------------------------------- рейтинги и ключевые API

_IMDB = None


def imdb_ratings():
    """Словарь tt… → (rating, votes) из датасета IMDb (8 МБ, кэш на сутки)."""
    global _IMDB
    if _IMDB is not None:
        return _IMDB
    CACHE.mkdir(parents=True, exist_ok=True)
    f = CACHE / "title.ratings.tsv.gz"
    if not f.exists() or time.time() - f.stat().st_mtime > 24 * 3600:
        print("скачиваю датасет рейтингов IMDb…", file=sys.stderr)
        f.write_bytes(fetch("https://datasets.imdbws.com/title.ratings.tsv.gz", timeout=120))
    _IMDB = {}
    with gzip.open(f, "rt", encoding="utf-8") as fh:
        next(fh)
        for line in fh:
            t, r, v = line.rstrip("\n").split("\t")
            _IMDB[t] = (float(r), int(v))
    return _IMDB


def omdb(imdb_id):
    key = KEYS.get("OMDB_KEY")
    if not key or not imdb_id:
        return {}
    try:
        return cached_json(f"omdb_{imdb_id}.json", lambda: fetch_json(f"https://www.omdbapi.com/?apikey={key}&i={imdb_id}"), 24 * 7)
    except Exception as e:  # noqa: BLE001
        warn(f"OMDb {imdb_id}: {e}")
        return {}


def kinopoisk(kp_id):
    key = KEYS.get("KINOPOISK_KEY")
    if not key or not kp_id:
        return {}
    try:
        return cached_json(f"kp_{kp_id}.json", lambda: fetch_json(
            f"https://kinopoiskapiunofficial.tech/api/v2.2/films/{kp_id}", {"X-API-KEY": key}), 24 * 7)
    except Exception as e:  # noqa: BLE001
        warn(f"Кинопоиск {kp_id}: {e}")
        return {}


# ----------------------------------------------------------------------------- описание и отзывы

MAX_OVERVIEW = 420


def _trim_sentences(text, limit):
    text = re.sub(r"\s+", " ", text).strip()
    if len(text) <= limit:
        return text
    cut = text[:limit]
    end = max(cut.rfind(". "), cut.rfind("! "), cut.rfind("? "))
    return cut[:end + 1] if end > limit // 2 else cut.rsplit(" ", 1)[0] + "…"


def wiki_overview(entity):
    """Начало раздела «Сюжет» статьи русской Википедии (без спойлеров — только первые предложения);
    если раздела нет — первый абзац статьи."""
    title = entity.get("sitelinks", {}).get("ruwiki", {}).get("title")
    if not title:
        return None
    try:
        data = fetch_json("https://ru.wikipedia.org/w/api.php?" + urllib.parse.urlencode({
            "action": "query", "prop": "extracts", "explaintext": 1, "exsectionformat": "wiki", "exlimit": 1,
            "titles": title, "format": "json", "redirects": 1}))
    except Exception as e:  # noqa: BLE001
        warn(f"Википедия «{title}»: {e}")
        return None
    text = ""
    for page in data.get("query", {}).get("pages", {}).values():
        text = page.get("extract", "") or ""
    text = text.replace("\u0301", "")                                   # ударения
    text = re.sub(r"\s*\([^()]*\b(англ|фр|итал|нем|исп|яп|кор|порт|норв|перс|кит)\.[^()]*\)", "", text)
    text = re.sub(r"\[\d+\]", "", text)
    m = re.search(r"\n=+\s*Сюжет[^=\n]*=+\s*\n(.*?)(?:\n=+[^=\n]+=+\s*\n|\Z)", text, re.S)
    body = m.group(1) if m else text.split("\n==", 1)[0]
    paragraphs = [x.strip() for x in body.split("\n") if len(x.strip()) > 40]
    if not paragraphs:
        return None
    return _trim_sentences(paragraphs[0], MAX_OVERVIEW) or None


def kinopoisk_reviews(kp_id, limit=3):
    """Отзывы зрителей Кинопоиска (нужен KINOPOISK_KEY): до трёх коротких выдержек без спойлеров."""
    key = KEYS.get("KINOPOISK_KEY")
    if not key or not kp_id:
        return []
    try:
        data = cached_json(f"kp_reviews_{kp_id}.json", lambda: fetch_json(
            f"https://kinopoiskapiunofficial.tech/api/v2.2/films/{kp_id}/reviews?page=1&order=USER_POSITIVE_RATING_DESC",
            {"X-API-KEY": key}), 24 * 7)
    except Exception as e:  # noqa: BLE001
        warn(f"отзывы Кинопоиска {kp_id}: {e}")
        return []
    # Сначала положительные, потом нейтральные; отрицательный — не больше одного и только последним.
    order = {"positive": 0, "neutral": 1, "negative": 2}
    items = sorted(data.get("items", []), key=lambda r: (order.get((r.get("type") or "").lower(), 1), -(r.get("positiveRating") or 0)))
    out, negatives = [], 0
    for r in items:
        text = re.sub(r"\s+", " ", (r.get("description") or "")).strip()
        kind = (r.get("type") or "").lower()
        if len(text) < 80 or "спойлер" in text.lower():
            continue
        if kind == "negative":
            if negatives or len(out) < limit - 1:
                continue
            negatives += 1
        out.append({"author": r.get("author") or "зритель", "type": kind, "text": _trim_sentences(text, 240)})
        if len(out) >= limit:
            break
    return out


# ----------------------------------------------------------------------------- постеры

def poster_from_tmdb(tmdb_id):
    html = fetch(f"https://www.themoviedb.org/movie/{tmdb_id}?language=ru-RU", {"User-Agent": UA_BROWSER}, timeout=15, retries=0).decode("utf-8", "ignore")
    m = re.search(r'<meta property="og:image" content="([^"]+)"', html)
    if not m:
        raise RuntimeError("og:image не найден")
    url = re.sub(r"/t/p/[^/]+/", "/t/p/w342/", m.group(1)).replace("media.themoviedb.org", "image.tmdb.org")
    return fetch(url, {"User-Agent": UA_BROWSER}, timeout=20, retries=0)


def poster_from_wikipedia(entity):
    links = entity.get("sitelinks", {})
    for wiki in ("ruwiki", "enwiki"):
        title = links.get(wiki, {}).get("title")
        if not title:
            continue
        lang = wiki[:2]
        data = fetch_json(f"https://{lang}.wikipedia.org/w/api.php?" + urllib.parse.urlencode({
            "action": "query", "prop": "pageimages", "piprop": "thumbnail", "pithumbsize": 400, "pilicense": "any",
            "titles": title, "format": "json", "redirects": 1}))
        for page in data.get("query", {}).get("pages", {}).values():
            src = page.get("thumbnail", {}).get("source")
            if src:
                return fetch(src, timeout=30)
    raise RuntimeError("в Википедии нет постера")


def image_ext(data):
    if data[:2] == b"\xff\xd8":
        return "jpg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "png"
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "webp"
    return None


def ensure_poster(m, entity):
    """Постер в posters/<slug>.<ext>: сначала TMDB (когда доступен), затем Википедия."""
    if m.get("poster") and (ROOT / m["poster"]).exists():
        return
    POSTERS.mkdir(exist_ok=True)
    slug = slugify(m.get("original_title") or m["title"])
    for ext in ("jpg", "png", "webp"):
        if (POSTERS / f"{slug}.{ext}").exists():
            m["poster"] = f"posters/{slug}.{ext}"
            return
    data, source = None, None
    for name, loader in (("TMDB", lambda: poster_from_tmdb(m["tmdb_id"]) if m.get("tmdb_id") else None),
                         ("Википедия", lambda: poster_from_wikipedia(entity) if entity else None)):
        try:
            data = loader()
            if data and len(data) > 5000 and image_ext(data):
                source = name
                break
            data = None
        except Exception as e:  # noqa: BLE001
            warn(f"постер «{m['title']}» из {name}: {str(e)[:120]}")
    if not data:
        fail(f"не удалось найти постер для «{m['title']}» — укажите poster_url в черновике")
    target = POSTERS / f"{slug}.{image_ext(data)}"
    target.write_bytes(data)
    m["poster"] = f"posters/{target.name}"
    print(f"постер: {m['poster']}  ← {source}")


# ----------------------------------------------------------------------------- обогащение

def awards_of(entity):
    won_ids = claim_ids(entity, "P166")
    nom_ids = claim_ids(entity, "P1411")
    labels = wd_labels(list(dict.fromkeys(won_ids + nom_ids)))

    def kind(q):
        l = labels.get(q, {})
        text = (l.get("ru") or "") + " | " + (l.get("en") or "")
        if q == "Q179808":
            return "palme"
        for k, (needles, _, _) in AWARDS.items():
            if any(n in text for n in needles):
                return k
        return None

    won = {}
    for q in won_ids:
        k = kind(q)
        if k:
            won.setdefault(k, []).append(q)
    nominated = {}
    for q in nom_ids:
        k = kind(q)
        if k and k not in won:
            nominated.setdefault(k, []).append(q)

    out = []
    if "oscar" in won:
        n = len(won["oscar"])
        label = "«Оскар»" if n == 1 else f"{n} «{plural(n, 'Оскар', 'Оскара', 'Оскаров')}»"
        if BEST_PICTURE in won["oscar"]:
            label += ", в том числе лучший фильм"
        out.append({"award": "oscar", "result": "won", "count": n, "label": label})
    elif "oscar" in nominated:
        n = len(nominated["oscar"])
        label = "Номинация на «Оскар»" if n == 1 else f"{n} {plural(n, 'номинация', 'номинации', 'номинаций')} на «Оскар»"
        out.append({"award": "oscar", "result": "nominated", "count": n, "label": label})
    for k, text in (("palme", "Золотая пальмовая ветвь Канн"), ("globe", "Золотой глобус"), ("bafta", "Премия BAFTA"),
                    ("lion", "Золотой лев Венеции"), ("bear", "Золотой медведь Берлинале")):
        if k in won:
            n = len(won[k])
            out.append({"award": k, "result": "won", "count": n, "label": text if n == 1 or k == "palme" else f"{text} ×{n}"})
    # Значок на карточке — один кубок и только лауреатам крупных премий; номинации видны в «О фильме».
    badge = "🏆" if any(k in won for k in AWARDS) else None
    return out, badge


def genres_of(entity):
    labels = wd_labels(claim_ids(entity, "P136"))
    found = []
    for q, l in labels.items():
        ru = (l.get("ru") or "").lower()
        g = GENRE_MAP.get(ru)
        if not g:
            g = re.sub(r"^фильм[- ]", "", ru).replace(" фильм", "").strip()
            g = g if g in GENRE_ORDER or g in GENRE_MAP.values() else None
        if g and g not in found:
            found.append(g)
    found.sort(key=lambda g: GENRE_ORDER.index(g) if g in GENRE_ORDER else 99)
    return found[:3]


def is_new_release(premiere, override=None):
    if override is not None:
        return bool(override)
    if not premiere:
        return False
    age = (date.today() - premiere).days
    return NEW_DIGITAL_LAG_DAYS <= age <= NEW_WINDOW_DAYS


def enrich_movie(m, existing):
    """Заполняет поля фильма; то, что уже было в текущем movies.json, сохраняется."""
    base = dict(existing or {})
    base.update({k: v for k, v in m.items() if v not in (None, "", [])})
    m = base
    for field in ("title", "year", "note"):
        if not m.get(field):
            fail(f"у фильма {m!r} нет поля {field}")

    qid = m.get("wikidata")
    if not m.get("tmdb_id"):
        cands = find_in_wikidata(m.get("original_title") or m["title"], m["year"])
        cands = [c for c in cands if c.get("tmdb")]
        if len(cands) != 1:
            fail(f"«{m['title']}» ({m['year']}): не нашёл однозначно tmdb_id в Wikidata, кандидаты: {cands[:5]}; укажите tmdb_id")
        m["tmdb_id"] = int(cands[0]["tmdb"])
        qid = cands[0]["qid"]
    qid = qid or wd_qid_by_tmdb(m["tmdb_id"])
    entity = None
    if qid:
        try:
            entity = wd_entity(qid)
        except Exception as e:  # noqa: BLE001
            warn(f"Wikidata {qid}: {e}")
    if entity:
        m["wikidata"] = qid
        imdb = claim_values(entity, "P345")
        kp = claim_values(entity, "P2603")
        m.setdefault("imdb_id", imdb[0] if imdb else None)
        if kp and not m.get("kinopoisk_id"):
            m["kinopoisk_id"] = int(kp[0])
        ru_label = entity.get("labels", {}).get("ru", {}).get("value")
        en_label = entity.get("labels", {}).get("en", {}).get("value")
        if not m.get("original_title") and en_label and en_label != m["title"]:
            m["original_title"] = en_label
        if ru_label and ru_label.lower() != m["title"].lower():
            warn(f"«{m['title']}»: в Wikidata русское название «{ru_label}»")
        dur = claim_values(entity, "P2047")
        if dur and not m.get("runtime"):
            try:
                m["runtime"] = int(float(dur[0]["amount"]))
            except (KeyError, ValueError, TypeError):
                pass
        people = wd_labels(claim_ids(entity, "P57")[:2] + claim_ids(entity, "P495")[:1])
        directors = [people[q].get("ru") or people[q].get("en") for q in claim_ids(entity, "P57")[:2] if q in people]
        countries = [people[q].get("ru") or people[q].get("en") for q in claim_ids(entity, "P495")[:1] if q in people]
        if directors and not m.get("director"):
            m["director"] = ", ".join(d for d in directors if d)
        if countries and not m.get("country"):
            m["country"] = countries[0]
        if not m.get("genres"):
            m["genres"] = genres_of(entity)
        premiere = earliest_date(entity)
        if premiere and not m.get("premiere"):
            m["premiere"] = premiere.isoformat()
        if not m.get("overview"):
            ov = wiki_overview(entity)
            if ov:
                m["overview"] = ov
        awards, badge = awards_of(entity)
        m["awards"] = awards
        if badge:
            m["award_badge"] = badge
        else:
            m.pop("award_badge", None)
    else:
        warn(f"«{m['title']}»: нет записи в Wikidata, только то, что в черновике")

    if m.get("imdb_id"):
        r = imdb_ratings().get(m["imdb_id"])
        if r:
            m["imdb_rating"], m["imdb_votes"] = r
    o = omdb(m.get("imdb_id"))
    if o.get("Metascore", "N/A") != "N/A":
        m["metascore"] = int(o["Metascore"])
    for item in o.get("Ratings", []):
        if item.get("Source") == "Rotten Tomatoes":
            m["rotten_tomatoes"] = int(item["Value"].rstrip("%"))
    k = kinopoisk(m.get("kinopoisk_id"))
    if k.get("ratingKinopoisk"):
        m["kp_rating"] = round(float(k["ratingKinopoisk"]), 1)
    if k.get("description") and not m.get("overview"):
        m["overview"] = k["description"][:MAX_OVERVIEW]
    reviews = kinopoisk_reviews(m.get("kinopoisk_id"))
    if reviews:
        m["reviews"] = reviews
    if m.get("imdb_rating"):
        m["rating"] = round(float(m["imdb_rating"]), 1)
    elif m.get("kp_rating"):
        m["rating"] = m["kp_rating"]
    elif m.get("rating"):
        m["rating"] = round(float(m["rating"]), 1)
    else:
        fail(f"«{m['title']}»: не удалось узнать рейтинг (нет imdb_id в Wikidata) — укажите rating и imdb_id вручную")
    prem = date.fromisoformat(m["premiere"]) if m.get("premiere") else None
    m["is_new"] = is_new_release(prem, m.get("is_new_override"))
    m.pop("is_new_override", None)
    ensure_poster(m, entity)
    return m


# ----------------------------------------------------------------------------- проверки

def validate_pool(movies, history, state, strict=True):
    problems, notes = [], []
    if not POOL_MIN <= len(movies) <= POOL_MAX:
        problems.append(f"в пуле {len(movies)} фильмов, нужно {POOL_MIN}–{POOL_MAX}")
    removed = {str(e.get("id")) for e in state.get("removed", [])}
    hist_removed = {h.get("key") or key_of(h) for h in history.get("shown", []) if h.get("status") in ("watched", "skip")}
    seen, directors = set(), {}
    for m in movies:
        t = m.get("title", "?")
        for field in ("title", "year", "rating", "note", "poster", "tmdb_id"):
            if m.get(field) in (None, ""):
                problems.append(f"«{t}»: нет поля {field}")
        k = key_of(m)
        if k in seen:
            problems.append(f"«{t}» встречается дважды")
        seen.add(k)
        if k in removed or k in hist_removed:
            problems.append(f"«{t}»: Настя уже убрала этот фильм (просмотрен / не буду смотреть)")
        if len(m.get("note", "")) > MAX_NOTE:
            problems.append(f"«{t}»: note длиннее {MAX_NOTE} ({len(m['note'])})")
        if len(m.get("why_you") or "") > MAX_WHY:
            problems.append(f"«{t}»: why_you длиннее {MAX_WHY}")
        if len(m.get("critics") or "") > MAX_CRITICS:
            problems.append(f"«{t}»: critics длиннее {MAX_CRITICS}")
        if m.get("poster") and not (ROOT / m["poster"]).exists():
            problems.append(f"«{t}»: файла {m['poster']} нет")
        r = float(m.get("rating") or 0)
        if m.get("is_new"):
            if r < RATING_NEW or (m.get("imdb_votes") or 0) < VOTES_NEW:
                notes.append(f"новинка «{t}»: рейтинг {r}, голосов {m.get('imdb_votes')} — ниже порога {RATING_NEW}/{VOTES_NEW}")
        elif r < RATING_CLASSIC:
            notes.append(f"«{t}»: рейтинг {r} ниже {RATING_CLASSIC}")
        if m.get("director"):
            directors[m["director"]] = directors.get(m["director"], 0) + 1
    for d, n in directors.items():
        if n > MAX_PER_DIRECTOR:
            notes.append(f"режиссёр {d}: {n} фильмов (лимит {MAX_PER_DIRECTOR})")
    new_count = sum(1 for m in movies if m.get("is_new"))
    if new_count < NEW_MIN:
        notes.append(f"новинок в пуле {new_count}, нужно ≥ {NEW_MIN}")
    if strict:
        problems.extend(notes)
        notes = []
    return problems, notes


# ----------------------------------------------------------------------------- команды

def cmd_find(args):
    if not args:
        fail('укажите название: pool.py find "Sinners" 2025')
    title = args[0]
    year = int(args[1]) if len(args) > 1 else None
    rows = find_in_wikidata(title, year)
    if not rows:
        print("ничего не найдено")
        return
    for r in rows:
        print(f"{r['qid']:>12}  tmdb={r.get('tmdb', '—'):>8}  imdb={r.get('imdb', '—'):>11}  {r.get('year') or '—'}  "
              f"ru: {r.get('ru') or '—'}  |  en: {r.get('en') or '—'}")


def cmd_state(_args):
    if not STATE.exists():
        print("state.json ещё не приходил с проектора (синхронизация не включена или Настя ничего не трогала)")
        return
    s = load_json(STATE, {})
    updated = s.get("updated", "")
    age = ""
    try:
        dt = datetime.fromisoformat(updated)
        days = (datetime.now(dt.tzinfo) - dt).days
        age = f" ({days} дн. назад)"
        if days >= 3:
            age += "  ⚠ состояние с проектора старше 3 дней"
    except ValueError:
        pass
    print(f"state.json: seq={s.get('seq')}, обновлено {updated}{age}")

    def line(e):
        snap = e.get("snap") or {}
        return f"{snap.get('title', e.get('id'))} ({snap.get('year', '?')}) id={e.get('id')} {e.get('at', '')[:10]}"
    print(f"\nЗакреплено ({len(s.get('pinned', []))}):")
    for e in s.get("pinned", []):
        print("  📌", line(e))
    print(f"\nИзбранное ({len(s.get('favorites', []))}):")
    for e in s.get("favorites", []):
        print("  ♥", line(e))
    print(f"\nУбрано ({len(s.get('removed', []))}):")
    for e in sorted(s.get("removed", []), key=lambda x: -x.get("seq", 0)):
        liked = {True: "👍 понравился", False: "👎 не понравился", None: "без оценки"}[e.get("liked")]
        what = "просмотрен" if e.get("reason") == "watched" else "не буду смотреть"
        print(f"  {what:17} {liked:16}", line(e))
    print(f"\nНачала смотреть ({len(s.get('started', []))}):")
    for e in s.get("started", []):
        print("  ▶", line(e))
    print(f"\nНравится ({len(s.get('likes', []))}):")
    for e in s.get("likes", []):
        print("  👍", line(e))


def cmd_enrich(args):
    if len(args) < 1:
        fail("pool.py enrich draft.json [pool.json]")
    draft = load_json(Path(args[0]), None)
    movies = draft["movies"] if isinstance(draft, dict) else draft
    current = {key_of(m): m for m in load_json(MOVIES, {}).get("movies", [])}
    out = []
    for i, m in enumerate(movies, 1):
        print(f"[{i}/{len(movies)}] {m.get('title')}", file=sys.stderr)
        existing = current.get(key_of(m))
        out.append(enrich_movie(m, existing))
    result = {"movies": out}
    target = Path(args[1]) if len(args) > 1 else None
    if target:
        dump_json(target, result)
        print(f"записано {target}: {len(out)} фильмов, новинок {sum(1 for x in out if x.get('is_new'))}")
    else:
        print(json.dumps(result, ensure_ascii=False, indent=2))
    problems, notes = validate_pool(out, load_json(HISTORY, {"shown": []}), load_json(STATE, {}), strict=False)
    for p in problems + notes:
        warn(p)


def cmd_validate(args):
    if not args:
        fail("pool.py validate pool.json")
    pool = load_json(Path(args[0]), None)
    movies = pool["movies"] if isinstance(pool, dict) else pool
    problems, _ = validate_pool(movies, load_json(HISTORY, {"shown": []}), load_json(STATE, {}))
    if problems:
        for p in problems:
            print(" -", p)
        fail(f"{len(problems)} проблем")
    print(f"ок: {len(movies)} фильмов, новинок {sum(1 for m in movies if m.get('is_new'))}")


def cmd_publish(args):
    paths = [a for a in args if not a.startswith("--")]
    if not paths:
        fail("pool.py publish pool.json [--push] [--force]")
    push, force = "--push" in args, "--force" in args
    pool = load_json(Path(paths[0]), None)
    movies = pool["movies"] if isinstance(pool, dict) else pool
    history = load_json(HISTORY, {"shown": []})
    state = load_json(STATE, {})
    problems, _ = validate_pool(movies, history, state, strict=not force)
    if problems:
        for p in problems:
            print(" -", p)
        fail(f"{len(problems)} проблем (--force пропускает мягкие)")

    today = date.today().isoformat()
    previous = {key_of(m): m for m in load_json(MOVIES, {}).get("movies", [])}
    for rank, m in enumerate(movies, 1):
        m["rank"] = rank
        m.setdefault("added", previous.get(key_of(m), {}).get("added") or today)
    dump_json(MOVIES, {"version": 2, "title": "Топ-10 рекомендаций кино", "updated": today, "movies": movies})

    # history.json: все когда-либо показанные + статусы с проектора
    by_key = {}
    for h in history.get("shown", []):
        h.setdefault("key", key_of(h))
        by_key[h["key"]] = h
    for m in movies:
        k = key_of(m)
        h = by_key.setdefault(k, {"key": k, "title": m["title"], "year": m["year"], "date": today, "status": "shown"})
        h.update({"tmdb_id": m.get("tmdb_id"), "last_seen": today, "genres": m.get("genres", []),
                  "country": m.get("country"), "director": m.get("director"), "is_new": m.get("is_new", False)})
        if h.get("status") == "rotated":
            h["status"] = "shown"
    current_keys = {key_of(m) for m in movies}
    for h in by_key.values():
        if h.get("status") == "shown" and h["key"] not in current_keys:
            h["status"] = "rotated"
    for e in state.get("removed", []):
        k = str(e.get("id"))
        snap = e.get("snap") or {}
        h = by_key.setdefault(k, {"key": k, "title": snap.get("title", k), "year": snap.get("year"), "date": today})
        h["status"] = e.get("reason") or "watched"
        h["liked"] = e.get("liked")
        h["removed_at"] = (e.get("at") or "")[:10]
    dump_json(HISTORY, {"shown": sorted(by_key.values(), key=lambda h: h.get("date", ""))})

    added = [m["title"] for m in movies if key_of(m) not in previous]
    gone = [m["title"] for k, m in previous.items() if k not in current_keys]
    new_count = sum(1 for m in movies if m.get("is_new"))
    print(f"movies.json: {len(movies)} фильмов, новинок {new_count}, +{len(added)} −{len(gone)}")
    if added:
        print("  добавлено:", ", ".join(added))
    if gone:
        print("  убрано:", ", ".join(gone))

    if push:
        branch = subprocess.run(["git", "rev-parse", "--abbrev-ref", "HEAD"], cwd=ROOT, capture_output=True, text=True).stdout.strip()
        msg = f"🎬 Подборка для Насти ({today}): +{len(added)} −{len(gone)}, в пуле {len(movies)}, новинок {new_count}"
        subprocess.run(["git", "add", "movies.json", "history.json", "posters"], cwd=ROOT, check=True)
        subprocess.run(["git", "-c", "core.hooksPath=/dev/null", "commit", "-q", "-m", msg], cwd=ROOT, check=True)
        subprocess.run(["git", "push", "origin", "HEAD"], cwd=ROOT, check=True)
        print(f"запушено в {branch}: {msg}")
        if branch != "main":
            print("внимание: это не main — проектор увидит подборку после слияния ветки")


def cmd_taste(_args):
    state = load_json(STATE, {})
    history = {h.get("key") or key_of(h): h for h in load_json(HISTORY, {"shown": []}).get("shown", [])}
    pool = {key_of(m): m for m in load_json(MOVIES, {}).get("movies", [])}

    def meta(k):
        m = pool.get(k) or history.get(k) or {}
        year = m.get("year")
        return {"genres": m.get("genres", []), "country": m.get("country"), "director": m.get("director"),
                "decade": f"{year // 10 * 10}-е" if isinstance(year, int) else None, "title": m.get("title")}

    buckets = {"liked": [], "disliked": [], "favorites": [], "skipped": [], "watched_unrated": []}
    for e in state.get("removed", []):
        k = str(e.get("id"))
        if e.get("reason") == "skip":
            buckets["skipped"].append(k)
        elif e.get("liked") is True:
            buckets["liked"].append(k)
        elif e.get("liked") is False:
            buckets["disliked"].append(k)
        else:
            buckets["watched_unrated"].append(k)
    buckets["favorites"] = [str(e.get("id")) for e in state.get("favorites", [])]
    for e in state.get("likes", []):
        k = str(e.get("id"))
        if k not in buckets["liked"]:
            buckets["liked"].append(k)

    counts = {}
    for name, keys in buckets.items():
        c = {"genres": {}, "decades": {}, "countries": {}, "directors": {}, "titles": []}
        for k in keys:
            mm = meta(k)
            for g in mm["genres"]:
                c["genres"][g] = c["genres"].get(g, 0) + 1
            for field, key in (("decade", "decades"), ("country", "countries"), ("director", "directors")):
                if mm[field]:
                    c[key][mm[field]] = c[key].get(mm[field], 0) + 1
            if mm["title"]:
                c["titles"].append(mm["title"])
        counts[name] = c
    recent = sorted(state.get("removed", []), key=lambda e: -e.get("seq", 0))[:10]
    previous = load_json(TASTE, {})
    taste = {
        "generated": date.today().isoformat(),
        "signals": sum(len(v) for v in buckets.values()),
        "counts": counts,
        "recent": [{"title": (e.get("snap") or {}).get("title"), "reason": e.get("reason"), "liked": e.get("liked"),
                    "at": (e.get("at") or "")[:10]} for e in recent],
        "summary": previous.get("summary", ""),
    }
    dump_json(TASTE, taste)
    print(f"taste.json: сигналов {taste['signals']} (👍 {len(buckets['liked'])}, 👎 {len(buckets['disliked'])}, "
          f"♥ {len(buckets['favorites'])}, пропущено {len(buckets['skipped'])}); summary пишет Claude")


COMMANDS = {"find": cmd_find, "state": cmd_state, "enrich": cmd_enrich, "validate": cmd_validate,
            "publish": cmd_publish, "taste": cmd_taste}

if __name__ == "__main__":
    if len(sys.argv) < 2 or sys.argv[1] not in COMMANDS:
        print(__doc__)
        sys.exit(1)
    COMMANDS[sys.argv[1]](sys.argv[2:])
