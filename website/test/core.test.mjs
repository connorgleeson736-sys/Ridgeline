// The website's routing and walk logic, checked on the same cases as the
// Android app's tests (DocRouteTest, RouteAlongTracksTest), real DOC and LINZ data included.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { meters, length, distanceTo, along } from '../src/js/core/geo.js';
import * as follow from '../src/js/core/follow.js';
import * as docroute from '../src/js/core/docroute.js';
import { decode } from '../src/js/core/mvt.js';

const fixtures = fileURLToPath(new URL('../../native-wgpu/android/app/src/test/fixtures/', import.meta.url));
const at = (east, north) => [169 + east / (111_320 * Math.cos((-44.5 * Math.PI) / 180)), -44.5 + north / 111_320];
const near = (a, b, m = 1) => meters(a, b) < m;
const close = (actual, expected, tolerance) => assert.ok(Math.abs(actual - expected) <= tolerance, `${actual} not within ${tolerance} of ${expected}`);

// ---- Following tracks (TrackFollow) ----
const tracks = [['Valley Track', [at(0, 0), at(1000, 0), at(1000, 1000)]], ['Spur', [at(500, 0), at(500, -300)]]];

test('point to point along the track, ending on it', () => {
  const line = follow.route(at(0, 30), at(1030, 900), tracks, 50);
  assert.ok(near(line[0], at(0, 30)));
  assert.ok(near(line[line.length - 1], at(1000, 900)), 'ends on the track beside the tap');
  close(length(line), 30 + 1000 + 900, 1);
});

test('joins and leaves the track beside the taps', () => {
  const line = follow.route(at(263, 20), at(483, -271), tracks, 40);
  assert.ok(near(line[1], at(263, 0)));
  assert.ok(near(line[line.length - 1], at(500, -271)));
  close(length(line), 20 + 237 + 271, 1);
});

test('a tap not close to a track is not followed', () => {
  assert.equal(follow.route(at(0, 0), at(600, 60), tracks, 50), null);
  assert.equal(follow.route(at(0, 500), at(1000, 0), tracks, 50), null);
  const apart = [['A', [at(0, 0), at(100, 0)]], ['B', [at(800, 0), at(900, 0)]]];
  assert.equal(follow.route(at(0, 0), at(900, 0), apart, 50), null);
});

test('walks along a road between tracks', () => {
  const a = ['A Track', [at(0, 500), at(0, 0)]], b = ['B Track', [at(1000, 0), at(1000, 500)]];
  const road = ['Valley Road', [at(-200, 0), at(1200, 0)]];
  close(length(follow.route(at(0, 500), at(1000, 500), [a, b], 20, [road])), 2000, 2);
  assert.equal(follow.route(at(0, 500), at(1000, 500), [a, b], 20), null);
});

test('prefers the track to a parallel road', () => {
  const track = ['Riverside Walk', [at(0, 0), at(500, 100), at(1000, 0)]];
  const road = ['Main Road', [at(0, 0), at(500, -20), at(1000, 0)]];
  const line = follow.route(at(0, 0), at(1000, 0), [track], 20, [road]);
  assert.ok(line.some((p) => near(p, at(500, 100))));
});

const banks = (bridgeAt) => [
  ['North Bank Track', [at(-300, 330), at(-300, 30), at(bridgeAt, 30)]],
  ['South Bank Track', [at(-300, -330), at(-300, -30), at(bridgeAt, -30)]],
  ['Bridge', [at(bridgeAt, 30), at(bridgeAt, -30)]]];
const river = [[at(-500, 0), at(5000, 0)]];

test('fords a river rather than a far detour, uses a nearby bridge, never fords without a river', () => {
  close(length(follow.route(at(0, 30), at(0, -30), banks(3000), 20, [], river)), 60, 2);
  close(length(follow.route(at(0, 30), at(0, -30), banks(100), 20, [], river)), 260, 2);
  close(length(follow.route(at(0, 30), at(0, -30), banks(3000), 20)), 6060, 2);
});

test('a dragged point slides along the track, then comes off', () => {
  const graph = new follow.Graph(tracks);
  const d = follow.drag(graph, at(0, 0), at(700, 25), at(500, -300), 40);
  assert.ok(near(d.point, at(700, 0)));
  close(length([at(0, 0), ...d.before]), 700, 1);
  close(length([d.point, ...d.after, at(500, -300)]), 500, 1);
  const off = follow.drag(graph, at(0, 0), at(700, 60), at(500, -300), 40);
  assert.ok(near(off.point, at(700, 60)));
  assert.equal(off.after.length, 0);
});

test('up the Roys Peak zigzags on real LINZ tiles', () => {
  const dir = fixtures + 'tracks/';
  const all = [];
  for (const f of readdirSync(dir)) {
    const [z, x, y] = f.replace('.pbf', '').split('-').map(Number);
    for (const feature of decode(readFileSync(dir + f), z, x, y, new Set(['streets'])))
      if (feature.properties.kind === 'track') for (const p of feature.paths) all.push([feature.properties.name || '', p]);
  }
  const roys = all.filter((t) => t[0] === 'Roys Peak Track').flatMap((t) => t[1]);
  assert.ok(roys.length > 50, 'decoded the track');
  const bottom = roys.reduce((a, b) => (b[1] < a[1] ? b : a));
  const from = [bottom[0] + 0.0004, bottom[1]], top = [169.0597, -44.6825];
  const line = follow.route(from, top, all, 200);
  assert.ok(length(line) > 1.5 * meters(from, top), 'follows the zigzags');
  assert.ok(line.slice(1).every((p) => all.some((t) => distanceTo(t[1], p) < 1)), 'on the track');
});

// ---- DOC walks (DocRoute) ----
const kepler = JSON.parse(readFileSync(fixtures + 'doc-lines/kepler-f7da33e0.json', 'utf8'))
  .features.flatMap((f) => f.geometry.paths);
const controlGates = [167.684982, -45.439866], luxmore = [167.61916, -45.38523], irisBurn = [167.5095, -45.39904], moturau = [167.61071, -45.48168];

test('the Kepler Track is one loop from the car park, huts in order, side trips left out', () => {
  const line = docroute.route(kepler, docroute.stated('60 km loop'));
  const total = length(line);
  assert.ok(total > 57_000 && total < 61_000, `${total}`);
  assert.ok(near(line[0], controlGates, 50));
  for (const h of [luxmore, irisBurn, moturau]) assert.ok(distanceTo(line, h) < 100);
  const a = [luxmore, irisBurn, moturau].map((h) => along(line, h));
  assert.ok(a[0] < a[1] && a[1] < a[2], `${a}`);
  assert.ok(distanceTo(line, [167.496537, -45.395537]) > 500, 'not to Iris Burn Falls');
  close(length(docroute.route(kepler)), total, 1);
});

test('a walk from end to end in pieces out of order, and an open loop closed', () => {
  const line = docroute.route([[at(0, 0), at(1000, 0)], [at(1000, 0), at(2000, 0)], [at(3000, 0), at(2000, 0)], [at(1500, 0), at(1500, 400)]]);
  assert.ok(near(line[0], at(0, 0)) && near(line[line.length - 1], at(3000, 0)));
  close(length(line), 3000, 1);
  const loop = [at(100, 0), at(1000, 0), at(1000, 1000), at(0, 1000), at(0, 0), at(-100, 0)];
  close(length(docroute.route([loop.slice(0, 3), loop.slice(2)])), 4200, 5);
});

test('a loop with an access track: in, round and out when DOC says so', () => {
  const access = [at(0, -2000), at(0, 0)], loop = [at(0, 0), at(1000, 0), at(1000, 1000), at(0, 1000), at(0, 0)];
  const pieces = [access, loop.slice(0, 3), loop.slice(2)];
  close(length(docroute.route(pieces, 8000)), 8000, 5);
  close(length(docroute.route(pieces, 4000)), 4000, 5);
});

test('DOC distances', () => {
  assert.equal(docroute.stated('60 km loop'), 60_000);
  assert.equal(docroute.stated('16 km return via same track'), 8000);
  assert.equal(docroute.stated('1.5 km one way return via same track'), 1500);
  assert.equal(docroute.stated('Various'), null);
});

// ---- DOC pages (DocPages), on the app's real page fixtures ----
import * as docpages from '../src/js/core/docpages.js';
const page = (name) => readFileSync(fixtures + `docpages/${name}.html`, 'utf8');

test('DOC page summary, as the app reads it', () => {
  const o = docpages.overview(page('milford-track'));
  assert.equal(o.title, 'Milford Track');
  assert.equal(o.time, '4 days');
  assert.equal(o.level, 'Intermediate tramping track');
  assert.equal(o.distance, '53.5 km one way');
  assert.ok(o.intro.startsWith('Take a journey through the heart of Fiordland'));
  assert.equal(o.dogs, 'No dogs.');
});

test('DOC page sections and legs', () => {
  const sections = docpages.parse(page('gillespie-pass-circuit'));
  assert.ok(sections.some(([t]) => /description/i.test(t)), 'has a description');
  assert.ok(sections.every(([, blocks]) => blocks.length > 0));
  const legs = docpages.legs(sections);
  assert.ok(legs.length >= 3, `legs: ${legs.map((l) => l.title).join(' | ')}`);
  assert.ok(legs.some((l) => /Siberia Hut/i.test(l.to) || /Siberia Hut/i.test(l.from)));
  assert.ok(legs.every((l) => l.time || l.km != null));
  const milford = docpages.legs(docpages.parse(page('milford-track')));
  assert.ok(milford.length >= 3 && /Clinton Hut/.test(milford[0].title), milford.map((l) => l.title).join(' | '));
});

test('huts on a planned route: at its ends and passed on the way (as the app\'s RouteHuts)', async () => {
  const { PlaceSearch } = await import('../src/js/core/search.js');
  const rows = [
    ['hut', 'Liverpool Hut', '', '-44.5', '168.6', '', '', ''],
    ['peak', 'Mt Liverpool', '', '-44.5003', '168.6005', '', '', ''],
    ['hut', 'Aspiring Hut', '', '-44.4', '168.7', '', '', ''],
    ['hut', 'Halfway Hut', '', '-44.4505', '168.65', '', '', ''],
    ['hut', 'Far Hut', '', '-44.47', '168.65', '', '', ''],
  ].map((r) => r.join('\t')).join('\n');
  const index = new PlaceSearch(rows);
  // A straight route from ~60 m off Liverpool Hut to ~60 m off Aspiring Hut, sampled every ~100 m.
  const a = [168.6007, -44.5002], b = [168.6994, -44.4004], total = meters(a, b), n = Math.round(total / 100);
  const points = Array.from({ length: n + 1 }, (_, i) => [a[0] + (b[0] - a[0]) * i / n, a[1] + (b[1] - a[1]) * i / n]);
  const marks = index.hutsAlong(points, points.map((_, i) => total * i / n));
  assert.deepEqual(marks.map((m) => m[1]), ['Liverpool Hut', 'Halfway Hut', 'Aspiring Hut']);
  assert.ok(Math.abs(marks[0][0]) < 100 && Math.abs(marks[1][0] - total / 2) < 300 && Math.abs(marks[2][0] - total) < 100);
  // Real data: a route past Mueller Hut.
  const real = new PlaceSearch(readFileSync(new URL('../../native-wgpu/android/app/src/main/assets/search/places.tsv', import.meta.url), 'utf8'));
  const mueller = real.entries.find((e) => e.place.kind === 'hut' && /^Mueller Hut/.test(e.place.name)).place;
  const past = [[mueller.lon - 0.01, mueller.lat + 0.0005], [mueller.lon + 0.01, mueller.lat + 0.0005]];
  assert.ok(real.hutsAlong(past, [0, meters(past[0], past[1])]).some((m) => /^Mueller Hut/.test(m[1])));
});

test('shared route links whose polyline contains ~ open (old and new links)', async () => {
  const { encodeRoute, decodeRoute } = await import('../src/js/core/share.js');
  const points = [[170.0648, -43.7210], [170.07, -43.724], [170.079, -43.728], [170.088, -43.73]];
  const link = encodeRoute({ name: 'Mueller ~ Sealy', points, waypoints: [0, 3] });
  const back = decodeRoute('#' + link);
  assert.equal(back.name, 'Mueller ~ Sealy');
  assert.equal(back.points.length, 4);
  assert.ok(Math.abs(back.points[3][0] - 170.088) < 1e-5);
  // A link from before ~ was escaped: ~ inside its polyline.
  const old = decodeRoute('#r=Test~fgziG_x~l_@vQo_@~Wgw@nKgw@~T');
  assert.equal(old.name, 'Test');
  assert.equal(old.points.length, 4);
  assert.deepEqual(old.waypoints, [0, 3]);
});

// ---- Huts along a walk: the app's rules (TrackSheet.showHuts, DocTracks.named) ----
import { hutName, named, namedOnly, pageText, compiledStops, walkHuts } from '../src/js/core/walks.js';
import { gunzipSync } from 'node:zlib';

test('hut names lose DOC asset codes, not historic notes', () => {
  assert.equal(hutName('T3: South Ohau Hut'), 'South Ohau Hut');
  assert.equal(hutName('Mintaro Hut (2021)'), 'Mintaro Hut');
  assert.equal(hutName('Top Crooked Hut 3PM'), 'Top Crooked Hut');
  assert.equal(hutName('Iron Gate Hut - 2019'), 'Iron Gate Hut');
  assert.equal(hutName('Field Hut (H)'), 'Field Hut');
  assert.equal(hutName('Hut (Historic 1949)'), 'Hut (Historic 1949)');
});

test('a walk shows only the huts near its line that its DOC page names', () => {
  const line = [[168.0, -44.9], [168.1, -44.9]];
  const hut = (name, x) => ({ name, point: [x, -44.9015] });
  const huts = [hut('Mid Caples Hut', 168.02), hut('Slip Flat Hut', 168.05), hut('Greenstone Hut', 168.08)];
  const page = { s: [['Huts', [['p', 'There are 3 DOC huts on this track:'], ['b', 'Mid Caples Hut'], ['b', 'Greenstone Hut'], ['i', 'Slip Flat Hut photo']]]] };
  assert.deepEqual(walkHuts({ line, huts, page }).map((s) => s.hut.name), ['Mid Caples Hut', 'Greenstone Hut']);
  // A page naming none of them: all the huts near the line.
  assert.equal(walkHuts({ line, huts, page: { s: [['About', [['p', 'A valley walk.']]]] } }).length, 3);
  assert.equal(named([], pageText(page)).length, 0);
});

test('a compiled walk shows exactly its own huts, in order along it', () => {
  const line = [[168.0, -44.9], [168.1, -44.9]];
  const near = [{ hut: { name: 'Slip Flat Hut', point: [168.05, -44.9015] }, along: 3900 }, { hut: { name: 'Greenstone Hut', point: [168.08, -44.9015], bunks: '20' }, along: 6300 }];
  const out = compiledStops([['Greenstone Hut', 168.08, -44.9015], ['Mid Caples Hut', 168.02, -44.9]], near, line);
  assert.deepEqual(out.map((s) => s.hut.name), ['Mid Caples Hut', 'Greenstone Hut']);
  assert.equal(out[1].hut.bunks, '20', "DOC's hut details where the map has them");
  assert.ok(Math.abs(out[0].along - meters(line[0], [168.02, -44.9])) < 1);
});

test('a compiled walk also shows the huts near it that its DOC page names, not others', () => {
  const line = [[168.0, -44.9], [168.1, -44.9]];
  const huts = [{ name: 'Mid Caples Hut', point: [168.02, -44.9] }, { name: 'Slip Flat Hut', point: [168.05, -44.9015] }, { name: 'Mokihinui Forks Hut', point: [168.07, -44.9] }];
  const page = { s: [['Track', [['h', 'Goat Creek Hut to Mōkihinui Forks Hut']]]], h: [['Mid Caples Hut', 168.02, -44.9]] };
  assert.deepEqual(walkHuts({ line, huts, page }).map((s) => s.hut.name), ['Mid Caples Hut', 'Mokihinui Forks Hut']);
});

test("the website lists the same huts as the app for every compiled walk with a DOC page", () => {
  const walksFile = fileURLToPath(new URL('../../native-wgpu/android/app/src/main/assets/walks/walks.json', import.meta.url));
  const pagesFile = fileURLToPath(new URL('../data/walk-pages.json.gz', import.meta.url));
  const compiled = JSON.parse(readFileSync(walksFile, 'utf8')).walks;
  const pages = JSON.parse(gunzipSync(readFileSync(pagesFile)));
  let checked = 0;
  for (const [guid, w] of Object.entries(compiled)) {
    const h = (w.huts || []).filter((x) => Number.isFinite(x.lon)).map((x) => [x.name, x.lon, x.lat]);
    if (!h.length || !pages[guid]) continue;
    const got = walkHuts({ line: [[h[0][1], h[0][2]], [h[0][1] + 0.01, h[0][2]]], huts: [], page: { ...pages[guid], h } }).map((s) => s.hut.name).sort();
    assert.deepEqual(got, h.map((x) => x[0]).sort(), guid);
    checked++;
  }
  assert.ok(checked > 100, `${checked} compiled walks checked`);
});
