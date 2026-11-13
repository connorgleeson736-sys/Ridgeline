#!/usr/bin/env python3
"""Review page for compiled walks: one map per walk (DOC's line grey, the main
way orange, other ways blue, huts red, start green), its status, flags and
notes, so every walk can be checked by eye.

Usage: python3 -I review.py DATA_DIR REPORT_JSON WALKS_JSON_GZ OUT_HTML [only-flagged|all]
"""
import gzip, html, json, math, os, sys


def decode(s):
    out, i, x, y = [], 0, 0, 0
    def nxt():
        nonlocal i
        r = shift = 0
        while True:
            c = ord(s[i]) - 63; i += 1
            r |= (c & 0x1f) << shift; shift += 5
            if c < 0x20:
                break
        return ~(r >> 1) if r & 1 else r >> 1
    while i < len(s):
        y += nxt(); x += nxt(); out.append((x / 1e6, y / 1e6))
    return out


def svg(doc, ways, huts, tried=(), w=320, h=220):
    pts = [p for l in doc for p in l] + [p for wy in ways for p in wy["pts"]] + [p for t in tried for p in t] + [(hh["lon"], hh["lat"]) for hh in huts]
    if not pts:
        return "<svg width='%d' height='%d'></svg>" % (w, h)
    lat0 = sum(p[1] for p in pts) / len(pts); k = math.cos(math.radians(lat0))
    xs = [p[0] * k for p in pts]; ys = [p[1] for p in pts]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    s = min((w - 20) / max(x1 - x0, 1e-6), (h - 20) / max(y1 - y0, 1e-6))
    def P(p):
        return f"{10 + (p[0] * k - x0) * s:.1f},{h - 10 - (p[1] - y0) * s:.1f}"
    out = [f"<svg viewBox='0 0 {w} {h}' width='{w}' height='{h}' role='img'><rect width='100%' height='100%' fill='var(--map)'/>"]
    for l in doc:
        out.append(f"<polyline points='{' '.join(P(p) for p in l)}' fill='none' stroke='var(--doc)' stroke-width='6' stroke-linejoin='round' opacity='.6'/>")
    for t in tried:
        out.append(f"<polyline points='{' '.join(P(p) for p in t)}' fill='none' stroke='var(--flag)' stroke-width='1.6' stroke-dasharray='4 3'/>")
    for wy in sorted(ways, key=lambda q: q["main"]):
        col = "var(--main)" if wy["main"] else "var(--alt)"
        out.append(f"<polyline points='{' '.join(P(p) for p in wy['pts'])}' fill='none' stroke='{col}' stroke-width='{2.2 if wy['main'] else 1.6}' stroke-linejoin='round'/>")
        if wy["pts"]:
            out.append(f"<circle cx='{P(wy['pts'][0]).split(',')[0]}' cy='{P(wy['pts'][0]).split(',')[1]}' r='3.5' fill='var(--start)'/>")
    for hh in huts:
        c = P((hh["lon"], hh["lat"])).split(",")
        out.append(f"<rect x='{float(c[0]) - 3}' y='{float(c[1]) - 3}' width='6' height='6' fill='var(--hut)'><title>{html.escape(hh['name'])}</title></rect>")
    out.append("</svg>")
    return "".join(out)


def main(data, report, asset, out, which="all"):
    rep = json.load(open(report))
    recs = json.loads(gzip.open(asset).read())["walks"]
    rows = []
    n_ok = sum(1 for r in rep if r["status"] == "verified")
    for r in sorted(rep, key=lambda r: (r["status"] == "verified", r["name"])):
        if which == "only-flagged" and r["status"] == "verified":
            continue
        g = r["guid"].replace("-", "")
        lp = os.path.join(data, "lines", g + ".json")
        doc = []
        if os.path.exists(lp):
            for f in json.load(open(lp)).get("features", []):
                doc += [[tuple(p) for p in path] for path in (f.get("geometry") or {}).get("paths", [])]
        rec = recs.get(r["guid"], {})
        ways = [{"main": w["main"], "pts": decode(w["line"])} for w in rec.get("ways", [])]
        wl = "".join(f"<li><b>{html.escape(w['label'])}</b> · {w['shape'] or '?'} · {w['km']:.2f} km{' · main' if w['main'] else ''}</li>" for w in r["ways"])
        fl = "".join(f"<li class='flag'>{html.escape(f)}</li>" for f in r["flags"])
        nt = "".join(f"<li class='note'>{html.escape(n)}</li>" for n in r["notes"])
        huts = ", ".join(html.escape(x) for x in r["huts"]) or "none"
        rows.append(f"""<article class='walk {r['status']}' data-name='{html.escape(r['name'].lower())}'>
<div class='map'>{svg(doc, ways, rec.get('huts', []), [decode(a["line"]) for a in r.get("attempted", [])])}</div>
<div class='info'><h2><a href='https://www.doc.govt.nz/link/{g}.aspx'>{html.escape(r['name'])}</a> <span class='badge'>{r['status']}</span></h2>
<p class='meta'>DOC line {r['docLineKm']:.1f} km · huts: {huts}</p><ul>{wl}{fl}{nt}</ul></div></article>""")
    page = f"""<!doctype html><html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>
<title>Walk Compile Review</title><style>
:root{{--bg:#f6f5f1;--fg:#1f2421;--muted:#66706a;--card:#fff;--line:#e2e0d8;--map:#eef1ea;--doc:#9aa39c;--main:#e0661b;--alt:#2f6fd1;--hut:#c43a2f;--start:#2e8b57;--flag:#a3321f}}
@media (prefers-color-scheme:dark){{:root:not([data-theme=light]){{--bg:#141716;--fg:#e8ebe8;--muted:#9aa49e;--card:#1d2120;--line:#2c3230;--map:#232927;--doc:#5c6661;--flag:#ff8a75}}}}
:root[data-theme=dark]{{--bg:#141716;--fg:#e8ebe8;--muted:#9aa49e;--card:#1d2120;--line:#2c3230;--map:#232927;--doc:#5c6661;--flag:#ff8a75}}
body{{margin:0;background:var(--bg);color:var(--fg);font:14px/1.45 system-ui,sans-serif}}
header{{padding:16px;border-bottom:1px solid var(--line)}}h1{{margin:0 0 4px;font-size:20px}}
input{{width:100%;max-width:420px;padding:8px 10px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--fg)}}
main{{padding:12px 16px;display:grid;gap:12px}}.walk{{display:flex;flex-wrap:wrap;gap:12px;background:var(--card);border:1px solid var(--line);border-radius:10px;padding:10px}}
.map svg{{max-width:100%;height:auto;border-radius:6px}}.info{{flex:1;min-width:240px}}h2{{font-size:15px;margin:2px 0}}h2 a{{color:inherit}}
.badge{{font-size:11px;padding:2px 7px;border-radius:9px;background:var(--map);color:var(--muted)}}.flagged .badge{{color:var(--flag)}}
.meta{{color:var(--muted);margin:2px 0 6px}}ul{{margin:0;padding-left:18px}}.flag{{color:var(--flag)}}.note{{color:var(--muted)}}
</style></head><body><header><h1>Walk compile review</h1><p class='meta'>{len(rep)} walks · {n_ok} verified · {len(rep) - n_ok} flagged. Grey: DOC's line · orange: main way · blue: other ways · green: start · red squares: huts · dashed red: a route that failed its checks.</p>
<input id='q' placeholder='Filter by name' oninput="for(const a of document.querySelectorAll('.walk'))a.style.display=a.dataset.name.includes(this.value.toLowerCase())?'':'none'"></header>
<main>{''.join(rows)}</main></body></html>"""
    open(out, "w").write(page)
    print(f"wrote {out}: {len(rows)} walks")


if __name__ == "__main__":
    main(*sys.argv[1:])
