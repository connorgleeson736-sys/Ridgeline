#!/usr/bin/env python3
"""Check walk readings (READING.md) against their packets.

Usage: python3 -I validate.py PACKETS_DIR READINGS_DIR [g ...]

A reading is accepted only when it has the exact shape READING.md gives,
every quote is found word for word in the packet's text (case, spacing and
quote marks aside), every ref is an id from the packet, and its ways are
consistent (a loop ends where it starts). Prints one line per problem and a
summary; exit status 1 when any reading has a problem.
"""
import json, os, re, sys, unicodedata

KINDS = {"one_way", "out_and_back", "loop", "network", "unclear"}
SHAPES = {"one_way", "out_and_back", "loop", None}
WHY = {"view", "nearby", "other walk", "side trip", "other"}
# Where `how` may appear (READING.md rules 7 and 10), and its values.
HOW = {"via": {"ford", "road"}, "exits": {"fly", "boat", "track", "road", "other"}}


def norm(s):
    s = unicodedata.normalize("NFKD", s or "")
    s = "".join(c for c in s if not unicodedata.combining(c))
    s = s.replace("’", "'").replace("‘", "'").replace("“", '"').replace("”", '"').replace("–", "-").replace("—", "-")
    return re.sub(r"\s+", " ", s).strip().lower()


def check(packet, reading):
    errs = []
    text = norm(" ".join(s["text"] for s in packet["sections"]) + " " + packet.get("name", ""))
    ids = {x["id"] for x in packet["links"]} | {x["id"] for x in packet["names"]} | {x["id"] for x in packet.get("features", [])}

    def place(p, where, nullable=False):
        if p is None:
            if not nullable:
                errs.append(f"{where}: missing")
            return
        if not isinstance(p, dict) or set(p) - {"name", "quote", "ref", "from", "why", "onto", "how"} or not {"name", "quote", "ref"} <= set(p):
            errs.append(f"{where}: needs name, quote, ref"); return
        q = p.get("quote")
        if not isinstance(q, str) or not q.strip():
            errs.append(f"{where}: empty quote")
        elif norm(q) not in text:
            errs.append(f"{where}: quote not in the page: {q!r}")
        if p.get("ref") is not None and p["ref"] not in ids:
            errs.append(f"{where}: unknown ref {p['ref']!r}")
        if "how" in p and p["how"] not in HOW.get(where.split(".")[-1].split("[")[0], set()):
            errs.append(f"{where}: how {p.get('how')!r}")

    if not isinstance(reading, dict):
        return ["not an object"]
    extra = set(reading) - {"guid", "kind", "ways", "access_points", "exits", "side_trips", "huts", "not_on_route", "doubts"}
    if extra:
        errs.append(f"unexpected fields {sorted(extra)}")
    if reading.get("guid") != packet["guid"]:
        errs.append("guid does not match the packet")
    if reading.get("kind") not in KINDS:
        errs.append(f"kind {reading.get('kind')!r}")
    ways = reading.get("ways")
    if not isinstance(ways, list):
        errs.append("ways is not a list"); ways = []
    if reading.get("kind") not in ("unclear", "network") and not ways:
        errs.append("no ways for a walk that is not unclear")
    if ways and sum(1 for w in ways if isinstance(w, dict) and w.get("main") is True) != 1:
        errs.append("exactly one way must be main")
    for i, w in enumerate(ways):
        at = f"ways[{i}]"
        if not isinstance(w, dict):
            errs.append(f"{at}: not an object"); continue
        extra = set(w) - {"id", "main", "label", "shape", "start", "end", "via", "direction", "time", "distance", "evidence"}
        for k in ("time", "distance"):
            v = w.get(k)
            if v is not None and (not isinstance(v, str) or norm(v) not in text + " " + norm(packet.get("time", "")) + " " + norm(packet.get("distance", ""))):
                errs.append(f"{at}: {k} not in the page: {v!r}")
        if extra:
            errs.append(f"{at}: unexpected fields {sorted(extra)}")
        if w.get("shape") not in SHAPES:
            errs.append(f"{at}: shape {w.get('shape')!r}")
        if w.get("direction") not in ("clockwise", "anticlockwise", None):
            errs.append(f"{at}: direction {w.get('direction')!r}")
        if not isinstance(w.get("label"), str) or not w["label"].strip():
            errs.append(f"{at}: label")
        place(w.get("start"), f"{at}.start", True)
        place(w.get("end"), f"{at}.end", True)
        for j, v in enumerate(w.get("via") or []):
            place(v, f"{at}.via[{j}]")
        ev = w.get("evidence")
        if not isinstance(ev, list) or not ev:
            errs.append(f"{at}: evidence missing")
        else:
            for e in ev:
                if not isinstance(e, str) or norm(e) not in text:
                    errs.append(f"{at}: evidence not in the page: {str(e)[:80]!r}")
        if w.get("shape") == "loop" and w.get("start") and w.get("end"):
            s, e = w["start"], w["end"]
            if (s.get("ref") or norm(s.get("name"))) != (e.get("ref") or norm(e.get("name"))):
                errs.append(f"{at}: a loop must end where it starts")
        if w.get("direction") and w.get("shape") != "loop":
            errs.append(f"{at}: direction is only for loops")
    for key in ("access_points", "exits", "side_trips", "huts", "not_on_route"):
        v = reading.get(key, [] if key == "exits" else None)
        if not isinstance(v, list):
            errs.append(f"{key} is not a list"); continue
        for j, p in enumerate(v):
            place(p, f"{key}[{j}]")
            if key == "not_on_route" and isinstance(p, dict) and p.get("why") not in WHY:
                errs.append(f"{key}[{j}]: why {p.get('why')!r}")
            if key == "exits" and isinstance(p, dict) and (p.get("how") not in HOW["exits"] or "from" not in p):
                errs.append(f"{key}[{j}]: needs from and how")
    if not isinstance(reading.get("doubts"), str):
        errs.append("doubts is not a string")
    return errs


def main(packets, readings, only):
    names = only or sorted(f[:-5] for f in os.listdir(readings) if f.endswith(".json") and not f.startswith("_"))
    bad = 0
    for g in names:
        rp = os.path.join(readings, g + ".json")
        if not os.path.exists(rp):
            print(f"{g}: no reading"); bad += 1; continue
        try:
            reading = json.load(open(rp))
        except Exception as e:
            print(f"{g}: invalid JSON: {e}"); bad += 1; continue
        errs = check(json.load(open(os.path.join(packets, g + ".json"))), reading)
        for e in errs:
            print(f"{g}: {e}")
        bad += bool(errs)
    print(f"checked {len(names)}, with problems {bad}")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2], sys.argv[3:]))
