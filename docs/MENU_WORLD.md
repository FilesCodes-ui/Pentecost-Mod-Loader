# Menu World: design

Status: design plus compiling skeleton (patch 133). Nothing here runs in the game yet.

## Goal

Replace the spinning panorama behind Pentecost's main menu with a living, vanilla Minecraft 1.20.1 landscape:
real day and night, shaders applied, mobs going about their business, a camera that makes cinematic shots,
and a very long view distance from a prototype LOD system. It renders only what the camera sees.

Non-goals for the first versions: player interaction, sound, weather beyond clear and rain, mod content of any kind.

## Decisions

1. **A fake client level, no server.** Pentecost builds a `ClientLevel` and fills it with chunks it reads from disk.
   No server thread, no ticking world, no saving. Because it is a real `ClientLevel` drawn by the normal
   `LevelRenderer`, Embeddium and Oculus shaders apply to it unchanged.
2. **Vanilla blocks only.** The chunk data comes from a vanilla-generated cache, so no mod block, mob or biome can
   be in it. The level is never joined by a player and is dropped when the menu closes.
3. **Seeds are generated once, offline, in a mod-free JVM.** Running vanilla worldgen inside the modded game would
   let mod biome modifiers and features leak in. Instead a worker (`SeedPregen`, later) starts a separate JVM from the
   vanilla 1.20.1 client jar and libraries, runs the game's own server entry point with the chosen seed, force-loads a
   region, saves it, and exits. The result is a cache in `config/pentecost/menu_world/<seed>/`. Later launches only read it.
4. **Three world sources** behind one `WorldSource`: a curated seed (default), any seed the player types, or one of
   the player's own saves (read only, copied or read in place). A save is already generated, so it skips the worker.
5. **A camera director, not fixed paths.** Shots are composed at runtime from scored vantage points and a library
   of camera moves, so the menu never loops. See Camera.
6. **Render only what is seen.** The camera is on rails, so the next 20 seconds are known. Chunks load in a cone
   along the shot, nothing behind the camera is meshed, and chunks behind the shot are dropped.
7. **LOD for distance.** Past the real chunks a coarse terrain mesh built from the same cache covers the horizon.

## Architecture

```
PentecostTitleScreen
   |  (setting: Menu background = World)
   v
MenuWorld  ---- MenuClock (day cycle, golden hour, real-clock option)
   |
   +-- WorldSource -> SeedCatalog / typed seed / own save
   |        |
   |        +-- cache: config/pentecost/menu_world/<seed>/   (region files + lod tiles)
   |        +-- SeedPregen (separate vanilla JVM, run once)
   |
   +-- Terrain (height, water, colour per column; backed by the cache)
   |        |
   |        +-- VantageScanner -> Vantage list
   |        +-- LodBuilder     -> LodTile grid
   |
   +-- ShotDirector -> Shot -> CameraRig -> Pose -> game camera
   +-- AmbientLife (mob rules)
```

`Terrain` is the seam. Everything that decides where to look or what to draw far away works against it, so the
whole camera and LOD stack is testable without the game, using a fake terrain.

## Camera

A shot is: an eye rail and a look rail (arc-length parametrised Catmull-Rom splines), a duration, a speed curve,
a field of view range, a roll, and a handheld amount. The rig adds:

- **Critically damped look-at.** The camera aims at the look rail through a spring, so aim never snaps.
- **Terrain clearance.** The eye is lifted smoothly above the ground and water, never clipped.
- **Handheld drift.** Small slow yaw, pitch and roll noise, scaled per shot.
- **FOV breathing and optional vertigo** (field of view changes against the move).
- **Fades.** A short dip at the start and end of each shot, so cuts are soft.

Moves: orbit, dolly, crane, push-in, fly-through, reveal, establishing, and tracking (follows a mob, later).
Thousands of shots come from: scored vantage points (hundreds per region) x moves x continuous parameters
(distance, sweep, altitude, speed, FOV, roll) x time of day. The director favours crane, reveal and orbit at
sunrise and sunset, fly-through at midday, and slow wide shots at night. It never repeats a move twice in a row.

A vantage is scored by openness (how much of the line of sight is clear), relief along the view, prominence of the
viewpoint, and water in view. Later: biome variety and structure presence from the cache.

## Time

`MenuClock` runs a full day in 8 real minutes by default (setting later), or follows the player's real clock.
It drives sun, moon, sky colour and the shaders' time. Shots are chosen with the current time in mind.

## Ambient life

Client-only entities spawned in the camera's view cone, at least 24 blocks from the eye: sheep, cows, horses,
foxes, rabbits, parrots, bees, fish. No AI, no targets, no look-at-player. They follow short scripted wanders,
and are removed when they leave the view cone. A shot can follow one (tracking).

## LOD (prototype)

Real chunks cover the first ~12 chunks. Beyond that, a grid of tiles at coarser and coarser cells:
4 blocks to 512, 16 blocks to 2048, 64 blocks to 8192. Each cell stores height, colour and a water flag, sampled
from the cache. Tiles are built on a worker thread, drawn as one mesh per tile, and faded into the real chunks
with fog. This is a prototype: no caves, no trees as geometry, colour from the top block only.

## Seeds

The default is chosen from published 1.20 lists; **none has been looked at in game yet**. The scanner decides where
the camera actually goes, so a seed only needs to have good terrain somewhere near its anchor.

| Name | Seed | Anchor (x, z) | Source note |
|---|---|---|---|
| Cherry village (default) | 4556775 | -1792, 544 | village beside a cherry grove (Badlion, 1.20) |
| Cherry mines | -769185976586290351 | -125, -500 | cherry groves, village (Pro Game Guides, 1.20) |
| Cherry forest | -5592633653095784779 | -812, 1376 | large cherry forest (1.20 list) |
| Canyon village | 6542604540806855638 | 0, 0 | village in a canyon ringed by cherry grove (Sportskeeda, 1.20) |
| Mansion woods | -656589980139732745 | 24, -18 | dark forest mansion, grove, lush cave (GameSkinny, 1.20.1) |
| Pink petals | -3779232957303710202 | 0, 0 | cherry grove over ancient cities (Pro Game Guides, 1.20.1) |

Biome generation is the same across 1.20 and 1.20.1. Structure placement is the Java Edition version of each.

## Settings (planned)

Interface, Menus: Menu background (Panorama, World), World source (Seed, Typed seed, My save), Day length,
Follow real clock, View distance, Mobs on or off. Default stays Panorama until the world is proven.

## Milestones

1. Skeleton and math (this patch): seeds, clock, camera director and rig, LOD builder, all compiling, with a headless demo.
2. Pregen worker: separate vanilla JVM, cache layout, progress UI in the settings screen.
3. Fake `ClientLevel`: chunk feeder from the cache, light data, rendered behind the menu, no camera yet.
4. Camera on the game: the rig drives the real camera. Time of day drives the sky. Shaders checked.
5. Visibility streaming: load and drop chunks by shot.
6. Ambient life.
7. LOD rendering.
8. Own saves and typed seeds, settings, polish.

## Risks

- Building a level outside a normal join may fire events other mods listen to. We may need to suppress them for this level.
- Anything that reads `Minecraft.level` will see the fake level. The menu must clear it when closing.
- Pregen size: a 3000 x 3000 block area is about 35,000 chunks. Tiers (small, medium, large) will trade size for variety.
- Oculus and Embeddium may assume a joined world. Needs testing early (milestone 3).
- Worker JVM needs the vanilla jar and libraries. On Fabric-only or Forge packs we locate them from the version JSON.
- Seeds are unverified visually until milestone 3.
