package khi.pentecost.game;

import khi.pentecost.control.Settings;
import khi.pentecost.core.ClientHooks;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which sounds the game may start.
 * Three limits: muted mods, muted sounds, and a rate (the same sound no closer than a gap, all sounds no more than a count per second).
 * Music, records and voice are never rate limited. The gate also counts what plays, so the Sound page can list it.
 */
final class SoundControl implements ClientHooks.Gate {
    private static final Map<String, int[]> MODS = new ConcurrentHashMap<>();
    private static final Map<String, int[]> SOUNDS = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final Set<String> NEVER_LIMITED = Set.of("music", "record", "voice");

    private static volatile Set<String> mutedMods = Set.of();
    private static volatile Set<String> mutedSounds = Set.of();
    private static volatile int gap;
    private static volatile int perSecond;
    private static long refreshed;
    private static long windowStart;
    private static int windowCount;
    private static int dropped;

    private SoundControl() {}

    static void install() {
        ClientHooks.gate = new SoundControl();
    }

    private static Set<String> parse(String list) {
        Set<String> out = new HashSet<>();
        for (String s : list.split(",")) {
            if (!s.isBlank()) {
                out.add(s.trim());
            }
        }
        return out;
    }

    private static void refresh(long now) {
        if (now - refreshed < 500) {
            return;
        }
        refreshed = now;
        mutedMods = parse(Settings.get(Settings.SOUND_MUTED_MODS));
        mutedSounds = parse(Settings.get(Settings.SOUND_MUTED));
        gap = Settings.num(Settings.SOUND_GAP, 0);
        perSecond = Settings.num(Settings.SOUND_LIMIT, 0);
    }

    @Override
    public boolean allow(Object o) {
        if (!(o instanceof SoundInstance s)) {
            return true;
        }
        long now = System.currentTimeMillis();
        refresh(now);
        String id = s.getLocation().toString();
        String mod = s.getLocation().getNamespace();
        MODS.computeIfAbsent(mod, k -> new int[1])[0]++;
        SOUNDS.computeIfAbsent(id, k -> new int[1])[0]++;
        if (mutedMods.contains(mod) || mutedSounds.contains(id)) {
            dropped++;
            return false;
        }
        SoundSource source = s.getSource();
        if (source != null && NEVER_LIMITED.contains(source.getName())) {
            return true;
        }
        if (gap > 0) {
            Long last = LAST.put(id, now);
            if (last != null && now - last < gap) {
                dropped++;
                return false;
            }
        }
        if (perSecond > 0) {
            synchronized (SoundControl.class) {
                if (now - windowStart >= 1000) {
                    windowStart = now;
                    windowCount = 0;
                }
                if (++windowCount > perSecond) {
                    dropped++;
                    return false;
                }
            }
        }
        return true;
    }

    // ---- what the Sound page reads

    record Seen(String name, int count) {}

    private static List<Seen> top(Map<String, int[]> map, int n) {
        List<Seen> out = new ArrayList<>();
        map.forEach((k, v) -> out.add(new Seen(k, v[0])));
        out.sort((a, b) -> Integer.compare(b.count(), a.count()));
        return out.size() > n ? new ArrayList<>(out.subList(0, n)) : out;
    }

    static List<Seen> mods() {
        return top(MODS, 60);
    }

    static List<Seen> sounds() {
        return top(SOUNDS, 40);
    }

    static int dropped() {
        return dropped;
    }

    static void clearCounts() {
        MODS.clear();
        SOUNDS.clear();
        dropped = 0;
    }

    static boolean modMuted(String mod) {
        return parse(Settings.get(Settings.SOUND_MUTED_MODS)).contains(mod);
    }

    static boolean soundMuted(String id) {
        return parse(Settings.get(Settings.SOUND_MUTED)).contains(id);
    }

    private static void flip(String key, String item) {
        Set<String> set = parse(Settings.get(key));
        if (!set.remove(item)) {
            set.add(item);
        }
        Settings.set(key, String.join(",", set));
    }

    static void toggleMod(String mod) {
        flip(Settings.SOUND_MUTED_MODS, mod);
        refreshed = 0;
    }

    static void toggleSound(String id) {
        flip(Settings.SOUND_MUTED, id);
        refreshed = 0;
    }
}
