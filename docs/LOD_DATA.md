> SUPERSEDED 2026-11-14: Pentecost no longer builds its own LOD. Distant Horizons is the LOD and Pentecost patches it (see HANDOFF.md). Kept for reference; the code is in _parked/lod.

# Pentecost LOD: data model and storage spec (milestone 1)

Status: draft spec, 2026-11-14. Nothing is built. Companion to `docs/LOD.md`, which has the goals and the other
milestones. Numbers marked "measure" are guesses until the first benchmark. I have not yet read `LodBuilder` or `LodTile`
for this spec; the first coding step is to check these types against them.

## Decision recorded: seed finding

The opt-in "work out the seed from the world" option (LOD.md, seed route d) is left out. Servers ban those tools, and
Pentecost should not ship one by default. The three exact routes stay: Pentecost server, `/seed`, typed seed (all
checked against the login hash). The code keeps a `SeedProvider` interface, so a solver could be added later without
touching anything else.

## Levels and sections

- Level `L` has cells of `2^L` blocks per side. Level 0 is one block per cell.
- A section is always 64 by 64 cells, at every level. So a section covers `64 * 2^L` blocks per side: 64 at level 0,
  128 at level 1, up to 4096 at level 6. Levels 0 to 6 to start; level 7 and up only if measurement asks for it.
- A section key is `(dimension, level, sx, sz)`. Its children are the four sections at level `L-1` that cover the
  same area, so the whole thing is a quadtree, as LOD.md says.
- Level 0 sections line up with 4 by 4 chunks, so a loaded chunk updates exactly one level-0 section.

## Column and span

A column holds 0 to `maxSpans` spans, sorted from the top down. Span fields:

| Field | Bits | Notes |
|---|---|---|
| minY | 12 | offset from the dimension's min build height |
| maxY | 12 | inclusive top, same offset |
| block | 16 | index into the section's block palette |
| biome | 8 | index into the section's biome palette |
| skyLight | 4 | light at the top of the span |
| blockLight | 4 | same |
| flags | 4 | bit 0 translucent (water, glass, ice), bit 1 top face exposed, bit 2 has tint, bit 3 spare |

That is 60 bits, stored in one `long` in memory. `maxSpans` by level (measure): 8 at levels 0 to 1, 4 at levels 2
to 3, 2 at levels 4 and up.

The block is a palette index, never a registry id, so modded blocks, renamed blocks and worlds with different mod sets
never share raw ids. Palettes are stored in each section file as block state strings (`modid:block[prop=value]`),
which also lets a changed mod set be detected (see Provenance).

## Provenance stamp (per section)

Every section stores one stamp:

| Field | Meaning |
|---|---|
| source | `LOADED_CHUNK`, `SERVER_SENT`, `LOCAL_GEN`, `PREDICTED` |
| stage | `HEIGHT`, `SURFACE`, `FEATURES`, `FULL`, `REAL` (how much of the generator ran) |
| generatorId | hash of the generator kind and settings (vanilla version, seed hash, noise settings where known) |
| modSetHash | hash of the loaded mod ids and versions that touch worldgen or blocks |
| formatVersion | this spec's version |
| changesIncluded | whether player changes are part of the data |
| stamp | wall-clock time written |

**Rank** decides which data wins when two sources cover the same section, highest first:
1. `LOADED_CHUNK` (stage `REAL`)
2. `SERVER_SENT` from a Pentecost server
3. `LOCAL_GEN` at stage `FULL`
4. `LOCAL_GEN` at stage `FEATURES`, then `SURFACE`, then `HEIGHT`
5. `PREDICTED`

Rules:
- A new section replaces the stored one only if its rank is higher, or equal and newer.
- If `modSetHash` or `generatorId` differs from the current world's, the stored section drops to rank 0: it still
  draws, but any source replaces it, and it is queued for regeneration. This is the fix for DH's stuck bad chunks.
- Data from a `PREDICTED` source is never written to the player's permanent store for a foreign server unless the
  player turns that on. It lives in a separate cache folder that can be deleted safely.
- A section is never marked finished by a shortcut. `stage` says what ran, and a later stage replaces it.

## Reduction (level `L` from level `L-1`)

For each parent cell, take the 2 by 2 child columns and build the parent's spans:
1. Collect all child spans, convert their Y ranges to the parent's scale (heights are not rescaled, only cells merge).
2. Merge spans that overlap in Y or touch, keeping the block that covers most of the merged span (ties: the child
   with the higher top). Take biome and tint flag from the topmost child. Light is the max of the children's.
3. Always keep the topmost span. Then, if the count is over `maxSpans` for the level, drop the thinnest span that is
   not the topmost, and merge its neighbours across the gap if the gap is under half a cell.
4. Translucent spans (water) are kept as their own span on top of the solid span below, never merged into it.

A section reduces lazily: marked dirty when a child changes, rebuilt on a worker thread, debounced (measure, start at
2 s). The parent keeps the lowest provenance rank among its children, so a mixed area never claims to be exact.

## Storage

- One folder per world or server: `lod/<key>/<dimension>/L<level>/`. The key is the server address plus the seed hash
  where known, or the save folder name for local worlds.
- Start with **one file per section** (`<sx>_<sz>.pls`), grouped under 16 by 16 region folders so no folder holds
  thousands of files. Measure the file count on an explored world. Only if it hurts, switch to region files with an
  index, as Anvil does. The file reader and writer sit behind one `LodStore` interface so the switch changes nothing
  else.
- File layout, all inside one `java.util.zip` Deflater stream (no new dependency; LZ4 or Zstd later only if measured
  to matter):
  1. header: magic `PLOD`, formatVersion, level, sx, sz
  2. provenance stamp
  3. block palette (state strings), biome palette (ids as strings)
  4. column table: 4096 entries, each a span count (0 to `maxSpans`)
  5. spans, column by column, 8 bytes each (the 60 bits above)
- Writes happen on one background thread, to a temporary file then a rename, so a crash never leaves a half file.
  A section that fails to read is deleted and rebuilt, and logged once.
- A Pentecost server stores the same files and can send them as they are, which is why the layout has no
  client-only fields.

## In memory

- `LodStore` keeps a least-recently-used set of decoded sections with a byte budget (setting; start at 512 MB,
  measure). Evicted sections are already on disk, so eviction never loses data.
- Dirty sections go on a write queue with a cap. If the queue is full, the oldest dirty reduced sections are dropped
  (they can be rebuilt from level 0), never level-0 ones.

## Interfaces (names are proposals)

- `LodStore`: `get(key)`, `put(key, section)` (applies the rank rule), `markDirty(key)`, `flush()`.
- `LodSource`: `request(key, rankFloor)` to a source that can supply a section: loaded chunks, server, local
  generator, predictor. The scheduler asks the best available source first.
- `SeedProvider`: `find(server)` returning a seed or empty. Implementations: Pentecost server, `/seed`, typed.
- `LodSampler`: turns a loaded chunk into level-0 columns. This is what `LodBuilder` does for the prototype today.

## Tests (headless, no game)

1. **Round trip:** a fake terrain (flat, hills, a tree, an overhang, a lake) goes into a section, is written, read
   back, and must be equal.
2. **Reduction:** the fake terrain reduced three levels keeps its top surface and its water, and never exceeds
   `maxSpans`.
3. **Rank:** every pair of sources, both orders, gives the expected winner. A changed `modSetHash` drops the rank.
4. **Crash safety:** a write cut off halfway leaves the old file readable.
5. **Benchmark:** build level-0 and level-1 to 6 sections from the menu-world cache (Canyon village, radius 44) and
   time it against the full generation's 214 to 311 s. Print the bytes per section and per square kilometre.

## Open points

- Whether 64 by 64 cells per section is right; 32 or 128 may fit the mesher better. Decide after the benchmark.
- Whether `maxSpans` of 8 at level 0 is enough for dense forests and caves.
- Where biome tint is stored for blocks that tint by position, not by biome (grass colour noise in some packs).
- How a `PREDICTED` section is marked on screen for the player (LOD.md says "labelled as a guess").
