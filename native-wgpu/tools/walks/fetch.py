#!/usr/bin/env python3
"""Fetch DOC's walk data for the offline walk compiler (CC BY 4.0, doc.govt.nz).

Usage: python3 -I fetch.py CACHE_DIR

Writes into CACHE_DIR (outside the repository):
  walks.json      every walk: guid, name, difficulty, time, point (DOC's walk
                  list), whether DOC has a walk line and page for it, and the
                  page path its link redirects to (pages link walks by path)
  pages/<g>.html  each walk's DOC page (g: guid without dashes)
  lines/<g>.json  each walk's DOC line (same query as the app's DocTracks.lineUrl)
  huts.json       DOC huts: name, point, staticLink and the page path that link
                  redirects to (walk pages link huts by that path)
  campsites.json  the same for DOC campsites
Existing files are kept (delete one to fetch it again).
"""
import json, os, ssl, sys, time, urllib.parse, urllib.request
from concurrent.futures import ThreadPoolExecutor

ORG = "https://services1.arcgis.com/3JjYDyG3oajxU6HO/ArcGIS/rest/services/"
SITE = "https://www.doc.govt.nz"
UA = {"User-Agent": "Mozilla/5.0 (compatible; walk-data-tools)"}
CTX = ssl.create_default_context(cafile=os.environ.get("SSL_CERT_FILE") or None)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def get(url, tries=4):
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), context=CTX, timeout=60) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            if e.code in (404, 410):
                return None
            if i == tries - 1:
                raise
        except Exception:
            if i == tries - 1:
                raise
        time.sleep(2 * (i + 1))


def location(url):
    """Where a DOC link/<guid>.aspx address redirects (the page path), or None."""
    opener = urllib.request.build_opener(NoRedirect, urllib.request.HTTPSHandler(context=CTX))
    for i in range(4):
        try:
            opener.open(urllib.request.Request(url, headers=UA, method="HEAD"), timeout=60)
            return None
        except urllib.error.HTTPError as e:
            if e.code in (301, 302, 303, 307, 308):
                return urllib.parse.urlparse(urllib.parse.urljoin(url, e.headers["Location"])).path
            if e.code in (404, 410):
                return None
        except Exception:
            pass
        time.sleep(2 * (i + 1))
    return None


def query_all(service, fields, geometry):
    out, offset = [], 0
    while True:
        q = urllib.parse.urlencode({"where": "1=1", "outFields": fields, "returnGeometry": str(geometry).lower(),
                                    "outSR": "4326", "f": "json", "resultOffset": offset, "resultRecordCount": 1000,
                                    "orderByFields": "OBJECTID"})
        d = json.loads(get(f"{ORG}{service}/FeatureServer/0/query?{q}"))
        feats = d.get("features", [])
        out += feats
        if not feats or not d.get("exceededTransferLimit"):
            return out
        offset += len(feats)


def guid_of(page):
    import re
    m = re.search(r"link/([0-9a-fA-F]{32})\.aspx", page or "")
    if not m:
        return None
    g = m.group(1).lower()
    return f"{g[:8]}-{g[8:12]}-{g[12:16]}-{g[16:20]}-{g[20:]}"


def main(cache):
    os.makedirs(f"{cache}/pages", exist_ok=True)
    os.makedirs(f"{cache}/lines", exist_ok=True)
    walks = {}
    for f in query_all("DOC_Walking_Experiences", "name,difficulty,completionTime,walkingAndTrampingWebPage", True):
        a, g = f["attributes"], guid_of(f["attributes"].get("walkingAndTrampingWebPage"))
        if not g:
            continue
        geo = f.get("geometry") or {}
        walks[g] = {"guid": g, "name": (a.get("name") or "").strip(), "difficulty": a.get("difficulty") or "",
                    "time": a.get("completionTime") or "", "point": [geo.get("x"), geo.get("y")] if "x" in geo else None,
                    "listed": True, "line": False}
    for f in query_all("DOC_WebsiteWalkingandTrampingLns_DTO", "EPISERVERGUID", False):
        g = (f["attributes"].get("EPISERVERGUID") or "").strip("{}").lower()
        if len(g) != 36:
            continue
        w = walks.setdefault(g, {"guid": g, "name": "", "difficulty": "", "time": "", "point": None, "listed": False})
        w["line"] = True
    print(f"walks {len(walks)} (listed {sum(w['listed'] for w in walks.values())}, with DOC line {sum(w['line'] for w in walks.values())})")

    def fetch_walk(w):
        g = w["guid"].replace("-", "")
        p = f"{cache}/pages/{g}.html"
        if not os.path.exists(p):
            b = get(f"{SITE}/link/{g}.aspx")
            if b:
                open(p + ".part", "wb").write(b); os.replace(p + ".part", p)
        lp = f"{cache}/lines/{g}.json"
        if w["line"] and not os.path.exists(lp):
            q = urllib.parse.urlencode({"where": f"EPISERVERGUID='{w['guid']}'", "outFields": "EPISERVERGUID",
                                        "returnGeometry": "true", "outSR": "4326", "maxAllowableOffset": "0.00002", "f": "json"})
            b = get(f"{ORG}DOC_WebsiteWalkingandTrampingLns_DTO/FeatureServer/0/query?{q}")
            if b:
                open(lp + ".part", "wb").write(b); os.replace(lp + ".part", lp)
    with ThreadPoolExecutor(10) as ex:
        list(ex.map(fetch_walk, walks.values()))
    missing = [w["guid"] for w in walks.values() if not os.path.exists(f"{cache}/pages/{w['guid'].replace('-', '')}.html")]
    print(f"pages missing: {len(missing)} {missing[:5]}")
    # Each walk's page path (DOC's text links other walks by path: "join the Greenstone Track").
    old = {w["guid"]: w.get("path") for w in json.load(open(f"{cache}/walks.json"))} if os.path.exists(f"{cache}/walks.json") else {}
    for w in walks.values():
        w["path"] = old.get(w["guid"])
        w["page"] = os.path.exists(f"{cache}/pages/{w['guid'].replace('-', '')}.html")
    todo = [w for w in walks.values() if w["page"] and not w["path"]]
    with ThreadPoolExecutor(10) as ex:
        for w, p in zip(todo, ex.map(lambda w: location(f"{SITE}/link/{w['guid'].replace('-', '')}.aspx"), todo)):
            w["path"] = p
    print(f"walk page paths {sum(1 for w in walks.values() if w.get('path'))}")
    json.dump(sorted(walks.values(), key=lambda w: w["guid"]), open(f"{cache}/walks.json", "w"), indent=0)

    for service, name in (("DOC_Huts", "huts"), ("DOC_Campsites", "campsites")):
        path = f"{cache}/{name}.json"
        old = {r["staticLink"]: r for r in json.load(open(path))} if os.path.exists(path) else {}
        rows = []
        for f in query_all(service, "*", True):
            a, geo = f["attributes"], f.get("geometry") or {}
            link = a.get("staticLink") or ""
            rows.append({"name": (a.get("name") or "").strip(), "staticLink": link, "place": a.get("place") or "",
                         "point": [geo.get("x"), geo.get("y")] if "x" in geo else None,
                         "path": old.get(link, {}).get("path")})
        todo = [r for r in rows if r["staticLink"] and not r["path"]]
        with ThreadPoolExecutor(10) as ex:
            for r, p in zip(todo, ex.map(lambda r: location(r["staticLink"]), todo)):
                r["path"] = p
        json.dump(rows, open(path, "w"), indent=0)
        print(f"{name} {len(rows)}, page paths {sum(1 for r in rows if r['path'])}")


if __name__ == "__main__":
    main(sys.argv[1])
