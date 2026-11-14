#!/usr/bin/env python3
"""Build and check what the app streams the overview imagery from.

  python3 -I publish.py build ZIP_DIR ASSETS_DIR OUT_DIR
      From the six original overview ZIPs (checked against overview-archives.json,
      their payloads against overview-index.json): OUT_DIR/packs/<zip> (the ZIPs as
      they are, for "Download all") and OUT_DIR/tiles/<payload>/<offset>.webp, every
      packed image byte for byte (OverviewStream.tileUrl). Upload OUT_DIR as it is.

  python3 -I publish.py check BASE_URL ASSETS_DIR ZIP_DIR [all]
      Fetches tiles (a sample, or all) and the packs' first bytes from BASE_URL and
      compares them with the ZIPs.

The imagery is not changed: no re-encoding, resampling or renaming of pixels.
"""
import hashlib, json, os, random, shutil, struct, sys, urllib.request


def payloads(zip_dir, assets):
    archives = {f["name"]: f for f in json.load(open(os.path.join(assets, "overview-archives.json")))["files"]}
    index = {f["sha256"]: f["bytes"] for f in json.load(open(os.path.join(assets, "overview-index.json")))["files"]}
    out = {}
    for name, rec in sorted(archives.items()):
        path = os.path.join(zip_dir, name)
        data = open(path, "rb").read()
        if len(data) != rec["bytes"] or hashlib.sha256(data).hexdigest() != rec["sha256"]:
            sys.exit(f"{name}: not the original ZIP")
        sig, _, flags, method = struct.unpack("<IHHH", data[:10])
        size = struct.unpack("<I", data[22:26])[0]
        nl, xl = struct.unpack("<HH", data[26:30])
        if sig != 0x04034B50 or method != 0 or flags & 1 or data[30:30 + nl].decode() != rec["payload"] + ".bin":
            sys.exit(f"{name}: unexpected layout")
        body = data[30 + nl + xl:30 + nl + xl + size]
        if hashlib.sha256(body).hexdigest() != rec["payload"] or index.get(rec["payload"]) != len(body):
            sys.exit(f"{name}: payload does not match the index")
        out[rec["payload"]] = (name, path, body)
    return out


def rows(assets):
    tiles = json.load(open(os.path.join(assets, "overview-index.json")))["tiles"]
    seen = {}
    for e in tiles.values():
        for v in ("natural", "raw"):
            r = e.get(v, e)
            key = (r["file"], r["offset"])
            if seen.setdefault(key, r["bytes"]) != r["bytes"]:
                sys.exit(f"{key}: two lengths")
    return seen


def build(zip_dir, assets, out):
    p = payloads(zip_dir, assets)
    os.makedirs(os.path.join(out, "packs"), exist_ok=True)
    for name, path, _ in p.values():
        shutil.copyfile(path, os.path.join(out, "packs", name))
    n = total = 0
    for (file, offset), length in rows(assets).items():
        img = p[file][2][offset:offset + length]
        if len(img) != length or img[:4] != b"RIFF" or img[8:12] != b"WEBP":
            sys.exit(f"{file}/{offset}: not a WebP image")
        d = os.path.join(out, "tiles", file)
        os.makedirs(d, exist_ok=True)
        open(os.path.join(d, f"{offset}.webp"), "wb").write(img)
        n += 1
        total += length
    print(f"{len(p)} packs, {n} tiles ({total / 1e6:.1f} MB) in {out}")


def get(url, headers=None):
    req = urllib.request.Request(url, headers={"User-Agent": "ridgeline-publish", **(headers or {})})
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.status, r.headers, r.read()


def check(base, assets, zip_dir, which="sample"):
    base = base.rstrip("/")
    p = payloads(zip_dir, assets)
    items = list(rows(assets).items())
    if which != "all":
        random.seed(1)
        items = random.sample(items, 200)
    bad = 0
    for (file, offset), length in items:
        status, headers, body = get(f"{base}/tiles/{file}/{offset}.webp")
        if status != 200 or body != p[file][2][offset:offset + length]:
            bad += 1
            print("BAD", file, offset, status, len(body))
    for name, path, _ in p.values():
        status, headers, body = get(f"{base}/packs/{name}", {"Range": "bytes=0-1023"})
        if status != 206 or body != open(path, "rb").read(1024):
            bad += 1
            print("BAD pack", name, status)
    print(f"checked {len(items)} tiles and {len(p)} packs at {base}: {bad} problems")
    return 1 if bad else 0


if __name__ == "__main__":
    cmd, *args = sys.argv[1:]
    sys.exit({"build": build, "check": check}[cmd](*args) or 0)
