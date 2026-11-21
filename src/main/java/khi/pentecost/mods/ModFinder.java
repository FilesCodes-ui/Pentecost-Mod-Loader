package khi.pentecost.mods;

import khi.pentecost.boot.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ModFinder {
    private static final Optional<ModInfo> MISSING = Optional.empty();
    private static final Map<String, Optional<ModInfo>> CACHE = new ConcurrentHashMap<>();
    private static volatile List<ModInfo> cachedFor;

    private ModFinder() {}

    public static Optional<ModInfo> find(List<ModInfo> mods, String id, boolean skipLibraries) {
        if (id == null || id.isEmpty()) {
            return MISSING;
        }
        if (cachedFor != mods) {
            CACHE.clear();
            cachedFor = mods;
        }
        String key = (skipLibraries ? "L|" : "A|") + id;
        Optional<ModInfo> hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        Optional<ModInfo> found = search(mods, id, skipLibraries);
        CACHE.put(key, found);
        return found;
    }

    static Optional<ModInfo> search(List<ModInfo> mods, String id, boolean skipLibraries) {
        List<ModInfo> pool = new ArrayList<>();
        for (ModInfo m : mods) {
            if (!(skipLibraries && m.loader() == LoaderKind.LIBRARY)) {
                pool.add(m);
            }
        }
        for (ModInfo m : pool) {
            if (m.id().equals(id)) {
                return Optional.of(m);
            }
        }
        for (ModInfo m : pool) {
            if (provides(m, id)) {
                return Optional.of(m);
            }
        }
        String want = even(id);

        if (want.length() < 3 || java.util.Set.of("api", "lib", "core", "config", "common", "base", "util", "utils").contains(want)) {
            return MISSING;
        }
        ModInfo byId = only(pool, m -> even(m.id()).equals(want));
        if (byId != null) {
            Log.info("Mod finder: \"" + id + "\" means " + byId.id() + " (" + byId.loader().label + "), same id spelled differently.");
            return Optional.of(byId);
        }
        ModInfo byName = only(pool, m -> m.name() != null && even(m.name()).equals(want));
        if (byName != null) {
            Log.info("Mod finder: \"" + id + "\" means " + byName.id() + " (" + byName.loader().label + "), same mod name.");
            return Optional.of(byName);
        }
        ModInfo byWords = byWords(pool, id);
        if (byWords != null) {
            Log.info("Mod finder: \"" + id + "\" means " + byWords.id() + " (" + byWords.loader().label + "), same name words.");
            return Optional.of(byWords);
        }
        return MISSING;
    }

    static ModInfo byWords(List<ModInfo> pool, String id) {
        List<String> want = words(id);
        if (want.isEmpty() || (want.size() == 1 && COMMON.contains(want.get(0)))) {
            return null;
        }
        ModInfo best = null;
        int bestExtra = Integer.MAX_VALUE;
        boolean tie = false;
        int matches = 0;
        for (ModInfo m : pool) {
            int extra = Integer.MAX_VALUE;
            for (String s : new String[]{m.id(), m.name()}) {
                if (s == null) {
                    continue;
                }
                List<String> have = words(s);
                if (have.containsAll(want)) {
                    extra = Math.min(extra, have.size() - want.size());
                }
            }
            if (extra == Integer.MAX_VALUE) {
                continue;
            }
            matches++;
            if (extra < bestExtra) {
                best = m;
                bestExtra = extra;
                tie = false;
            } else if (extra == bestExtra && !best.id().equals(m.id())) {
                tie = true;
            }
        }

        if (tie || (matches > 1 && bestExtra > 0)) {
            return null;
        }
        return best;
    }

    private static final java.util.Set<String> COMMON = java.util.Set.of("api", "lib", "core", "config", "common", "base",
            "util", "utils");

    static List<String> words(String s) {
        List<String> out = new ArrayList<>();
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!w.isEmpty() && !w.equals("fabric") && !w.equals("forge") && !w.equals("quilt") && !w.equals("neoforge")
                    && !w.equals("mod") && !w.equals("port") && !out.contains(w)) {
                out.add(w);
            }
        }
        return out;
    }

    private static boolean provides(ModInfo m, String id) {
        if (m.raw() != null && m.raw().has("provides") && m.raw().get("provides").isJsonArray()) {
            for (com.google.gson.JsonElement e : m.raw().getAsJsonArray("provides")) {
                if (e.isJsonPrimitive() && e.getAsString().equals(id)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ModInfo only(List<ModInfo> pool, java.util.function.Predicate<ModInfo> test) {
        ModInfo one = null;
        for (ModInfo m : pool) {
            if (test.test(m)) {
                if (one != null && !one.id().equals(m.id())) {
                    return null;
                }
                one = m;
            }
        }
        return one;
    }

    static String even(String s) {
        String t = s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        StringBuilder b = new StringBuilder();
        for (String w : t.split(" ")) {
            if (w.isEmpty() || w.equals("fabric") || w.equals("forge") || w.equals("quilt") || w.equals("neoforge")
                    || w.equals("mod") || w.equals("port")) {
                continue;
            }
            b.append(w);
        }
        return b.toString();
    }
}
