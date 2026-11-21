package khi.pentecost.game;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.install.Http;
import khi.pentecost.install.ModrinthPick;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

final class OptionalMods {
    static final String EMBEDDIUM = "embeddium";
    static final String OCULUS = "oculus";

    enum State { IDLE, WORKING, DONE, FAILED }

    record Job(String name, State state, String message) {}

    private static final Map<String, Job> JOBS = new ConcurrentHashMap<>();

    private OptionalMods() {}

    static Job job(String slug) {
        return JOBS.getOrDefault(slug, new Job(slug, State.IDLE, ""));
    }

    static String status(String slug, boolean loaded) {
        if (loaded) {
            return "Installed";
        }
        Job j = job(slug);
        return switch (j.state()) {
            case WORKING -> "Downloading";
            case DONE -> j.message();
            case FAILED -> j.message();
            default -> "Not installed";
        };
    }

    static boolean busy() {
        return JOBS.values().stream().anyMatch(j -> j.state() == State.WORKING);
    }

    static void install(String slug, String display) {
        if (busy() || !(EMBEDDIUM.equals(slug) || OCULUS.equals(slug))) {
            return;
        }
        JOBS.put(slug, new Job(slug, State.WORKING, ""));
        Thread t = new Thread(() -> {
            try {
                Path mods = Dirs.mods();
                installOne(slug, mods, SharedConstants.getCurrentVersion().getName(), new HashSet<>(), 0);
                JOBS.put(slug, new Job(slug, State.DONE, "Installed. Restart to use it"));
                toast(display + " is installed", "Restart Pentecost to use it.");
            } catch (IOException | RuntimeException e) {
                Log.error("Could not install " + display + ": " + e.getMessage());
                String why = e.getMessage() == null ? "No reason given" : e.getMessage();
                JOBS.put(slug, new Job(slug, State.FAILED, "Failed: " + why));
                toast("Could not install " + display, why);
            }
        }, "pentecost-install-" + slug);
        t.setDaemon(true);
        t.start();
    }

    private static void installOne(String project, Path mods, String minecraft, Set<String> seen, int depth) throws IOException {
        if (!seen.add(project) || depth > 3) {
            return;
        }
        String url = "https://api.modrinth.com/v2/project/" + project + "/version?game_versions=%5B%22" + minecraft
                + "%22%5D&loaders=%5B%22forge%22%5D";
        ModrinthPick pick = ModrinthPick.of(Http.text(url));
        if (!alreadyThere(mods, pick.filename())) {
            Http.download(pick.url(), mods.resolve(pick.filename()), pick.sha1());
            Log.info("Installed " + pick.filename() + " from Modrinth");
        }
        for (String dep : pick.required()) {
            installOne(dep, mods, minecraft, seen, depth + 1);
        }
    }

    static boolean alreadyThere(Path mods, String filename) throws IOException {
        String base = ModrinthPick.baseName(filename);
        try (Stream<Path> s = Files.list(mods)) {
            return s.map(p -> p.getFileName().toString())
                    .filter(n -> n.toLowerCase(java.util.Locale.ROOT).endsWith(".jar"))
                    .anyMatch(n -> ModrinthPick.baseName(n).equals(base));
        }
    }

    private static void toast(String title, String text) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> khi.pentecost.game.PentecostToast.show(Component.literal(title), Component.literal(text)));
    }
}
