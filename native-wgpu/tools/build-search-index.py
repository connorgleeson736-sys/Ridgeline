#!/usr/bin/env python3
"""Build the offline place search index (android assets search/places.tsv).

Plain UTF-8: the APK compresses assets itself, and Android's build strips a
".gz" suffix from asset names.

Inputs (downloaded separately, paths as arguments):
  gaz.csv      NZ Gazetteer, https://gazetteer.linz.govt.nz/gaz.csv
               (Toitu Te Whenua LINZ, CC BY 4.0)
  huts*.json   DOC_Huts FeatureServer query, f=json, outSR=4326
  walks*.json  DOC_Walking_Experiences FeatureServer query, f=json, outSR=4326
  camps*.json  DOC_Campsites FeatureServer query, f=json, outSR=4326
               (Department of Conservation open data)

Output rows (tab separated, UTF-8): kind, name, alternative names (|),
latitude, longitude, rank (lower is more important), detail, link.
"""
import csv, json, sys

def main(out, gaz, docs):
    rows = []
    kinds = {
        'Hill': 'peak', 'Mountain': 'peak', 'Peak': 'peak', 'Volcano': 'peak',
        'Pass': 'pass', 'Saddle': 'pass',
        'Lake': 'lake', 'Tarn': 'lake', 'Lagoon': 'lake', 'Pool': 'lake', 'Pond': 'lake',
        'Glacier': 'glacier', 'Ice Feature': 'glacier', 'Snowfield': 'glacier', 'Neve': 'glacier',
        'Stream': 'river', 'River': 'river',
        'Town': 'place', 'City': 'place', 'Locality': 'place', 'Suburb': 'place', 'Village': 'place', 'Place': 'place',
        'Valley': 'valley', 'Gorge': 'valley', 'Canyon': 'valley', 'Basin': 'valley', 'Flat': 'valley',
        'Ridge': 'ridge', 'Range': 'ridge', 'Spur': 'ridge', 'Cliff': 'ridge', 'Bluff': 'ridge', 'Rock': 'ridge',
        'Waterfall': 'waterfall',
        'Bay': 'coast', 'Beach': 'coast', 'Point': 'coast', 'Cape': 'coast', 'Island': 'coast', 'Peninsula': 'coast',
        'Scenic Reserve': 'area', 'Recreation Reserve': 'area', 'Forest': 'area', 'Bush': 'area',
        'Historic Reserve': 'area', 'Scientific Reserve': 'area', 'Conservation Area': 'area', 'National Park': 'area',
        'Forest Park': 'area', 'Historic Site': 'area',
    }
    base = {'walk': 0, 'hut': 1, 'place': 1, 'peak': 2, 'pass': 2, 'lake': 2, 'glacier': 3, 'camp': 3,
            'waterfall': 3, 'valley': 4, 'ridge': 4, 'area': 4, 'river': 5, 'coast': 5}
    with open(gaz, encoding='utf-8-sig') as f:
        for r in csv.DictReader(f):
            kind = kinds.get(r['feat_type'])
            if not kind or r['status'] == 'Unofficial Discontinued':
                continue
            try:
                lat, lon = float(r['crd_latitude']), float(r['crd_longitude'])
            except ValueError:
                continue
            if not (165 <= lon <= 180 and -49 <= lat <= -33):
                continue
            name = r['name'].strip()
            alts = {p.strip() for p in name.split('/') if '/' in name and p.strip()}
            alts.discard(name)
            rank = base[kind]
            if r['status'].startswith('Unofficial'):
                rank += 0.5
            if r['status'] == 'Unofficial Replaced':
                rank += 2
            height = r['height'].strip()
            if kind == 'peak' and height:
                try:
                    rank -= min(1.0, float(height) / 3000)
                except ValueError:
                    pass
            detail = r['feat_type'] + (f' · {height} m' if kind == 'peak' and height else '')
            rows.append((kind, name, '|'.join(sorted(alts)), lat, lon, rank, detail, ''))
    for path in docs:
        data = json.load(open(path, encoding='utf-8'))
        for feat in data.get('features', []):
            a, g = feat['attributes'], feat.get('geometry') or {}
            lon, lat = g.get('x'), g.get('y')
            if lon is None or lat is None or not a.get('name'):
                continue
            if 'walkingAndTrampingWebPage' in a or 'difficulty' in a:
                bits = [b for b in (a.get('difficulty'), a.get('completionTime')) if b]
                rows.append(('walk', a['name'].strip(), '', lat, lon, base['walk'], ' · '.join(bits), a.get('walkingAndTrampingWebPage') or ''))
            elif 'camp' in path.lower():
                rows.append(('camp', a['name'].strip(), '', lat, lon, base['camp'], a.get('place') or a.get('region') or '', a.get('staticLink') or ''))
            else:
                bits = [b for b in (a.get('place'), 'Bookable' if a.get('bookable') == 'Yes' else None) if b]
                rows.append(('hut', a['name'].strip(), '', lat, lon, base['hut'], ' · '.join(bits), a.get('staticLink') or ''))
    seen, unique = set(), []
    for row in rows:
        key = (row[0], row[1].lower(), round(row[3], 3), round(row[4], 3))
        if key in seen:
            continue
        seen.add(key)
        unique.append(row)
    clean = lambda s: str(s).replace('\t', ' ').replace('\n', ' ')
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        for kind, name, alts, lat, lon, rank, detail, link in unique:
            f.write('\t'.join([kind, clean(name), clean(alts), f'{lat:.5f}', f'{lon:.5f}', f'{rank:.2f}', clean(detail), clean(link)]) + '\n')
    print(len(unique), 'entries')

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2], sys.argv[3:])
