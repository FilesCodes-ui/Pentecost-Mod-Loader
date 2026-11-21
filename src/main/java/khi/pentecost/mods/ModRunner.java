package khi.pentecost.mods;

import khi.pentecost.adapter.fabric.FabricLoaderImpl;
import khi.pentecost.boot.Log;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ModRunner {
    private static final Set<String> BUILT_IN = Set.of("minecraft", "java", "forge", "fabricloader",
            "fabric-loader", "quilt_loader", "pentecost", "mixinextras");

    private final ClassLoader loader;
    private final List<ModInfo> mods;
    private final Map<String, Throwable> failures = new LinkedHashMap<>();
    private final Map<String, List<String>> missing = new LinkedHashMap<>();
    private final List<String> started = new ArrayList<>();

    private final Set<String> waiting = new java.util.LinkedHashSet<>();

    public ModRunner(ClassLoader loader, List<ModInfo> mods) {
        this.loader = loader;
        this.mods = mods;
    }

    public void checkDependencies() {
        Set<String> present = new java.util.HashSet<>(BUILT_IN);
        for (ModInfo m : mods) {
            present.add(m.id());
            if (m.raw() != null && m.raw().has("provides") && m.raw().get("provides").isJsonArray()) {
                m.raw().getAsJsonArray("provides").forEach(e -> present.add(e.getAsString()));
            }
        }
        for (ModInfo m : mods) {
            List<String> gone = m.depends().stream().filter(d -> !ModDiscovery.met(d, present)).toList();
            if (!gone.isEmpty()) {
                missing.put(m.id(), gone);
                Log.error(m.name() + " needs " + String.join(", ", gone)
                        + ". Install the missing mod from Modrinth, then start again.");
            }
        }
    }

    public void runPreLaunch() {
        for (ModInfo m : mods) {
            if (missing.containsKey(m.id()) || m.loader() == LoaderKind.FORGE) {
                continue;
            }
            for (String value : m.entrypoints("preLaunch")) {
                try {
                    PreLaunchEntrypoint e = FabricLoaderImpl.INSTANCE.create(value, PreLaunchEntrypoint.class);
                    if (e != null) {
                        e.onPreLaunch();
                    }
                } catch (Throwable t) {
                    fail(m, t);
                }
            }
        }
    }

    public void runAll(boolean client) {
        for (ModInfo m : mods) {
            if (missing.containsKey(m.id()) || failures.containsKey(m.id()) || m.loader() == LoaderKind.LIBRARY) {
                continue;
            }
            if (m.loader() == LoaderKind.FORGE && khi.pentecost.core.PentecostRuntime.forgeLayer) {
                continue;
            }
            if (khi.pentecost.core.PentecostRuntime.forgeCovers.contains(m.id())) {
                continue;
            }
            try {
                boolean later = client && waitsForForge(m);
                if (later) {
                    waiting.add(m.id());
                }
                run(m, false);
                started.add(m.id());
            } catch (Throwable t) {
                fail(m, t);
            }
        }

        if (client) {
            for (ModInfo m : mods) {
                if (!started.contains(m.id()) || failures.containsKey(m.id()) || waiting.contains(m.id())
                        || (m.loader() != LoaderKind.FABRIC && m.loader() != LoaderKind.QUILT)) {
                    continue;
                }
                try {
                    for (String value : m.entrypoints("client")) {
                        ClientModInitializer init = FabricLoaderImpl.INSTANCE.create(value, ClientModInitializer.class);
                        if (init != null) {
                            init.onInitializeClient();
                        }
                    }
                } catch (Throwable t) {
                    started.remove(m.id());
                    fail(m, t);
                }
            }
        }
        if (!waiting.isEmpty()) {
            Log.info("Client side of " + String.join(", ", waiting)
                    + " starts inside Forge's client setup: it works with a Forge mod");
        }
        Log.info("Mods started: " + started.size() + ", failed: " + failures.size()
                + ", missing dependencies: " + missing.size());
    }

    private static boolean plumbing(String id) {
        return id.startsWith("fabric-") || id.startsWith("fabric_") || id.equals("mixinextras");
    }

    private boolean waitsForForge(ModInfo m) {
        if (!khi.pentecost.core.PentecostRuntime.forgeLayer || m.entrypoints("client").isEmpty()
                || (m.loader() != LoaderKind.FABRIC && m.loader() != LoaderKind.QUILT)) {
            return false;
        }
        Set<String> named = new java.util.HashSet<>(m.depends());
        if (m.raw() != null) {
            for (String key : new String[]{"depends", "recommends", "suggests", "breaks", "conflicts"}) {
                if (m.raw().has(key) && m.raw().get(key).isJsonObject()) {
                    named.addAll(m.raw().getAsJsonObject(key).keySet());
                }
            }
        }
        for (ModInfo other : mods) {
            if (other.loader() == LoaderKind.FORGE && !plumbing(other.id()) && named.contains(other.id())) {
                return true;
            }
        }
        return false;
    }

    public void runWaitingClient() {
        for (ModInfo m : mods) {
            if (!waiting.remove(m.id()) || failures.containsKey(m.id())) {
                continue;
            }
            try {
                for (String value : m.entrypoints("client")) {
                    ClientModInitializer init = FabricLoaderImpl.INSTANCE.create(value, ClientModInitializer.class);
                    if (init != null) {
                        init.onInitializeClient();
                    }
                }
                Log.info("Client side of " + m.name() + " started");
            } catch (Throwable t) {
                fail(m, t);
            }
        }
    }

    private void fail(ModInfo m, Throwable t) {
        Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
        failures.put(m.id(), cause);
        khi.pentecost.core.Hooks.markFailed(m.id());
        Log.error(m.name() + " failed to start and was disabled: " + cause);
        StackTraceElement[] st = cause.getStackTrace();
        for (int i = 0; i < Math.min(6, st.length); i++) {
            Log.error("    at " + st[i]);
        }

        for (int i = 6; i < st.length; i++) {
            String c = st[i].getClassName();
            if (!c.startsWith("net.minecraft") && !c.startsWith("com.mojang") && !c.startsWith("java.")
                    && !c.startsWith("jdk.") && !c.startsWith("net.fabricmc") && !c.startsWith("khi.pentecost")
                    && !c.startsWith("io.github.fabricators_of_create")) {
                Log.error("    ... from mod code at " + st[i]);
                break;
            }
        }

        int depth = 0;
        for (Throwable c = cause.getCause(); c != null && c != cause && depth < 5; c = c.getCause(), depth++) {
            Log.error("  Caused by: " + c);
            StackTraceElement[] cs = c.getStackTrace();
            for (int i = 0; i < Math.min(5, cs.length); i++) {
                Log.error("    at " + cs[i]);
            }
        }
    }

    private void run(ModInfo m, boolean client) throws Exception {
        switch (m.loader()) {
            case FORGE -> {
                for (String cls : m.entrypoints("main")) {
                    Class<?> c = Class.forName(cls, true, loader);
                    var ctor = c.getDeclaredConstructor();
                    ctor.setAccessible(true);
                    ctor.newInstance();
                }
            }
            case FABRIC, QUILT -> {
                for (String value : m.entrypoints("main")) {
                    ModInitializer init = FabricLoaderImpl.INSTANCE.create(value, ModInitializer.class);
                    if (init != null) {
                        init.onInitialize();
                    }
                }
                if (client) {
                    for (String value : m.entrypoints("client")) {
                        ClientModInitializer init = FabricLoaderImpl.INSTANCE.create(value, ClientModInitializer.class);
                        if (init != null) {
                            init.onInitializeClient();
                        }
                    }
                }
            }
            default -> { }
        }
    }

    public Map<String, Throwable> failures() { return failures; }
    public Map<String, List<String>> missing() { return missing; }
    public List<String> started() { return started; }
}
