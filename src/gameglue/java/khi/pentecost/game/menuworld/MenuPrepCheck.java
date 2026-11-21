package khi.pentecost.game.menuworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.game.menuworld.profile.MenuProfile;
import khi.pentecost.game.menuworld.profile.Poi;
import khi.pentecost.game.menuworld.profile.ProfileStore;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class MenuPrepCheck {
    private static int failures;

    private static void check(String what, boolean ok, String detail) {
        System.out.println((ok ? "PASS " : "FAIL ") + what + (detail.isEmpty() ? "" : " (" + detail + ")"));
        if (!ok) failures++;
    }

    public static void main(String[] args) throws Exception {
        Path cache = Path.of(args[0]);
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        Path config = cache.getParent().getParent();
        Path home = Path.of(System.getProperty("user.dir"));
        JsonObject meta = JsonParser.parseString(Files.readString(cache.resolve("meta.json"))).getAsJsonObject();

        MenuProfile p = new MenuProfile();
        p.seed = Long.parseLong(cache.getFileName().toString());
        p.centerMode = MenuProfile.CenterMode.COORDS;
        p.centerX = meta.get("centerBlockX").getAsInt();
        p.centerZ = meta.get("centerBlockZ").getAsInt();
        p.radiusChunks = meta.get("radiusChunks").getAsInt();
        List<String> steps = new ArrayList<>();
        MenuPrep.Ready first = MenuPrep.prepare(home, config, p, false, s -> steps.add(s.phase().name()));
        check("a cached seed is ready without generating", first.terrain().chunkCount() == 4 * p.radiusChunks * p.radiusChunks
                && !steps.contains("GENERATING"), first.terrain().chunkCount() + " chunks, steps " + steps);
        check("the first start scans for places", first.scanned() != null && !first.scanned().isEmpty(),
                first.scanned() == null ? "no scan" : first.scanned().size() + " places");
        check("the centre and spawn come from the cache", first.centerX() == p.centerX && first.centerZ() == p.centerZ
                && first.spawnX() == (meta.has("spawnX") ? meta.get("spawnX").getAsInt() : p.centerX), first.spawnX() + "," + first.spawnZ());
        check("the land can be read for the camera", first.terrain().heightAt(first.centerX(), first.centerZ()) > -64, "");

        p.scanKey = first.scanKey();
        MenuPrep.Ready again = MenuPrep.prepare(home, config, p, false, s -> { });
        check("an unchanged world is not scanned again", again.scanned() == null, "");
        MenuPrep.Ready forced = MenuPrep.prepare(home, config, p, true, s -> { });
        check("a rescan can be asked for", forced.scanned() != null && forced.scanned().size() == first.scanned().size(), "");

        MenuProfile fresh = new MenuProfile();
        fresh.seed = 8675309L;
        Path wouldBe = config.resolve("menu_world").resolve("8675309");
        boolean asked = false;
        try {
            MenuPrep.prepare(home, config, fresh, false, s -> { });
        } catch (MenuPrep.NeedsEula e) {
            asked = true;
        }
        check("an uncached seed asks about the EULA first", asked && !Files.exists(wouldBe), "");

        Path cherry = cache.getParent().resolve("-769185976586290351");
        if (Files.isRegularFile(cherry.resolve("level.dat"))) {
            MenuProfile save = MenuProfile.forSave(cherry.toString());
            MenuPrep.Ready r = MenuPrep.prepare(home, config, save, false, s -> { });
            check("a save is read in place and centred on its spawn", r.centerX() == r.spawnX() && r.centerZ() == r.spawnZ()
                    && r.terrain().chunkCount() > 0, r.terrain().chunkCount() + " chunks, spawn " + r.spawnX() + "," + r.spawnZ());
            check("a save is scanned the first time", r.scanned() != null, "");
        }
        MenuProfile broken = MenuProfile.forSave(config.resolve("menu_world").resolve("no-such-world").toString());
        String reason = null;
        try {
            MenuPrep.prepare(home, config, broken, false, s -> { });
        } catch (MenuPrep.Failed e) {
            reason = e.getMessage();
        }
        check("a world that is not there gives a plain reason", reason != null && !reason.isBlank(), String.valueOf(reason));
        MenuProfile none = MenuProfile.forSave("");
        reason = null;
        try {
            MenuPrep.prepare(home, config, none, false, s -> { });
        } catch (MenuPrep.Failed e) {
            reason = e.getMessage();
        }
        check("no world picked gives a plain reason", reason != null && reason.contains("picked"), String.valueOf(reason));

        MenuProfile m = new MenuProfile();
        m.addCustom("home", 10, 70, 10, 24);
        m.switchPointsTo("seed:1");
        check("a profile from before adopts its places for the first world", m.points.size() == 1 && m.pointsSource.equals("seed:1"), "");
        m.switchPointsTo("seed:2");
        check("another world starts with no places and keeps the old ones aside", m.points.isEmpty() && m.stashed.get("seed:1").size() == 1, "");
        m.addCustom("lake", 50, 64, 50, 24);
        m.switchPointsTo("seed:1");
        check("going back brings the old places back", m.points.size() == 1 && m.points.get(0).name.equals("home")
                && m.stashed.get("seed:2").size() == 1, "");
        Path file = Files.createTempFile(Path.of(args[1]), "profile", ".json");
        ProfileStore.save(file, m);
        MenuProfile loaded = ProfileStore.load(file);
        check("the stashed places survive saving", loaded.stashed.get("seed:2") != null && loaded.stashed.get("seed:2").get(0).name.equals("lake")
                && loaded.pointsSource.equals("seed:1"), "");
        Poi pp = loaded.points.get(0);
        check("a saved profile reloads the same", pp.name.equals("home") && loaded.version == 3, "");

        Files.writeString(file, "{\"handheld\":9,\"clearance\":-4,\"fadeSeconds\":0,\"fovOffset\":500,\"secondsPerDay\":1,"
                + "\"startTicks\":-1000,\"viewChunks\":999,\"timeMode\":\"SOON\",\"moves\":[null,\"\",\"orbit\"]}");
        MenuProfile wild = ProfileStore.load(file);
        check("out of range settings are clamped", wild.handheld == 2 && wild.clearance == 3 && wild.fadeSeconds == 0.2
                && wild.fovOffset == 30 && wild.secondsPerDay == 30 && wild.startTicks == 23000 && wild.viewChunks == 24
                && wild.timeMode == MenuProfile.TimeMode.CYCLE && wild.moves.equals(List.of("orbit")),
                wild.handheld + " " + wild.clearance + " " + wild.startTicks + " " + wild.viewChunks + " " + wild.timeMode + " " + wild.moves);

        System.out.println(failures == 0 ? "ALL PASSED" : failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
