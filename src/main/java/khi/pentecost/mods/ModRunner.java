package khi.pentecost.mods;

import khi.pentecost.boot.Log;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Checks dependencies and starts every mod. One failing mod never stops the others. */
public final class ModRunner {
    /** Ids the loader itself answers for. Library mods such as Fabric API stay normal jars. */
    private static final Set<String> BUILT_IN =
            Set.of("minecraft", "java", "forge", "fabricloader", "fabric-loader", "quilt_loader", "pentecost");

    private final ClassLoader loader;
    private final List<ModInfo> mods;
    private final Map<String, Throwable> failures = new LinkedHashMap<>();
    private final Map<String, List<String>> missing = new LinkedHashMap<>();
    private final List<String> started = new ArrayList<>();

    public ModRunner(ClassLoader loader, List<ModInfo> mods) {
        this.loader = loader;
        this.mods = mods;
    }

    /** Records mods whose required dependencies are absent. Those mods do not start. */
    public void checkDependencies() {
        Set<String> present = new java.util.HashSet<>(BUILT_IN);
        for (ModInfo m : mods) {
            present.add(m.id());
        }
        for (ModInfo m : mods) {
            List<String> gone = m.depends().stream().filter(d -> !present.contains(d)).toList();
            if (!gone.isEmpty()) {
                missing.put(m.id(), gone);
                Log.error(m.name() + " needs " + String.join(", ", gone)
                        + ". Install the missing mod from Modrinth, then start again.");
            }
        }
    }

    public void runAll(boolean client) {
        for (ModInfo m : mods) {
            if (missing.containsKey(m.id())) {
                continue;
            }
            try {
                run(m, client);
                started.add(m.id());
            } catch (Throwable t) {
                Throwable cause = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                failures.put(m.id(), cause);
                Log.error(m.name() + " failed to start and was disabled: " + cause);
            }
        }
    }

    private void run(ModInfo m, boolean client) throws Exception {
        switch (m.loader()) {
            case FORGE -> {
                for (String cls : m.mainEntrypoints()) {
                    construct(cls);
                }
            }
            case FABRIC, QUILT -> {
                for (String cls : m.mainEntrypoints()) {
                    Object o = construct(cls);
                    if (o instanceof ModInitializer init) {
                        init.onInitialize();
                    }
                }
                if (client) {
                    for (String cls : m.clientEntrypoints()) {
                        Object o = construct(cls);
                        if (o instanceof ClientModInitializer init) {
                            init.onInitializeClient();
                        }
                    }
                }
            }
        }
    }

    private Object construct(String className) throws Exception {
        // Fabric allows "pkg.Class::member". Only the class part is supported so far.
        int sep = className.indexOf("::");
        String name = sep < 0 ? className : className.substring(0, sep);
        Class<?> c = Class.forName(name, true, loader);
        var ctor = c.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    public Map<String, Throwable> failures() { return failures; }
    public Map<String, List<String>> missing() { return missing; }
    public List<String> started() { return started; }
}
