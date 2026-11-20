#!/usr/bin/env python3
"""North Island overview imagery, made as the South Island's was
(scripts/imagery/south-island/pipeline.py, SOUTH_ISLAND_IMAGERY_STATUS.md), but
local: every stage writes to WORK and is resumable; publishing is publish.py's.

  python3 north_island.py mask    NE_COUNTRIES_GEOJSON
  python3 north_island.py fetch   [--workers 24]        (LINZ key: map-defaults.json)
  python3 north_island.py build   SI_ZIP_DIR            (the six original SI ZIPs)
  python3 north_island.py package ASSETS_DIR            (writes packs, index, coarse)

WORK is $RIDGE_NORTH_WORK. Method, as measured on the South Island's blend mask:
  * Coast: Natural Earth 1:10 m country polygons (public domain), the North Island
    and its coastal islands; mask at 16 px per zoom-13 tile, land widened by 21 px,
    Gaussian blur sigma 5, full alpha from 0.97 (fit to the South Island mask:
    mean alpha error 0.023 in its fade band). It only controls an offshore blend.
  * Sources: LINZ aerial (basemaps "aerial") zoom-13 tiles wherever the mask is
    not zero, 256 px, fetched at most 12 per second; LINZ's own tile at a coarser
    zoom wherever the mask leaves part of an output tile open.
  * Pyramid: zoom 12 from the zoom-13 sources with the mask as alpha, then each
    level from the four below: premultiplied 2x2 area mean, nearest-even rounding
    (the South Island's average()). A tile the mask covers fully is the overlay
    itself; otherwise it is laid over the South Island's finished tile where the
    South Island has one (tiles both islands share), else over LINZ's tile.
  * No enhancement, resampling of LINZ pixels or invented imagery. Lossless WebP,
    each round-tripped. Raw and natural colour differ only where a shared South
    Island tile differs (its West Coast Esri gaps); the North Island has no Esri.
"""
import argparse, collections, hashlib, io, json, math, os, struct, sys, threading, time, urllib.error, urllib.request, zipfile
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ASSETS = HERE.parents[1] / "android/app/src/main/assets"
WORK = Path(os.environ.get("RIDGE_NORTH_WORK", "north-island-work"))
M = 16                      # mask pixels per zoom-13 tile (as the South Island)
GUARD, SIGMA, FULL = 21, 5.0, 0.97
VERSION = "north-island-overviews-v1"
PACK_VERSION = "1.0"        # in the pack names: Ridgeline-Overviews-NI-1.0-N-of-M.zip
ROOT = HERE.parents[2]      # the repository: the South Island's own index lives in public/ and capacitor/
OVERVIEWS = HERE.parents[1] / "assets/overviews"
PACK_LIMIT = 62_000_000     # OverviewAttachments accepts ZIPs up to 63,000,000 bytes


def digest(b):
    return hashlib.sha256(b).hexdigest()


def atomic(p, b):
    p.parent.mkdir(parents=True, exist_ok=True)
    t = p.with_name(p.name + ".part")
    with t.open("wb") as f:
        f.write(b)
        f.flush()
        os.fsync(f.fileno())
    t.replace(p)


def log(**v):
    print(json.dumps(v, separators=(",", ":")), flush=True)


def tile_xy(lon, lat, z=13):
    n = 2 ** z
    return (lon + 180) / 360 * n, (1 - math.asinh(math.tan(math.radians(lat))) / math.pi) / 2 * n


# ---- 1. Mask -----------------------------------------------------------------
def north_polygons(path):
    nz = [f for f in json.load(open(path))["features"] if f["properties"].get("ADM0_A3") == "NZL"][0]
    out = []
    for poly in nz["geometry"]["coordinates"]:
        lons = [p[0] for p in poly[0]]
        lats = [p[1] for p in poly[0]]
        clat, clon = sum(lats) / len(lats), sum(lons) / len(lons)
        # The North Island and its coastal islands: north of Cook Strait, not the
        # Kermadecs, Chathams or the South Island (whose own mask exists).
        if -41.75 < clat < -34.0 and 172.0 < clon < 179.0:
            out.append(poly)
    return out


def stage_mask(ne_path):
    from scipy.ndimage import distance_transform_edt, gaussian_filter
    polys = north_polygons(ne_path)
    xs = [tile_xy(*p)[0] for poly in polys for p in poly[0]]
    ys = [tile_xy(*p)[1] for poly in polys for p in poly[0]]
    margin = 4  # tiles: covers the widening (21 px) and the blur's tail
    X0, Y0 = int(min(xs)) - margin, int(min(ys)) - margin
    X1, Y1 = int(max(xs)) + margin + 1, int(max(ys)) + margin + 1
    W, H = (X1 - X0) * M, (Y1 - Y0) * M
    img = Image.new("L", (W, H), 0)
    d = ImageDraw.Draw(img)
    for poly in polys:
        d.polygon([((tile_xy(*p)[0] - X0) * M, (tile_xy(*p)[1] - Y0) * M) for p in poly[0]], fill=255)
        for hole in poly[1:]:
            d.polygon([((tile_xy(*p)[0] - X0) * M, (tile_xy(*p)[1] - Y0) * M) for p in hole], fill=0)
    land = np.array(img) > 0
    dist = distance_transform_edt(~land)
    blur = gaussian_filter((dist <= GUARD).astype(np.float64), SIGMA)
    alpha = np.round(np.clip(blur / FULL, 0, 1) * 255).astype(np.uint8)
    assert alpha[land].min() == 255, "all land is full alpha"
    WORK.mkdir(parents=True, exist_ok=True)
    Image.fromarray(alpha).save(WORK / "blend-mask.png", optimize=True)
    blocks = alpha.reshape(Y1 - Y0, M, X1 - X0, M).max(axis=(1, 3))
    sources = sorted((X0 + int(x), Y0 + int(y)) for y, x in zip(*np.nonzero(blocks)))
    parents, current = {}, set(sources)
    for z in range(12, -1, -1):
        current = {(x // 2, y // 2) for x, y in current}
        parents[str(z)] = sorted(current)
    config = {"version": VERSION, "sourceZoom": 13, "maximumZoom": 12, "minimumZoom": 0, "tileSize": 256,
              "maskBounds": [X0, Y0, X1, Y1], "maskPixelsPerSourceTile": M,
              "mask": {"source": "Natural Earth 1:10m admin-0 countries v5.1.2 (public domain), NZL, North Island polygons",
                       "guardPixels": GUARD, "gaussianSigmaPixels": SIGMA, "fullAlphaAt": FULL,
                       "sha256": digest((WORK / "blend-mask.png").read_bytes()), "polygons": len(polys)},
              "sourceTiles": sources, "parents": parents,
              "parentCounts": {z: len(v) for z, v in parents.items()},
              "filter": "premultiplied recursive 2x2 area mean, nearest-even rounding", "encoding": "lossless WebP",
              "attribution": "LINZ CC BY 4.0 and imagery providers",
              "scope": "North Island and neighbouring coastal islands; coastline only controls an offshore image blend"}
    atomic(WORK / "config.json", json.dumps(config, separators=(",", ":")).encode())
    log(stage="mask", polygons=len(polys), bounds=[X0, Y0, X1, Y1], sources=len(sources),
        parents=config["parentCounts"])


def config():
    return json.loads((WORK / "config.json").read_text())


# ---- 2. Sources ------------------------------------------------------------------
_lock = threading.Lock()
_next = 0.0


def throttle():
    global _next
    with _lock:
        now = time.monotonic()
        wait = max(0.0, _next - now)
        _next = max(now, _next) + 1 / 12
    if wait:
        time.sleep(wait)


def source_path(z, x, y):
    return WORK / "source" / str(z) / str(x) / f"{y}.webp"


def fetch_one(job, key):
    z, x, y = job
    p = source_path(z, x, y)
    if p.exists():
        try:
            with Image.open(p) as im:
                im.load()
                if im.size == (256, 256):
                    return "kept"
        except Exception:
            pass
    url = f"https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api={key}"
    for attempt in range(6):
        try:
            throttle()
            with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "ridgeline-overviews"}), timeout=40) as r:
                b = r.read()
            with Image.open(io.BytesIO(b)) as im:
                im.load()
                assert im.size == (256, 256), "unexpected source tile size"
            atomic(p, b)
            return "fetched"
        except urllib.error.HTTPError as e:
            if e.code in (401, 403):
                raise RuntimeError("LINZ key rejected") from None
            if e.code == 404:
                return "missing"
            time.sleep(min(2 ** attempt, 20))
        except (TimeoutError, OSError):
            time.sleep(min(2 ** attempt, 20))
    raise RuntimeError(f"source {z}/{x}/{y} failed")


def mask_image():
    return Image.open(WORK / "blend-mask.png")


def coarse_full(cfg, mask, z, x, y):
    X0, Y0, X1, Y1 = cfg["maskBounds"]
    f = 2 ** (13 - z)
    b = ((x * f - X0) * M, (y * f - Y0) * M, ((x + 1) * f - X0) * M, ((y + 1) * f - Y0) * M)
    if b[0] < 0 or b[1] < 0 or b[2] > mask.width or b[3] > mask.height:
        return False
    return mask.crop((b[0] - 1, b[1] - 1, b[2] + 1, b[3] + 1)).getextrema() == (255, 255)


def stage_fetch(workers):
    cfg = config()
    mask = mask_image()
    key = json.load(open(ASSETS / "map-defaults.json"))["linzApiKey"]
    jobs = [(13, x, y) for x, y in cfg["sourceTiles"]]
    jobs += [(int(z), x, y) for z, keys in cfg["parents"].items() for x, y in keys if not coarse_full(cfg, mask, int(z), x, y)]
    counts = collections.Counter()
    with ThreadPoolExecutor(max_workers=workers) as pool:
        for i, result in enumerate(pool.map(lambda j: fetch_one(j, key), jobs)):
            counts[result] += 1
            if i % 500 == 0:
                log(stage="fetch", done=i, total=len(jobs), **counts)
    log(stage="fetch_complete", total=len(jobs), **counts)


# ---- 3. Pyramid -------------------------------------------------------------------
def average(im):
    """The South Island pipeline's premultiplied 2x2 area mean."""
    pixels = np.asarray(im).astype("float32")
    a = pixels[:, :, 3:] / 255
    pa = pixels[:, :, :3] * a
    aa = a.reshape(256, 2, 256, 2, 1).mean(axis=(1, 3))
    rgb = pa.reshape(256, 2, 256, 2, 3).mean(axis=(1, 3))
    rgb = np.divide(rgb, aa, out=np.zeros_like(rgb), where=aa > 0)
    out = np.concatenate([rgb, aa * 255], axis=2)
    return Image.fromarray(np.clip(np.rint(out), 0, 255).astype("uint8"))


def lossless(im):
    b = io.BytesIO()
    im.save(b, "WEBP", lossless=True, exact=True, method=4)
    data = b.getvalue()
    assert Image.open(io.BytesIO(data)).convert(im.mode).tobytes() == im.tobytes(), "lossless round trip"
    return data


class SouthIsland:
    """The South Island's finished tiles (raw and natural): the bundled coarse
    images and the tiles in its six original ZIPs."""

    def __init__(self, zip_dir):
        # Its own index, never the app's merged one (which has the North Island in it).
        text = (ROOT / "public/south-overviews.js").read_text()
        south = json.JSONDecoder().raw_decode(text[text.index("{", text.index("const data=")):])[0]
        self.coarse = {k: {m: (v.get(m) or v)["url"].lstrip("/") for m in ("raw", "natural")}
                       for k, v in south["tiles"].items() if int(k.split("/")[0]) <= 8}
        self.index = {"tiles": json.load(open(ROOT / "capacitor/overview-catalog.json"))["tiles"]}
        self.payloads = {}
        archives = json.load(open(ROOT / "native-wgpu/overview-archives.json"))["files"]
        for rec in archives:
            data = (Path(zip_dir) / rec["name"]).read_bytes()
            assert digest(data) == rec["sha256"], rec["name"]
            size = struct.unpack("<I", data[22:26])[0]
            nl, xl = struct.unpack("<HH", data[26:30])
            body = data[30 + nl + xl:30 + nl + xl + size]
            assert digest(body) == rec["payload"]
            self.payloads[rec["payload"]] = body

    def tile(self, z, x, y, mode):
        k = f"{z}/{x}/{y}"
        c = self.coarse.get(k)
        if c:
            return Image.open(ROOT / "public" / c[mode]).convert("RGBA")
        t = self.index["tiles"].get(k)
        if t:
            row = t.get(mode, t)
            b = self.payloads[row["file"]][row["offset"]:row["offset"] + row["bytes"]]
            return Image.open(io.BytesIO(b)).convert("RGBA")
        return None


def stage_build(zip_dir, workers):
    cfg = config()
    mask = mask_image()
    mask.load()  # fully decoded before the workers share it (lazy PNG loading is not thread safe)
    mask_lock = threading.Lock()
    X0, Y0, _, _ = cfg["maskBounds"]
    south = SouthIsland(zip_dir)
    sources = set(map(tuple, cfg["sourceTiles"]))
    out = WORK / "out"
    state_path = WORK / "built.json"
    built = json.loads(state_path.read_text()) if state_path.exists() else {}

    def source(x, y):
        p = source_path(13, x, y)
        if (x, y) not in sources or not p.exists():
            return None
        im = Image.open(p).convert("RGBA")
        with mask_lock:
            a = mask.transform((256, 256), Image.Transform.EXTENT, ((x - X0) * M, (y - Y0) * M, (x - X0 + 1) * M, (y - Y0 + 1) * M),
                               resample=Image.Resampling.BILINEAR)
        im.putalpha(a)
        return im

    def overlay_path(z, x, y):
        return WORK / "overlay" / str(z) / str(x) / f"{y}.webp"

    def one(z, x, y):
        mosaic = Image.new("RGBA", (512, 512))
        for dy in range(2):
            for dx in range(2):
                cx, cy = x * 2 + dx, y * 2 + dy
                if z == 12:
                    child = source(cx, cy)
                else:
                    p = overlay_path(z + 1, cx, cy)
                    child = Image.open(p).convert("RGBA") if p.exists() else None
                if child is not None:
                    mosaic.paste(child, (dx * 256, dy * 256))
        overlay = average(mosaic)
        atomic(overlay_path(z, x, y), lossless(overlay))
        opaque = overlay.getchannel("A").getextrema() == (255, 255)
        rows = {}
        for mode in ("raw", "natural"):
            base = None if opaque else south.tile(z, x, y, mode)
            shared = base is not None
            if base is None and not opaque:
                p = source_path(z, x, y)
                base = Image.open(p).convert("RGBA") if p.exists() else Image.new("RGBA", (256, 256), (0, 0, 0, 0))
            result = (overlay if opaque else Image.alpha_composite(base, overlay)).convert("RGB")
            data = lossless(result)
            path = out / "tiles" / (digest(data) + ".webp")
            if not path.exists():
                atomic(path, data)
            rows[mode] = {"sha256": digest(data), "bytes": len(data), "shared": shared, "opaque": opaque}
        return f"{z}/{x}/{y}", rows

    for z in range(12, -1, -1):
        keys = [tuple(k) for k in cfg["parents"][str(z)]]
        todo = [k for k in keys if f"{z}/{k[0]}/{k[1]}" not in built]
        with ThreadPoolExecutor(max_workers=workers) as pool:
            for i, (k, rows) in enumerate(pool.map(lambda xy: one(z, *xy), todo)):
                built[k] = rows
                if i % 200 == 0:
                    atomic(state_path, json.dumps(built).encode())
        atomic(state_path, json.dumps(built).encode())
        log(stage="level", z=z, coordinates=len(keys), shared=sum(1 for k in keys if built[f"{z}/{k[0]}/{k[1]}"]["raw"]["shared"]))
    log(stage="build_complete", coordinates=len(built))


# ---- 4. Packages ---------------------------------------------------------------------
def stage_package(out_dir):
    """Write the North Island's packs (WORK/packs) and its committed index
    (OUT/north-island.json, OUT/nz-overviews-v1/*.webp), which
    tools/package-assets.mjs merges over the South Island's when it builds the
    app's assets. Shared coordinates replace the South Island's entries."""
    out_dir = Path(out_dir)
    built = json.loads((WORK / "built.json").read_text())
    tiles_dir = WORK / "out" / "tiles"
    fine = sorted((k for k in built if int(k.split("/")[0]) >= 9), key=lambda k: tuple(map(int, k.split("/"))))
    # Zooms 9-12 into payloads (content-addressed, deduplicated), each under PACK_LIMIT.
    payloads, current = [], bytearray()
    placed = {}
    for k in fine:
        for mode in ("raw", "natural"):
            sha = built[k][mode]["sha256"]
            if sha in placed:
                continue
            data = (tiles_dir / (sha + ".webp")).read_bytes()
            assert digest(data) == sha
            if len(current) + len(data) > PACK_LIMIT:
                payloads.append(bytes(current))
                current = bytearray()
            placed[sha] = (len(payloads), len(current), len(data))
            current.extend(data)
    if current:
        payloads.append(bytes(current))
    names = [f"Ridgeline-Overviews-NI-{PACK_VERSION}-{i + 1}-of-{len(payloads)}.zip" for i in range(len(payloads))]
    packs = WORK / "packs"
    packs.mkdir(parents=True, exist_ok=True)
    shas = [digest(p) for p in payloads]
    archives = []
    for name, body, sha in zip(names, payloads, shas):
        # One STORED entry "<payload sha>.bin", as the South Island ZIPs.
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", compression=zipfile.ZIP_STORED) as zf:
            info = zipfile.ZipInfo(sha + ".bin", date_time=(2026, 11, 15, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            zf.writestr(info, body)
        data = buf.getvalue()
        assert len(data) <= 63_000_000
        atomic(packs / name, data)
        archives.append({"name": name, "bytes": len(data), "sha256": digest(data), "payload": sha})
    tiles = {}
    for k in fine:
        entry = {}
        for mode in ("raw", "natural"):
            i, off, n = placed[built[k][mode]["sha256"]]
            entry[mode] = {"file": shas[i], "offset": off, "bytes": n}
        tiles[k] = entry
    # Zooms 0-8: bundled images, as the South Island's coarse tiles.
    coarse = {}
    for k in sorted(built, key=lambda k: tuple(map(int, k.split("/")))):
        if int(k.split("/")[0]) > 8:
            continue
        entry = {}
        for mode in ("raw", "natural"):
            sha = built[k][mode]["sha256"]
            name = f"assets/nz-overviews-v1/{sha[:24]}.webp"
            atomic(out_dir / name.removeprefix("assets/"), (tiles_dir / (sha + ".webp")).read_bytes())
            entry[mode] = name
        coarse[k] = entry
    shared = sorted(k for k, v in built.items() if v["raw"]["shared"])
    index = {"version": VERSION, "coordinates": len(built), "sharedWithSouthIsland": len(shared),
             "tiles": tiles, "coarse": coarse,
             "files": [{"sha256": s, "bytes": len(p)} for s, p in zip(shas, payloads)], "archives": archives}
    atomic(out_dir / "north-island.json", (json.dumps(index, separators=(",", ":")) + "\n").encode())
    report = {"version": VERSION, "coordinates": len(built), "fineCoordinates": len(fine), "coarseCoordinates": len(coarse),
              "sharedWithSouthIsland": shared, "packs": archives, "uniqueFineTiles": len(placed),
              "fineBytes": sum(len(p) for p in payloads)}
    atomic(WORK / "report.json", (json.dumps(report, indent=2) + "\n").encode())
    log(stage="package", packs=len(names), fineCoordinates=len(fine), coarse=len(coarse), bytes=report["fineBytes"],
        shared=len(shared))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("stage", choices=["mask", "fetch", "build", "package"])
    ap.add_argument("arg", nargs="?")
    ap.add_argument("--workers", type=int, default=24)
    a = ap.parse_args()
    if a.stage == "mask":
        stage_mask(a.arg)
    elif a.stage == "fetch":
        stage_fetch(a.workers)
    elif a.stage == "build":
        stage_build(a.arg, min(a.workers, 8))
    else:
        stage_package(a.arg or OVERVIEWS)
