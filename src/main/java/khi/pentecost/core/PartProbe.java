package khi.pentecost.core;

import khi.pentecost.loader.ClassTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.LongAdder;

public final class PartProbe {
    public static final int WORLD = 0;
    public static final int CULL = 1;
    public static final int UPLOAD = 2;
    public static final int TERRAIN = 3;
    public static final int ENTITIES = 4;
    public static final int BLOCK_ENTITIES = 5;
    public static final int PARTICLES = 6;
    public static final int SKY = 7;
    public static final int SHADOW = 8;
    public static final int HUD = 9;
    public static final int CLIENT_TICK = 10;
    public static final int RENDER = 11;
    public static final int TICK_ENTITIES = 12;
    public static final int TICK_AMBIENT = 13;
    public static final int TICK_BLOCK_ENTITIES = 14;
    public static final int TICK_PARTICLES = 15;
    public static final int FRAME_PARTS = 16;
    public static final int NOISE = 16;
    public static final int SURFACE = 17;
    public static final int CAVES = 18;
    public static final int FEATURES = 19;
    public static final int LIGHT = 20;
    public static final int FINISHED = 21;
    public static final int CHUNK_TASKS = 22;
    public static final int SERVER_TICK = 23;
    public static final int PARTS = 24;

    public static volatile boolean on;
    private static volatile Thread renderThread;

    private static final int[] depth = new int[FRAME_PARTS];
    private static final long[] start = new long[FRAME_PARTS];
    private static final long[] sum = new long[FRAME_PARTS];
    private static final long[] thisFrame = new long[FRAME_PARTS];
    private static int frames;

    private static final LongAdder[] genNanos = new LongAdder[PARTS];
    private static final LongAdder[] genCount = new LongAdder[PARTS];
    private static final ThreadLocal<long[]> genState = ThreadLocal.withInitial(() -> new long[PARTS * 2]);

    private static long windowStart;
    private static final double[] frameMs = new double[FRAME_PARTS];
    private static final double[] perSecond = new double[PARTS];
    private static final double[] msEach = new double[PARTS];

    static {
        for (int i = FRAME_PARTS; i < PARTS; i++) {
            genNanos[i] = new LongAdder();
            genCount[i] = new LongAdder();
        }
    }

    private PartProbe() {}

    public static void enter(int part) {
        if (!on) {
            return;
        }
        if (part < FRAME_PARTS) {
            if (Thread.currentThread() != renderThread) {
                return;
            }
            if (part != SHADOW && depth[SHADOW] > 0) {
                return;
            }
            if (depth[part]++ == 0) {
                start[part] = System.nanoTime();
            }
            return;
        }
        long[] s = genState.get();
        if (s[part * 2]++ == 0) {
            s[part * 2 + 1] = System.nanoTime();
        }
    }

    public static void exit(int part) {
        if (part < FRAME_PARTS) {
            if (!on || Thread.currentThread() != renderThread || depth[part] == 0) {
                return;
            }
            if (--depth[part] == 0) {
                long took = System.nanoTime() - start[part];
                sum[part] += took;
                thisFrame[part] += took;
            }
            return;
        }
        long[] s = genState.get();
        if (s[part * 2] == 0) {
            return;
        }
        if (--s[part * 2] == 0 && on) {
            genNanos[part].add(System.nanoTime() - s[part * 2 + 1]);
            genCount[part].increment();
        }
    }

    public static void frame(boolean shown) {
        renderThread = Thread.currentThread();
        on = shown;
        long now = System.nanoTime();
        for (int i = 0; i < FRAME_PARTS; i++) {
            if (depth[i] > 0 && now - start[i] > 1_000_000_000L) {
                depth[i] = 0;
            }
        }
        frames++;
        if (windowStart == 0) {
            windowStart = now;
            return;
        }
        long span = now - windowStart;
        if (span < 500_000_000L) {
            return;
        }
        for (int i = 0; i < FRAME_PARTS; i++) {
            frameMs[i] = frames == 0 ? 0 : sum[i] / 1e6 / frames;
            sum[i] = 0;
        }
        for (int i = FRAME_PARTS; i < PARTS; i++) {
            long n = genCount[i].sumThenReset();
            long ns = genNanos[i].sumThenReset();
            perSecond[i] = n * 1e9 / span;
            msEach[i] = n == 0 ? 0 : ns / 1e6 / n;
        }
        frames = 0;
        windowStart = now;
    }

    public static void takeFrameParts(double[] out) {
        for (int i = 0; i < FRAME_PARTS && i < out.length; i++) {
            out[i] = thisFrame[i] / 1e6;
            thisFrame[i] = 0;
        }
    }

    public static double frameMs(int part) {
        return frameMs[part];
    }

    public static double perSecond(int part) {
        return perSecond[part];
    }

    public static double msEach(int part) {
        return msEach[part];
    }

    private record Target(String method, String descStart, int part) {}

    private static final Map<String, List<Target>> TARGETS = new HashMap<>();

    static {
        TARGETS.put("net.minecraft.client.renderer.LevelRenderer", List.of(
                new Target("renderLevel", null, WORLD),
                new Target("setupRender", null, CULL),
                new Target("compileChunks", null, UPLOAD),
                new Target("renderChunkLayer", null, TERRAIN),
                new Target("renderEntity", null, ENTITIES),
                new Target("renderSky", null, SKY),
                new Target("renderClouds", null, SKY),
                new Target("renderSnowAndRain", null, SKY)));
        TARGETS.put("net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher", List.of(
                new Target("render", null, BLOCK_ENTITIES)));
        TARGETS.put("net.minecraft.client.particle.ParticleEngine", List.of(
                new Target("render", null, PARTICLES),
                new Target("tick", "()V", TICK_PARTICLES)));
        TARGETS.put("net.minecraft.client.multiplayer.ClientLevel", List.of(
                new Target("tickEntities", "()V", TICK_ENTITIES),
                new Target("animateTick", "(III)V", TICK_AMBIENT)));
        TARGETS.put("net.minecraft.world.level.Level", List.of(
                new Target("tickBlockEntities", "()V", TICK_BLOCK_ENTITIES)));
        TARGETS.put("net.minecraft.client.gui.Gui", List.of(
                new Target("render", "(Lnet/minecraft/client/gui/GuiGraphics;F)", HUD)));
        TARGETS.put("net.minecraft.client.Minecraft", List.of(
                new Target("tick", "()V", CLIENT_TICK)));
        TARGETS.put("net.minecraft.client.renderer.GameRenderer", List.of(
                new Target("render", "(FJZ)V", RENDER)));
        TARGETS.put("net.minecraftforge.client.gui.overlay.ForgeGui", List.of(
                new Target("render", "(Lnet/minecraft/client/gui/GuiGraphics;F)", HUD)));
        TARGETS.put("net.irisshaders.iris.shadows.ShadowRenderer", List.of(
                new Target("renderShadows", null, SHADOW)));
        TARGETS.put("net.coderbot.iris.pipeline.ShadowRenderer", List.of(
                new Target("renderShadows", null, SHADOW)));
        TARGETS.put("net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator", List.of(
                new Target("doFill", null, NOISE),
                new Target("buildSurface", null, SURFACE),
                new Target("applyCarvers", null, CAVES)));
        TARGETS.put("net.minecraft.world.level.chunk.ChunkGenerator", List.of(
                new Target("applyBiomeDecoration", null, FEATURES)));
        TARGETS.put("net.minecraft.server.level.ThreadedLevelLightEngine", List.of(
                new Target("runUpdate", null, LIGHT)));
        TARGETS.put("net.minecraft.server.level.ChunkMap", List.of(
                new Target("protoChunkToFullChunk", null, FINISHED)));
        TARGETS.put("net.minecraft.server.level.ServerChunkCache", List.of(
                new Target("pollTask", "()Z", CHUNK_TASKS)));
        TARGETS.put("net.minecraft.server.MinecraftServer", List.of(
                new Target("tickServer", "(Ljava/util/function/BooleanSupplier;)V", SERVER_TICK)));
    }

    public static ClassTransformer transformer() {
        return (name, bytes) -> {
            List<Target> targets = TARGETS.get(name);
            if (targets == null || System.getenv("PENTECOST_NO_PART_PROBE") != null) {
                return bytes;
            }
            try {
                ClassReader cr = new ClassReader(bytes);
                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                int[] done = {0};
                cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
                    @Override
                    public MethodVisitor visitMethod(int access, String n, String d, String sig, String[] ex) {
                        MethodVisitor mv = super.visitMethod(access, n, d, sig, ex);
                        if ((access & Opcodes.ACC_ABSTRACT) != 0) {
                            return mv;
                        }
                        for (Target t : targets) {
                            if (t.method().equals(n) && (t.descStart() == null || d.startsWith(t.descStart()))) {
                                done[0]++;
                                return wrap(mv, t.part());
                            }
                        }
                        return mv;
                    }
                }, 0);
                if (done[0] == 0) {
                    return bytes;
                }
                return cw.toByteArray();
            } catch (RuntimeException e) {
                khi.pentecost.boot.Log.warn("F6 timers skipped " + name + ": " + e);
                return bytes;
            }
        };
    }

    private static MethodVisitor wrap(MethodVisitor mv, int part) {
        return new MethodVisitor(Opcodes.ASM9, mv) {
            @Override
            public void visitCode() {
                super.visitCode();
                call("enter");
            }

            @Override
            public void visitInsn(int opcode) {
                if (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) {
                    call("exit");
                }
                super.visitInsn(opcode);
            }

            private void call(String what) {
                super.visitIntInsn(Opcodes.BIPUSH, part);
                super.visitMethodInsn(Opcodes.INVOKESTATIC, "khi/pentecost/core/PartProbe", what, "(I)V", false);
            }
        };
    }
}
