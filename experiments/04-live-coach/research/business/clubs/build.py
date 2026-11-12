"""Merge the research CSVs into clubs.csv and build index.html from index.template.html.

Usage: python3 build.py [raw.csv ...]
With no arguments it rebuilds index.html from the existing clubs.csv.
"""
import csv, json, re, sys
from pathlib import Path

HERE = Path(__file__).parent
COLS = ["name","city","district","type","offer","ages","tables","website","facebook","line","phone","email","source_url","notes"]
OUT_COLS = ["id","prio"] + COLS
CITY_ORDER = ["台北市","新北市","桃園市","台中市","臺南市","高雄市"]
CITY_CODE = {"台北市":"tpe","新北市":"ntpc","桃園市":"tyn","台中市":"txg","臺南市":"tnn","高雄市":"khh"}
TYPE_ORDER = ["chain","private studio","sports centre","school team","community club","association"]

def norm_city(c):
    c = c.strip().replace("臺北","台北").replace("臺中","台中").replace("台南","臺南")
    return c

def prio(r):
    t = r["type"]
    if r["city"] == "台北市" and t in ("private studio","chain"): return "A"
    if r["city"] == "台北市" or (r["city"] == "新北市" and t in ("private studio","chain")): return "B"
    return "C"

def load_raw(paths):
    rows, seen = [], set()
    for p in paths:
        with open(p, encoding="utf-8-sig", newline="") as f:
            for r in csv.DictReader(f):
                r = {k: (r.get(k) or "").strip() for k in COLS}
                if not r["name"] or not r["source_url"]:
                    continue
                r["city"] = norm_city(r["city"])
                r["type"] = r["type"].lower()
                key = (r["name"], r["city"])
                if key in seen: continue
                seen.add(key); rows.append(r)
    rows.sort(key=lambda r: (CITY_ORDER.index(r["city"]) if r["city"] in CITY_ORDER else 9,
                             TYPE_ORDER.index(r["type"]) if r["type"] in TYPE_ORDER else 9))
    counts = {}
    for r in rows:
        code = CITY_CODE.get(r["city"], "oth")
        counts[code] = counts.get(code, 0) + 1
        r["id"] = f"{code}-{counts[code]:02d}"
        r["prio"] = prio(r)
    with open(HERE/"clubs.csv", "w", encoding="utf-8-sig", newline="") as f:
        w = csv.DictWriter(f, fieldnames=OUT_COLS, quoting=csv.QUOTE_ALL)
        w.writeheader(); w.writerows(rows)

def messages():
    md = (HERE/"messages_zh.md").read_text(encoding="utf-8")
    out = []
    for title, body in re.findall(r"^## (.+?)\n(.*?)(?=^## |\Z)", md, re.S | re.M):
        if title[0] not in "ABD": continue
        lines = [l[2:] if l.startswith("> ") else ("" if l == ">" else None) for l in body.splitlines()]
        # keep only the first quoted block (the main message), plus subject line for email
        subj = re.search(r"\*\*主旨：\*\*\s*(.+)", body)
        block, started = [], False
        for l in lines:
            if l is None:
                if started: break
                continue
            started = True; block.append(l)
        text = ("主旨：" + subj.group(1) + "\n\n" if subj else "") + "\n".join(block).replace("**", "")
        out.append({"title": title.strip(), "text": text.strip()})
    return out

def build():
    with open(HERE/"clubs.csv", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    for i, r in enumerate(rows): r["idx"] = i
    tpl = (HERE/"index.template.html").read_text(encoding="utf-8")
    js = lambda o: json.dumps(o, ensure_ascii=False).replace("</", "<\\/")
    html = tpl.replace("/*CLUBS*/[]", js(rows)).replace("/*MSGS*/[]", js(messages()))
    (HERE/"index.html").write_text(html, encoding="utf-8")
    by = {}
    for r in rows: by[r["city"]] = by.get(r["city"], 0) + 1
    print(len(rows), "clubs", by)

if __name__ == "__main__":
    if len(sys.argv) > 1: load_raw(sys.argv[1:])
    build()
