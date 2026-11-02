#!/usr/bin/env python3
"""
Публикует новую подборку фильмов для домашнего экрана Насти.

Использование:
    python3 scripts/publish.py draft.json            # проверить, скачать постеры, записать movies.json
    python3 scripts/publish.py draft.json --push     # ... и закоммитить + запушить в GitHub

draft.json — объект {"movies": [...]} или просто массив из 10 фильмов. У каждого фильма:
    title (рус.), year, rating, note (<= 48 символов, почему стоит посмотреть), tmdb_id
    опционально: original_title, kinopoisk_id, poster (относительный путь), poster_url (абсолютный URL)

Скрипт:
  * проверяет, что фильмов ровно 10 и что их нет в history.json (уже показывали раньше);
  * для фильмов без постера берёт og:image со страницы https://www.themoviedb.org/movie/<tmdb_id>
    и кладёт картинку в posters/<slug>.jpg (ширина 342px);
  * пишет movies.json с полем updated = сегодня, дописывает history.json;
  * с --push коммитит с заметным сообщением и пушит в origin/main.
"""
import json
import re
import subprocess
import sys
import unicodedata
import urllib.request
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
POSTERS = ROOT / "posters"
MOVIES = ROOT / "movies.json"
HISTORY = ROOT / "history.json"
UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Safari/537.36"
MAX_NOTE = 48


def fail(msg: str) -> None:
    print("ОШИБКА:", msg, file=sys.stderr)
    sys.exit(1)


def slugify(text: str) -> str:
    text = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    text = re.sub(r"[^a-zA-Z0-9]+", "_", text).strip("_").lower()
    return text or "poster"


def fetch(url: str, timeout: int = 30) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept-Language": "ru-RU,ru;q=0.9"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return resp.read()


def tmdb_poster(tmdb_id: int) -> str:
    html = fetch(f"https://www.themoviedb.org/movie/{tmdb_id}?language=ru-RU").decode("utf-8", "ignore")
    m = re.search(r'<meta property="og:image" content="([^"]+)"', html)
    if not m:
        raise RuntimeError(f"og:image не найден на странице TMDB {tmdb_id}")
    url = m.group(1)
    # Берём размер w342: достаточно для карточки, экономит трафик проектора.
    return re.sub(r"/t/p/[^/]+/", "/t/p/w342/", url).replace("media.themoviedb.org", "image.tmdb.org")


def load_history() -> dict:
    if HISTORY.exists():
        return json.loads(HISTORY.read_text(encoding="utf-8"))
    return {"shown": []}


def key_of(m: dict) -> str:
    return f"{m.get('title', '').strip().lower()}|{m.get('year', '')}"


def main() -> None:
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    push = "--push" in sys.argv
    allow_repeat = "--allow-repeat" in sys.argv
    if not args:
        fail("укажите draft.json")
    draft = json.loads(Path(args[0]).read_text(encoding="utf-8"))
    movies = draft["movies"] if isinstance(draft, dict) else draft
    if len(movies) != 10:
        fail(f"нужно ровно 10 фильмов, а в черновике {len(movies)}")

    history = load_history()
    shown = {key_of(m) for m in history["shown"]}
    seen = set()
    for m in movies:
        for field in ("title", "year", "rating", "note"):
            if field not in m or m[field] in ("", None):
                fail(f"у фильма {m!r} нет поля {field}")
        if not isinstance(m["year"], int) or not (1900 <= m["year"] <= date.today().year + 1):
            fail(f"странный год у «{m['title']}»: {m['year']}")
        m["rating"] = round(float(m["rating"]), 1)
        if not (1.0 <= m["rating"] <= 10.0):
            fail(f"рейтинг вне диапазона у «{m['title']}»")
        if len(m["note"]) > MAX_NOTE:
            fail(f"note у «{m['title']}» длиннее {MAX_NOTE} символов ({len(m['note'])}): сократите")
        k = key_of(m)
        if k in seen:
            fail(f"«{m['title']}» встречается дважды")
        seen.add(k)
        if k in shown and not allow_repeat:
            fail(f"«{m['title']}» ({m['year']}) уже был в подборке раньше, подберите другой фильм (или --allow-repeat)")

    POSTERS.mkdir(exist_ok=True)
    for m in movies:
        if m.get("poster_url") or m.get("poster"):
            if m.get("poster") and not (ROOT / m["poster"]).exists():
                fail(f"файл постера {m['poster']} не найден")
            continue
        if not m.get("tmdb_id"):
            fail(f"у «{m['title']}» нет ни постера, ни tmdb_id")
        slug = slugify(m.get("original_title") or m["title"])
        target = POSTERS / f"{slug}.jpg"
        if not target.exists():
            url = tmdb_poster(int(m["tmdb_id"]))
            data = fetch(url)
            if len(data) < 5000 or data[:2] != b"\xff\xd8":
                fail(f"постер для «{m['title']}» не похож на JPEG ({url})")
            target.write_bytes(data)
            print(f"постер: {target.relative_to(ROOT)}  <-  {url}")
        m["poster"] = f"posters/{target.name}"

    today = date.today().isoformat()
    out = {"title": "Топ-10 рекомендаций кино", "updated": today, "movies": movies}
    MOVIES.write_text(json.dumps(out, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    history["shown"].extend({"title": m["title"], "year": m["year"], "date": today} for m in movies if key_of(m) not in shown)
    HISTORY.write_text(json.dumps(history, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"movies.json обновлён: {len(movies)} фильмов, updated={today}")

    if push:
        titles = ", ".join(m["title"] for m in movies[:3])
        msg = f"🎬 Новая десятка для Насти ({today}): {titles} и ещё 7"
        subprocess.run(["git", "add", "movies.json", "history.json", "posters"], cwd=ROOT, check=True)
        subprocess.run(["git", "commit", "-m", msg], cwd=ROOT, check=True)
        subprocess.run(["git", "push", "origin", "HEAD:main"], cwd=ROOT, check=True)
        print("запушено:", msg)


if __name__ == "__main__":
    main()
