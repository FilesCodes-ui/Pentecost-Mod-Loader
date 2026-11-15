> SUPERSEDED 2026-11-14: Pentecost no longer builds its own LOD. Distant Horizons is the LOD and Pentecost patches it (see HANDOFF.md). Kept for reference; the code is in _parked/lod.

# Pentecost LOD: design draft

Status: draft for Gabriel to decide on. Nothing here is built. It comes from reading the current prototype
(`menuworld/lod/LodBuilder`, `LodTile`, `source/Terrain`) and `docs/MENU_WORLD.md`, and from what I remember of how
Distant Horizons (DH) works from the outside. I have not read DH's code and there is no DH jar in this repo. Facts
about DH and about the 1.20.1 network protocol below are from memory and marked "check".

## Goals (from Gabriel)

1. Universal, like the rest of Pentecost: any world, any pack, any server.
2. Works on servers the player does not own. Works better when the server runs Pentecost.
3. Generates terrain that does not exist yet.
4. Real colours and textures, including blocks from other mods and resource packs.
5. More compatible than DH (shaders, Embeddium, other mods) and better looking and faster where it can be.
6. Clean room: DH may be studied for behaviour, never copied.

## What the prototype has, and what it lacks

The prototype keeps one surface per column: height, one colour, a water flag, in tiles of 4, 16 and 64 block cells.
That is enough for a menu backdrop. It cannot show trees, overhangs, cave mouths, biome tint changes, or textures,
and it has no storage, no updates, and no drawing. The new system replaces it, and the menu world then uses the same
system with its cache as one more data source.

## Data model

A column holds a short list of vertical spans, not one value: `{minY, maxY, block, biome, skyLight, blockLight}`.
The block is an index into a per-world palette of block states, so modded blocks work without special cases.
Several spans per column give trees (trunk, leaves), overhangs and cave openings. Coarser levels merge the spans of
the finer level beneath them, like mip levels: 1, 2, 4, 8 ... blocks per cell, kept as a quadtree of sections.
Each section carries a "last seen" time, so old data can be replaced by newer data from any source.

## Where the data comes from (best source wins, per section)

1. **Chunks the client has loaded.** Works on every server. Every chunk the player loads writes its columns at full
   detail and updates the coarse levels above it. Block changes update them later, debounced.
2. **A Pentecost server.** The server builds and keeps the data itself, and sends sections to the client over a
   Pentecost-only channel (check what Forge's channel API allows here). Vanilla and other clients are not affected.
3. **The local world generator:** the integrated server of a singleplayer world, a WWLS or LAN host. Exact, because
   it is the world's own generator. Runs at low priority while the player plays.
4. **Predicted terrain on a server Pentecost does not control.** See the next section. This one is optional and is
   labelled in the UI as a guess.

## Generating terrain that does not exist

- **Own world, or a Pentecost server:** generate ahead with the real generator, in a time budget. On a Pentecost
  server the work is done once and shared by every player. This is the same idea as the menu world's pregen, but
  inside the world's own server, so modded worldgen is correct.
- **Someone else's server:** the client cannot know the seed. It can only hold a guess. My understanding is that the
  server sends the *hashed* seed in its login packet (check `ClientboundLoginPacket` for 1.20.1), which cannot be
  turned back into a seed but can confirm one. So the player enters the seed (from the server's wiki, say), the client
  hashes it and compares, and a match turns prediction on. The prediction then runs the vanilla 1.20.1 generator in a
  worker, the way the menu world does.
- **What a prediction gets wrong:** structures that need the exact generator settings, worldgen from mods or datapacks
  (I believe 1.20.1 does not send noise settings to the client, so a server with custom worldgen cannot be
  reproduced; check), and everything players built or dug. Two safeguards: (a) while the player moves, compare the
  prediction with the real chunks that load. If they disagree beyond a limit, switch prediction off for that server.
  (b) Real data always replaces predicted data, section by section.
- **Not predicted:** anything the guess cannot represent. It stays empty rather than wrong.

Decision for Gabriel: is "type the seed once per server, with the hash check" acceptable, or should prediction exist
only for own worlds and Pentecost servers?

## Colours and textures

- Block colour comes from the game's own textures: when resources load, take each block's top-face sprite from its
  baked model and average it. That follows resource packs and covers other mods' blocks. If a model cannot be read,
  fall back to the block's map colour.
- Biome tint (grass, leaves, water) is applied when the mesh is built, from the stored biome id and the game's colour
  maps, so tint changes cost no extra storage.
- **Near LOD (cells of 1 to 4 blocks) is textured:** it uses real block-face quads with the block atlas, so it looks
  like terrain, not a painted surface. **Far LOD uses flat averaged colours,** where texture detail would be sub-pixel
  anyway. The switch point is a setting.
- Lighting: store sky and block light of the top span. Shader packs do their own lighting, so this mostly matters
  without a pack.

## Drawing, and compatibility

This is where most of the risk is, so the plan is conservative.

- **Use the game's own render types and vertex format** for LOD meshes, so a shader pack sees LOD terrain as terrain
  and applies its terrain programs. A separate custom shader is the most common reason a LOD mod breaks under shaders.
  Plan: a LOD pass that draws with the terrain program, then the vanilla chunks over it.
- **Depth:** vanilla's far plane is too near for LOD. Two options: (A) draw LOD in a first pass with its own, farther
  projection, clear depth, then draw vanilla chunks (what I remember DH doing; check); (B) extend the far plane for
  the whole world pass, which is simpler but touches everything shaders do with depth. Start with A.
- **Overlap with real chunks:** keep a bitmask of loaded chunks, updated each frame, and skip LOD cells beneath
  them. Fade over a few blocks, and keep a small depth offset so the seam does not flicker.
- **Embeddium and Oculus** hook the same places vanilla does. The LOD pass should not replace or wrap their chunk
  renderer, only run before it. Needs a test matrix: vanilla, Embeddium, Embeddium + Oculus with two shader packs,
  with and without Flywheel/Create.
- **Mesh building** on worker threads, only walls where neighbouring heights differ, no hidden faces, one buffer per
  section, uploaded with a per-frame budget.
- **Safe mode:** any exception or GL error in the LOD pass turns LOD off and draws vanilla, as `MenuHost.frame`
  already does for the menu world. A marker file turns it off at the next start if the game stopped while it was on.

## Storage

One folder per world or server (keyed by server address plus dimension, and by seed where known), in Pentecost's own
compressed section files. Sections are written on a background thread and read back when the player returns. A
Pentecost server stores the same format so it can send files as they are. Size has to be measured, not guessed: I would
build the storage first and measure it on a real explored world before choosing the default distance limits.

## Servers

- A Pentecost-only channel with a version handshake. Section requests, deltas, compression, a rate limit per player.
- **Privacy and cheating:** LOD from a server shows terrain and builds far from the player. The server owner needs
  a switch: generated terrain only (default), or including player changes, with a minimum age before changes show.
  Data the player could not have seen is the owner's decision, not ours.
- A server that does not run Pentecost is never sent anything extra. For it, LOD comes from sources 1 and 4 only.

## Studying DH without copying it

Gabriel's call: study how DH generates far terrain quickly and how it optimises, not how it is coded. So far this was
read from its public changelogs, its issue tracker and community explanations, not from its source. Findings are in
the next section. The licence matters (I believe it is LGPL; check). Code here is written from this document, not
from DH's source.

## What DH's public material says about speed (not verified against its code)

- **It stops generation early.** Its generator has modes: only existing chunks; surface; features; full. As the
  community describes it, surface and features use DH's own multithreaded generation, which is much faster but only
  "almost correct" and is not saved as real chunks. Full uses the game's real generation, is exact, and saves
  chunks. Another mode asks the game's own server to generate the chunks.
- **It has a CPU budget:** thread presets and a dynamic thread count, so background work can be turned up or down.
- **Servers:** from 2.3 the server generates LODs, and the client turns high detail into the lower levels. A newer
  option has the server send every level already reduced, so the client does none of that work.
- **Pregeneration** with Chunky is widely recommended next to it, since real chunks are the fastest source of LOD data.
- **Shader packs have to opt in.** DH terrain shows only under packs that implement support for it, and the seam with
  the real chunks looks worse under shaders. This is the main compatibility gap to beat (see Drawing).
- **Mod worldgen breaks the fast modes.** One public compat patch exists because TerraFirmaCraft decorates a chunk
  from its neighbours' biomes and DH's fast mode hands it blank ones. DH also marks such chunks as finished, so bad
  data stays until the database is deleted.

## What Pentecost should take from that (ideas, to be measured)

1. **Do only the stages a level needs.** A far level needs height, biome and surface block, not caves, ores, features
   or structures. And a coarse level does not need every block: sample at the cell size of the level, so cost follows
   the number of cells, not blocks. Guess to test: this is far faster than the menu world's full generation, which
   took 214 to 311 s for 7744 chunks on this PC.
2. **Coarse first, refine as the player nears.** Fill the far levels everywhere, then the finer ones by distance and
   by where the player faces.
3. **Never fake what we can generate properly.** If the world's chunk generator is not vanilla's (modded worldgen),
   use the real generator, slower, instead of a shortcut that gives bare or wrong terrain.
4. **Stamp the data.** Each section records how it was made (stage, generator, mod set, format version). Data made
   by a shortcut is replaced when something better arrives, and a mod set change invalidates it, so wrong data never
   becomes permanent.
5. **A budget the player can see:** one CPU load setting and a thread count that adjusts to the game's load.
6. **Servers send already-reduced levels,** so clients spend no time converting.

## Milestones

1. Data model, section storage, and the sampler from loaded chunks. Headless tests with a fake terrain, like the camera.
2. Meshing and drawing, flat colours, vanilla only, then with Embeddium.
3. Shader packs (Oculus): the terrain-program path, depth handling, the seam.
4. Textured near LOD, block colours from resource packs and mods, biome tint.
5. Local generation ahead of the player (own worlds, WWLS, LAN).
6. Pentecost server channel and server-side generation.
7. Predicted terrain on other servers: seed entry, hash check, self-check.
8. Move the menu world onto the new system and delete the prototype tiles.

Rule from HANDOFF: Gabriel tests in the game. I build, compile and run headless checks only.

## Decisions

Decided by Gabriel (2026-11-14):
- Server privacy default: **both** generated terrain and player changes are shared. The owner still gets a switch to
  turn player changes off, and the data says which kind it is.
- DH may be studied for its optimisation techniques (not its code).

Decided by me, to be tested early:
- **Draw in two passes** (LOD first with its own far projection, then vanilla chunks), not an extended far plane. It
  leaves vanilla's projection alone, so it is the less risky start. Milestone 3 checks it under Oculus; if shader
  packs break on the depth clear, the other option is tried.

Still open:
1. Where the seed comes from on a server Pentecost does not control (see below).
2. Default distance limits and disk budget, after storage is measured.

### Getting the seed on servers Pentecost does not control

A client is never sent the seed, only a hash of it (check for 1.20.1), so Pentecost cannot read it. Ways to get it:
a. The server runs Pentecost, which sends it. Exact.
b. The player has permission for `/seed`. Pentecost can run it and read the reply. Exact.
c. The player types it. Exact, checked against the hash.
d. Work it out from the world itself: the structures and biomes the client has received are enough to find a seed in
principle, and tools exist that do this. It is passive, but many servers treat those tools as cheating and ban them,
and it only works for vanilla-style worldgen. Not recommended as a default; if wanted, an opt-in setting.
In every case the hash check confirms the seed before prediction starts.
