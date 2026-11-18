package khi.pentecost.mods;

import khi.pentecost.boot.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Step 3 of mod threads: how a mod from one loader finds a mod from another.
 *
 * Fabric and Forge mods often name the same mod differently: "cloth-config" and "cloth_config",
 * "jei-fabric" and "jei", "create" and "create_fabric". A Fabric mod asking for a Forge mod by its Fabric name
 * used to get "not loaded" and turn its support off. The finder tries, in order, and stops at the first sure answer:
 * 1. the exact id,
 * 2. an id another mod says it provides,
 * 3. the same id once spelling is evened out (case, - and _, loader words like fabric or forge removed),
 * 4. the same display name, evened out the same way.
 * Steps 3 and 4 only answer when exactly one mod matches, so a guess can never pick between two mods.
 * Every match made by 3 or 4 is written to the log once, so a wrong one is easy to spot.
 */
public final class ModFinder {
    private static final Optional<ModInfo> MISSING = Optional.empty();
    private static final Map<String, Optional<ModInfo>> CACHE = new ConcurrentHashMap<>();
    private static volatile List<ModInfo> cachedFor;

    private ModFinder() {}

    /** Finds the mod a lookup means, across loaders. Libraries are skipped when skipLibraries is set. */
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
        // Words too common to stand for one mod: "fabric-api" evens to "api" and must never match a mod called API.
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

    /**
     * 5. Parts of the name, as whole words: the mod whose id or name holds every word asked for. When only one mod
     * does, it is the answer. When several do, only one with exactly those words counts. Never a guess.
     */
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
        // With several mods holding the words, only an exact word match is sure. "xaero" fits both Xaero mods: no answer.
        if (tie || (matches > 1 && bestExtra > 0)) {
            return null;
        }
        return best;
    }

    private static final java.util.Set<String> COMMON = java.util.Set.of("api", "lib", "core", "config", "common", "base",
            "util", "utils");

    /** The words of an id or name: lower case, split at anything not a letter or digit, loader words removed. */
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

    /** An id or name with spelling evened out: lower case, letters and digits only, loader words removed. */
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
