#!/usr/bin/env python3
"""Compare readings with hand-checked gold readings, field by field.

Usage: python3 -I compare.py GOLD_DIR READINGS_DIR
Prints each difference that changes the compiled walk (kind, ways, shapes,
starts, ends, places in order, huts, access points, direction)."""
import json, os, sys


def key(p):
    if not p:
        return None
    return p.get("ref") or ("name:" + (p.get("name") or "").lower())


def summary(r):
    ways = r.get("ways", [])
    main = next((w for w in ways if w.get("main")), {})
    return {
        "kind": r.get("kind"),
        "ways": len(ways),
        "main.shape": main.get("shape"),
        "main.start": key(main.get("start")),
        "main.end": key(main.get("end")),
        "main.via(ref)": [v.get("ref") for v in main.get("via", []) if v.get("ref")],
        "main.direction": main.get("direction"),
        "other ways": sorted((key(w.get("start")), key(w.get("end")), w.get("shape")) for w in ways if not w.get("main")),
        "huts(ref)": sorted(h.get("ref") or "name:" + h["name"].lower() for h in r.get("huts", [])),
        "access(ref)": sorted(a.get("ref") or "name:" + a["name"].lower() for a in r.get("access_points", [])),
    }


def main(gold, readings):
    total = diff = 0
    for f in sorted(os.listdir(gold)):
        if not f.endswith(".json"):
            continue
        rp = os.path.join(readings, f)
        if not os.path.exists(rp):
            continue
        g, r = summary(json.load(open(os.path.join(gold, f)))), summary(json.load(open(rp)))
        for k in g:
            total += 1
            if g[k] != r[k]:
                diff += 1
                print(f"{f[:8]} {k}: gold {g[k]!r} | reading {r[k]!r}")
    print(f"fields {total}, different {diff}")


if __name__ == "__main__":
    main(*sys.argv[1:])
