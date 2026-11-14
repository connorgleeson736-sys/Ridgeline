#!/usr/bin/env python3
"""Choose each walk's reading between an older and a newer set, by the compile
checks, and keep huts the older one names that the chosen route passes.

Usage: python3 -I merge.py OLD_DIR OLD_REPORT OLD_ASSET NEW_DIR NEW_REPORT NEW_ASSET GOLD_DIR OUT_DIR

Each set is compiled first (WalkCompileTest: REPORT and ASSET). Per walk:
hand readings (GOLD_DIR) are used as they are; otherwise the newer reading,
unless only the older one passes the checks. Huts the older reading names that
the newer one lacks are added when the newer compiled route (or the line shown
for a flagged walk) passes within 600 m of them.
"""
import json, math, os, re, shutil, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from review import decode  # noqa: E402


def seg(p, a, b):
    k = math.cos(math.radians(p[1]))
    ax, ay, bx, by, px, py = a[0] * k, a[1], b[0] * k, b[1], p[0] * k, p[1]
    l = (bx - ax) ** 2 + (by - ay) ** 2
    t = 0 if l == 0 else max(0, min(1, ((px - ax) * (bx - ax) + (py - ay) * (by - ay)) / l))
    return math.hypot(ax + (bx - ax) * t - px, ay + (by - ay) * t - py) * 111320


def key(n):
    return re.sub(r"[^a-z0-9]", "", re.sub(r"\b(hut|huts|bivvy|biv|bivouac|lodge|shelter)\b", "", n.lower()))


def main(old_dir, old_report, old_asset, new_dir, new_report, new_asset, gold_dir, out):
    ro = {x["guid"].replace("-", ""): x for x in json.load(open(old_report))}
    rn = {x["guid"].replace("-", ""): x for x in json.load(open(new_report))}
    wo = {k.replace("-", ""): v for k, v in json.load(open(old_asset))["walks"].items()}
    wn = {k.replace("-", ""): v for k, v in json.load(open(new_asset))["walks"].items()}
    gold = {f[:-5] for f in os.listdir(gold_dir) if f.endswith(".json")}
    shutil.rmtree(out, ignore_errors=True)
    os.makedirs(out)
    names = {f[:-5].lstrip("_") for d in (old_dir, new_dir) for f in os.listdir(d) if f.endswith(".json")} | gold
    for g in sorted(names):
        if g in gold:
            shutil.copy(os.path.join(gold_dir, g + ".json"), os.path.join(out, g + ".json"))
            print(g, "hand")
            continue
        newp, oldp = os.path.join(new_dir, g + ".json"), os.path.join(old_dir, g + ".json")
        vn = rn.get(g, {}).get("status") == "verified"
        vo = ro.get(g, {}).get("status") == "verified"
        use = "new" if (vn or not vo) and os.path.exists(newp) else "old"
        r = json.load(open(newp if use == "new" else oldp))
        added = []
        if use == "new" and g in wo and g in wn and os.path.exists(oldp):
            main_way = [w for w in wn[g].get("ways", []) if w["main"]]
            line = decode(main_way[0]["line"]) if main_way else (decode(wn[g]["line"]) if wn[g].get("line") else [])
            have = {key(h["name"]) for h in wn[g].get("huts", [])} | {key(h["name"]) for h in r.get("huts", [])}
            old = json.load(open(oldp))
            for h in wo[g].get("huts", []):
                if key(h["name"]) in have or len(line) < 2:
                    continue
                if min(seg((h["lon"], h["lat"]), line[i], line[i + 1]) for i in range(len(line) - 1)) > 600:
                    continue
                e = [x for x in old.get("huts", []) if key(x["name"]) == key(h["name"]) or key(h["name"]) in key(x["name"]) or key(x["name"]) in key(h["name"])]
                if e:
                    r["huts"].append(e[0])
                    have.add(key(h["name"]))
                    added.append(h["name"])
        json.dump(r, open(os.path.join(out, g + ".json"), "w"), indent=1, ensure_ascii=False)
        print(g, use, ("+huts " + ", ".join(added)) if added else "")


if __name__ == "__main__":
    main(*sys.argv[1:])
