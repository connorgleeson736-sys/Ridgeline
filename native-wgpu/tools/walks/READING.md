# Reading a DOC walk page

You are reading one New Zealand Department of Conservation (DOC) walk page to
record, as structured data, how the walk is walked. A program then follows
your answer over the map's tracks and checks it. Accuracy is the only goal:
an empty field with a doubt is always better than a guess.

## Input: a reading packet (`packets/<g>.json`)

- `name`, `time`, `distance`, `difficulty`: DOC's summary of the walk.
- `sections`: the page's text, section by section (`### ` starts a heading,
  `- ` a list item). This is the only source you may use.
- `links`: huts, campsites and other DOC walks the page links to, each with an
  id `L1`, `L2`… These are exact: when the text names one of them, use its id.
- `features`: map features with no gazetteer name, each with an id `F1`…:
  `road end` ("where Tiki Track meets BRECON STREET": where a track starts at a
  road; a car park or road end the text describes is usually one of these),
  `lift end` (the lower or upper end of a gondola or chairlift, with its
  height), `map label …` (a named place on the map). Use one when the text's
  place is clearly it (the start "Skyline Gondola base on Brecon street" is
  the lower lift end and also where the Tiki Track meets Brecon Street: give
  the one the text means, here the track's start at the street).
- `names`: places and map tracks near the walk that the text mentions, each
  with an id `T1`, `T2`… (`kind`: `map track`, `map peak`, or a gazetteer kind:
  hut, camp, lake, peak, pass, coast, place, river, ridge, valley,
  waterfall…). A name can match more than one entry (Elfin Bay is both
  `coast` and `place`): give the one meant.

## Rules

1. Use only the packet's text. Do not use what you know about the walk from
   anywhere else, and do not fill gaps with what is usual for walks.
2. Every place you record carries `quote`: the words exactly as they appear in
   the text (copy them; case and spelling as written), and `ref`: the id of
   the link, name or feature it is, or `null` when it is none of them. Only
   give a ref you are sure of; a wrong ref is worse than `null`.
3. Starts and ends matter most: without a placed start the walk cannot be
   drawn. Look hard for the entry that is the place: a car park, road end,
   trailhead, village or town start is usually a `road end` feature (the one
   where the way's first track meets a road in that place, or the road the
   text names); a hut, campsite or other walk is usually a link; a lake, peak
   or bay a name. Give it when the text and the feature agree; leave `null`
   only when no entry is clearly the place.
4. Record a walk's way only as the text describes it. If the text does not say
   where the walk starts, ends or goes, leave that field `null` and say why in
   `doubts`.
5. Ways (`ways`): the main way first (the walk as the page presents it: the
   whole circuit, the full crossing, the track to its destination). Then each
   other way the page itself offers for doing this walk: another start ("or
   from the Skyline Gondola top"), another end ("to the saddle"), a shorter
   option it describes as a way to do the walk. Not side trips, not other
   walks the page mentions, not "you could continue on to …".
   A choice of starting points for the same described way ("start either
   outside the Visitor Centre or at White Horse Hill car park") is one way
   with the first as its start and the others in `access_points`; a separate
   way is one the page describes on its own (its own heading, time or
   distance).
6. `shape`: `out_and_back` when the walk returns the same way ("return via the
   same track", "retrace your steps", distance "return via same track");
   `loop` when it comes back to its start by another way (circuit, loop,
   round trip); `one_way` when it ends somewhere else (crossings, A to B,
   "arrange transport"). DOC's own `time`/`distance` saying "return" (and not
   "loop") means out and back. If the text does not make it clear, `null`,
   with a doubt.
7. `via`: the places and tracks walked between start and end, in the order
   walked: huts, junctions, saddles, lakes, bridges with names, tracks
   followed ("join the Greenstone Track"). Only places on the way, not things
   seen ("views of Mt Earnslaw"), not side trips. For an out-and-back way,
   list the way out only; its end is where it turns. Day/leg headings
   ("Day 1: Glade Wharf to Clinton Hut") give the order.
8. `direction`: `clockwise` or `anticlockwise` only if the text says so (or
   says which way it is described). Otherwise `null`.
9. `access_points`: every other place the page (often "Getting there") says
   the walk can be started from or joined at ("the four access points to this
   track are: …"), that is not already a way's start. Each with what it leads
   onto when the text says (`onto`: the track or place it joins, or `null`).
10. `side_trips`: places the text describes as side trips or optional detours
   off the way, with the place on the way they leave from when stated.
11. `huts`: the huts (and bivouacs, lodges, shelters with bunks) the main way
   passes or ends at, according to the text. A page often lists them ("There
   are 3 DOC huts on this track: …"). Huts mentioned only as nearby, on other
   walks, or reached by a side trip go in `not_on_route`.
12. `not_on_route`: places the text names that are not walked by any way:
    views, nearby attractions, other walks, places a side trip reaches.
13. `time`, `distance` (per way): only when the text gives them for that way
    (a heading's "Time: …", "Distance: …"), copied exactly; else `null`.
    Multi-day ways: the whole way's time if stated (often DOC's `time`).
14. `evidence`: for each way, the sentences (verbatim) that establish its
    start, end and shape.
15. `doubts`: everything you were unsure of, plainly. Empty string if none.
16. A page for several separate tracks ("X tracks", "walks in the Y area")
    that does not describe one walk: `kind: "network"`, and list each track
    the page describes as its own way only if the text gives its start and end.

## Output: `readings/<g>.json`, exactly this shape

```json
{
  "guid": "…",
  "kind": "one_way | out_and_back | loop | network | unclear",
  "ways": [
    {
      "id": "W1",
      "main": true,
      "label": "plain short name for this way, from the text's own words",
      "shape": "one_way | out_and_back | loop | null",
      "start": {"name": "…", "quote": "…", "ref": "L3 | T7 | null"},
      "end":   {"name": "…", "quote": "…", "ref": "…"},
      "via":   [{"name": "…", "quote": "…", "ref": "…"}],
      "direction": "clockwise | anticlockwise | null",
      "time": "DOC's time for this way, verbatim (\"6-8 hr return\"), or null",
      "distance": "DOC's distance for this way, verbatim (\"14 km return\"), or null",
      "evidence": ["…verbatim sentence…"]
    }
  ],
  "access_points":[{"name": "…", "quote": "…", "ref": "…", "onto": "track or place it joins, or null"}],
  "side_trips":   [{"name": "…", "quote": "…", "ref": "…", "from": "name of the place on the way, or null"}],
  "huts":         [{"name": "…", "quote": "…", "ref": "…"}],
  "not_on_route": [{"name": "…", "quote": "…", "ref": "…", "why": "view | nearby | other walk | side trip | other"}],
  "doubts": ""
}
```

For a loop, `end` is the same place as `start`. `start`/`end` may be `null`
(with a doubt) when the text does not say. Write valid JSON only.
