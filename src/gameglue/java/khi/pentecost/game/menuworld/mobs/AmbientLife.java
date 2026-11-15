package khi.pentecost.game.menuworld.mobs;

import khi.pentecost.boot.Log;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Life in the menu world. The kinds of mob come from each biome's own spawn list, so cows and sheep stand in grass,
 * polar bears on snow and camels in the desert, in the group sizes the game uses. Only vanilla mobs ever appear.
 * They are client-only entities with no AI and never look at the viewer.
 *
 * Movement is the game's own way of moving mobs it does not control: each tick the mob is told where it should be and
 * the game eases it there while it plays the walking animation. A mob walks to a spot nearby, turns before it walks,
 * stands for a while, then walks on. Flyers (bees, parrots) circle over the ground.
 *
 * Mobs appear far from the camera, some in the picture and some just outside it, biased to where the camera is
 * heading, and never nearer than {@link #MIN_DISTANCE} blocks. Hostile mobs only show at night, a few at a time.
 *
 * Call {@link Crowd#update} once per frame from the render thread.
 */
public final class AmbientLife {
    private AmbientLife() {}

    /** Never nearer than this to the camera, so nothing is ever seen up close or notices the viewer. */
    public static final double MIN_DISTANCE = 24.0;

    public static final double SPAWN_MARGIN_DEGREES = 8.0;
    public static final double DESPAWN_MARGIN_DEGREES = 25.0;

    private static final double MAX_DISTANCE = 110.0;
    /** In view, nothing appears nearer than this: far enough that appearing is not noticed. */
    private static final double VIEW_SPAWN_MIN = 55.0;
    private static final int CAP = 30;
    private static final int HOSTILE_CAP = 4;
    private static final int MAX_GROUP = 4;

    /** Ids that need water, a rider or a fight to make sense, and the bosses. */
    private static final Set<String> NEVER = Set.of(
            "player", "marker", "armor_stand", "ender_dragon", "wither", "giant", "illusioner", "guardian",
            "elder_guardian", "squid", "glow_squid", "cod", "salmon", "pufferfish", "tropical_fish", "tadpole",
            "dolphin", "axolotl", "shulker");
    /** Mobs that hang in the air: they circle above the ground. */
    private static final Set<String> FLYERS = Set.of("bee", "parrot", "bat", "ghast", "phantom", "vex", "allay", "blaze");

    /** Ground a mob may stand on, by block name. Names, not tags: tags are not bound until a real world is joined. */
    private static final Set<String> NATURAL = Set.of(
            "grass_block", "dirt", "coarse_dirt", "podzol", "mycelium", "rooted_dirt", "moss_block", "mud", "sand",
            "red_sand", "snow", "snow_block", "powder_snow", "stone", "granite", "diorite", "andesite", "deepslate",
            "tuff", "calcite", "gravel", "ice", "packed_ice", "blue_ice", "terracotta", "dirt_path", "farmland");

    private static boolean naturalGround(BlockState s) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(s.getBlock());
        if (!"minecraft".equals(id.getNamespace())) return false;
        String p = id.getPath();
        return NATURAL.contains(p) || (p.endsWith("terracotta") && !p.contains("glazed"));
    }

    private static final class Active {
        final Mob mob;
        final int id;
        final boolean flies, hostile;
        double px, py, pz, tx, tz, wait, speed, cur, phase, lift;
        float yaw;
        int age, firstTick;

        Active(Mob mob, int id, boolean flies, boolean hostile, double speed, Random rnd) {
            this.mob = mob;
            this.id = id;
            this.flies = flies;
            this.hostile = hostile;
            this.speed = speed;
            this.phase = rnd.nextDouble() * 6.28;
            this.yaw = rnd.nextFloat() * 360F - 180F;
            this.wait = 0.5 + rnd.nextDouble() * 4;
            this.px = mob.getX();
            this.py = mob.getY();
            this.pz = mob.getZ();
            this.tx = px;
            this.tz = pz;
            this.firstTick = mob.tickCount;
        }
    }

    /** The mobs in one menu level. */
    public static final class Crowd {
        private final ClientLevel level;
        private final Random rnd = new Random();
        private final RandomSource rs = RandomSource.create();
        private final List<Active> live = new ArrayList<>();
        private final Set<EntityType<?>> unusable = new HashSet<>();
        private int nextId = 1_500_000;
        private double spawnIn = 0.5;
        private double acc;
        private int failures;
        private boolean off;
        // where the camera is heading, so mobs appear ahead of it
        private boolean haveCam;
        private double lastX, lastZ, velX, velZ;
        // set when the level turns out not to tick these mobs: they are then moved and animated here
        private boolean selfTick;
        private boolean warned;
        private int tried, noList, badType, spawned, notNatural;
        private double logIn = 8;

        public Crowd(ClientLevel level) {
            this.level = level;
        }

        public int size() {
            return live.size();
        }

        /**
         * @param camX,camY,camZ camera position
         * @param yaw            camera yaw in degrees, as the game uses it
         * @param fov            vertical field of view in degrees
         * @param aspect         window width over height
         * @param gameTicks      unused: mobs step on their own 20 per second clock
         * @param dayTicks       time of day in ticks
         */
        public void update(double camX, double camY, double camZ, double yaw, double fov, double aspect, int gameTicks,
                           long dayTicks, double dt) {
            if (off) return;
            try {
                double half = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(fov) / 2) * Math.max(1.0, aspect)));
                boolean night = dayTicks % 24000 > 13200 && dayTicks % 24000 < 22800;

                if (haveCam && dt > 0) {
                    double k = 1 - Math.exp(-dt / 0.4);
                    velX += (Mth.clamp((camX - lastX) / dt, -80, 80) - velX) * k;
                    velZ += (Mth.clamp((camZ - lastZ) / dt, -80, 80) - velZ) * k;
                }
                lastX = camX;
                lastZ = camZ;
                haveCam = true;

                logIn -= dt;
                if (logIn <= 0) {
                    logIn = 10;
                    Log.info("Menu world mobs: " + live.size() + " live, " + spawned + " spawned, " + tried
                            + " tries, not natural ground " + notNatural + ", no biome list " + noList
                            + ", unusable type " + badType + (selfTick ? ", moved by hand" : ""));
                }
                spawnIn -= dt;
                if (spawnIn <= 0) {
                    spawnIn = 0.25 + rnd.nextDouble() * 0.35;
                    trySpawn(camX, camZ, yaw, half, night);
                }

                acc += dt;
                int steps = 0;
                while (acc >= 0.05 && steps < 3) {
                    acc -= 0.05;
                    steps++;
                }
                if (acc > 0.15) acc = 0;

                Iterator<Active> it = live.iterator();
                while (it.hasNext()) {
                    Active a = it.next();
                    if (outOfSight(a, camX, camZ, yaw, half) || (a.hostile && !night)) {
                        remove(a);
                        it.remove();
                        continue;
                    }
                    for (int i = 0; i < steps; i++) step(a);
                }
            } catch (RuntimeException e) {
                if (++failures >= 4) {
                    off = true;
                    Log.warn("Menu world mobs are off for this session: " + e);
                    clear();
                }
            }
        }

        /** Removes every mob. Call before the level is dropped. */
        public void clear() {
            for (Active a : live) remove(a);
            live.clear();
        }

        // ------------------------------------------------------------------ appearing and leaving

        private void trySpawn(double camX, double camZ, double yaw, double half, boolean night) {
            if (live.size() >= CAP) return;
            tried++;
            int hostile = 0;
            for (Active a : live) if (a.hostile) hostile++;
            boolean wantHostile = night && hostile < HOSTILE_CAP && rnd.nextDouble() < 0.3;

            boolean inView = rnd.nextDouble() < 0.7;
            double off;
            double dist;
            if (inView) {
                off = (rnd.nextDouble() * 2 - 1) * half * 0.9;
                dist = VIEW_SPAWN_MIN + rnd.nextDouble() * (MAX_DISTANCE - VIEW_SPAWN_MIN);
            } else {
                double side = rnd.nextBoolean() ? 1 : -1;
                off = side * (half + SPAWN_MARGIN_DEGREES + rnd.nextDouble() * 12);
                dist = MIN_DISTANCE + 8 + rnd.nextDouble() * (MAX_DISTANCE - MIN_DISTANCE - 8);
            }
            double bearing = Math.toRadians(yaw + off);
            // Shift toward where the camera is going, so there is life where it arrives.
            double lx = velX * 2.0, lz = velZ * 2.0;
            double ll = Math.hypot(lx, lz);
            if (ll > 60) {
                lx *= 60 / ll;
                lz *= 60 / ll;
            }
            double x = camX + -Math.sin(bearing) * dist + lx * 0.5;
            double z = camZ + Math.cos(bearing) * dist + lz * 0.5;
            if (Math.hypot(x - camX, z - camZ) < MIN_DISTANCE + 6) return;
            if (!ground(x, z)) return;

            Biome biome = level.getBiome(new BlockPos(Mth.floor(x), (int) height(x, z), Mth.floor(z))).value();
            MobCategory cat = wantHostile ? MobCategory.MONSTER : MobCategory.CREATURE;
            Optional<MobSpawnSettings.SpawnerData> pick = biome.getMobSettings().getMobs(cat).getRandom(rs);
            if (pick.isEmpty()) {
                noList++;
                return;
            }
            MobSpawnSettings.SpawnerData data = pick.get();
            EntityType<?> type = data.type;
            ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
            if (!"minecraft".equals(id.getNamespace()) || NEVER.contains(id.getPath()) || unusable.contains(type)) {
                badType++;
                return;
            }

            int max = Math.max(data.minCount, Math.min(data.maxCount, MAX_GROUP));
            int count = wantHostile ? 1 : data.minCount + rnd.nextInt(Math.max(1, max - data.minCount + 1));
            count = Math.max(1, Math.min(count, CAP - live.size()));
            for (int i = 0; i < count; i++) {
                double sx = i == 0 ? x : x + (rnd.nextDouble() - 0.5) * 9;
                double sz = i == 0 ? z : z + (rnd.nextDouble() - 0.5) * 9;
                spawnOne(type, id.getPath(), sx, sz, wantHostile);
            }
        }

        private void spawnOne(EntityType<?> type, String path, double x, double z, boolean hostile) {
            if (!ground(x, z)) return;
            Entity e;
            try {
                e = type.create(level);
            } catch (RuntimeException ex) {
                unusable.add(type);
                return;
            }
            if (!(e instanceof Mob mob)) {
                unusable.add(type);
                return;
            }
            boolean flies = FLYERS.contains(path);
            double attr = 0.2;
            try {
                if (mob.getAttributes().hasAttribute(Attributes.MOVEMENT_SPEED)) {
                    attr = mob.getAttributeValue(Attributes.MOVEMENT_SPEED);
                }
            } catch (RuntimeException ignored) {
                // the default stands
            }
            double speed = flies ? 3.5 : Mth.clamp(attr * 13.0, 1.2, 3.4) * (0.8 + rnd.nextDouble() * 0.2);
            int eid = nextId++;
            double y = height(x, z) + (flies ? 5 + rnd.nextDouble() * 5 : 0);
            if (path.equals("ghast") || path.equals("phantom")) y += 12;
            float yaw = rnd.nextFloat() * 360F - 180F;
            mob.setPos(x, y, z);
            mob.setYRot(yaw);
            mob.yBodyRot = yaw;
            mob.yBodyRotO = yaw;
            mob.setYHeadRot(yaw);
            mob.yHeadRotO = yaw;
            mob.setNoAi(true);
            mob.setSilent(true);
            mob.setNoGravity(true);
            mob.setCustomNameVisible(false);
            mob.setInvulnerable(true);
            mob.setOnGround(!flies);
            mob.setOldPosAndRot();
            level.putNonPlayerEntity(eid, mob);
            live.add(new Active(mob, eid, flies, hostile, speed, rnd));
            spawned++;
        }

        private boolean outOfSight(Active a, double camX, double camZ, double yaw, double half) {
            double dx = a.px - camX, dz = a.pz - camZ;
            double dist = Math.hypot(dx, dz);
            if (dist > MAX_DISTANCE + 25 || !level.hasChunk(Mth.floor(a.px) >> 4, Mth.floor(a.pz) >> 4)) {
                return true;
            }
            if (dist < MIN_DISTANCE - 6) {
                return true; // the camera flew toward it
            }
            double bearing = Math.toDegrees(Math.atan2(-dx, dz));
            double diff = Math.abs(Mth.wrapDegrees(bearing - yaw));
            return diff > half + DESPAWN_MARGIN_DEGREES + 20;
        }

        private void remove(Active a) {
            try {
                level.removeEntity(a.id, Entity.RemovalReason.DISCARDED);
            } catch (RuntimeException e) {
                // the level is being dropped
            }
        }

        // ------------------------------------------------------------------ one tick of a mob

        private void step(Active a) {
            Mob m = a.mob;
            double x = a.px, z = a.pz;
            boolean walking = false;
            if (a.wait > 0) {
                a.wait -= 0.05;
            } else {
                double dx = a.tx - x, dz = a.tz - z;
                double d = Math.hypot(dx, dz);
                if (d < 0.7) {
                    // arrived: stand a while, then choose somewhere else to go
                    a.wait = a.flies ? 0.4 : 3 + rnd.nextDouble() * 9;
                    double r = a.flies ? 18 : 5 + rnd.nextDouble() * 9;
                    double ang = rnd.nextDouble() * Math.PI * 2;
                    double nx = x + Math.cos(ang) * r, nz = z + Math.sin(ang) * r;
                    if (ground(nx, nz) && ground((x + nx) / 2, (z + nz) / 2)) {
                        a.tx = nx;
                        a.tz = nz;
                    } else {
                        a.tx = x;
                        a.tz = z; // water or no land that way: stand and choose again
                        a.wait = 1 + rnd.nextDouble() * 2;
                    }
                } else {
                    // turn first, then walk: nothing slides sideways
                    float want = (float) Math.toDegrees(Math.atan2(-dx, dz));
                    float diff = Mth.wrapDegrees(want - a.yaw);
                    a.yaw = Mth.wrapDegrees(a.yaw + Mth.clamp(diff, -12F, 12F));
                    walking = Math.abs(diff) < 45F;
                }
            }
            // ease the pace in and out, so a mob never starts or stops at full speed
            a.cur += ((walking ? a.speed : 0) - a.cur) * 0.2;
            if (a.cur > 0.03) {
                double stepLen = a.cur / 20.0;
                double nx = x + -Math.sin(Math.toRadians(a.yaw)) * stepLen;
                double nz = z + Math.cos(Math.toRadians(a.yaw)) * stepLen;
                if (ground(nx, nz)) {
                    x = nx;
                    z = nz;
                } else {
                    a.tx = x;
                    a.tz = z;
                    a.wait = 1 + rnd.nextDouble() * 2;
                    a.cur = 0;
                }
            }
            a.px = x;
            a.pz = z;

            // Height follows the land smoothly: a step up is climbed, never a pop.
            double ty = height(x, z);
            if (a.flies) {
                a.phase += 0.05;
                ty += 6.0 + Math.sin(a.phase * 1.3) * 1.8;
            }
            a.py += Mth.clamp(ty - a.py, -0.45, 0.16);

            a.age++;
            if (!selfTick && a.age == 40 && m.tickCount == a.firstTick) {
                selfTick = true;
                if (!warned) {
                    warned = true;
                    Log.warn("Menu world mobs: the level does not tick them, so they are moved by hand");
                }
            }
            if (selfTick) {
                m.setOldPosAndRot();
                m.tickCount++;
                m.setPos(x, a.py, z);
                m.setYRot(a.yaw);
                m.setYHeadRot(a.yaw);
                m.yBodyRot = a.yaw;
                m.setOnGround(!a.flies);
                m.calculateEntityAnimation(a.flies);
            } else {
                // The game moves and animates the mob from here during its own tick.
                m.lerpTo(x, a.py, z, a.yaw, 0F, 3, false);
                m.lerpHeadTo(a.yaw, 3);
                m.setOnGround(!a.flies);
            }
        }

        // ------------------------------------------------------------------ the land

        /** Dry, loaded, natural ground at this spot: not a roof, a wall top or a cliff edge. */
        private boolean ground(double x, double z) {
            int bx = Mth.floor(x), bz = Mth.floor(z);
            if (!level.hasChunk(bx >> 4, bz >> 4)) return false;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx, bz);
            if (y <= level.getMinBuildHeight() + 1) return false;
            BlockPos below = new BlockPos(bx, y - 1, bz);
            if (!level.getFluidState(below).isEmpty()
                    || level.getFluidState(below.above()).getType().isSame(net.minecraft.world.level.material.Fluids.WATER)) {
                return false;
            }
            BlockState s = level.getBlockState(below);
            if (!naturalGround(s)) {
                notNatural++;
                return false;
            }
            // A roof or a wall top stands above its surroundings; open ground does not.
            for (int k = 0; k < 4; k++) {
                int ox = bx + (k == 0 ? 3 : k == 1 ? -3 : 0), oz = bz + (k == 2 ? 3 : k == 3 ? -3 : 0);
                if (!level.hasChunk(ox >> 4, oz >> 4)) return false;
                int h = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, ox, oz);
                if (Math.abs(h - y) > 3) return false;
            }
            return true;
        }

        private double height(double x, double z) {
            return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
        }
    }

    /** Names of the kinds of mob that can never appear, for the settings page and checks. */
    public static Set<String> neverIds() {
        return new HashSet<>(NEVER);
    }
}
