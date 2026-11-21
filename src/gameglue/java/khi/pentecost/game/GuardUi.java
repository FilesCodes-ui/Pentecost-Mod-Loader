package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.boot.StartupGuard;
import net.minecraft.CrashReport;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.EditWorldScreen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.storage.LevelSummary;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class GuardUi {
    private static boolean checked;
    private static boolean testFired;

    private GuardUi() {}

    static void frame() {
        Minecraft mc = Minecraft.getInstance();
        if (!StartupGuard.menuReached()) {
            String test = System.getenv("PENTECOST_GUARD_TEST");
            if (test != null && !test.isBlank() && !testFired && !StartupGuard.offIds().contains(test.strip())) {
                testFired = true;
                Log.warn("Startup guard test: crashing on purpose as mod " + test.strip());
                mc.delayCrash(CrashReport.forThrowable(new IllegalStateException(
                        "Startup guard test: Test (" + test.strip() + ") encountered an error"), "Startup guard test"));
                return;
            }
            if (mc.level == null && (!(mc.screen instanceof TitleScreen) || mc.getOverlay() != null)) {
                return;
            }
            StartupGuard.markMenu();
        }
        if (checked || !(mc.screen instanceof TitleScreen) || mc.getOverlay() != null) {
            return;
        }
        checked = true;
        List<String[]> off = StartupGuard.offList();
        if (off.isEmpty()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        for (String[] row : off) {
            body.append(row[1]).append(" broke the game twice while it was starting, so Pentecost turned it off");
            body.append(row[2].isEmpty() ? "." : ", along with the mods that need it: " + row[2] + ".");
            body.append("\n");
        }
        body.append("\nWorlds that use these mods ask before they open, and get a backup first.");
        Screen back = mc.screen;
        mc.setScreen(new ConfirmScreen(keep -> {
            if (!keep) {
                StartupGuard.allowAll();
            }
            mc.setScreen(back);
        }, Component.literal("Pentecost turned off a broken mod"), Component.literal(body.toString()),
                Component.literal("OK"), Component.literal("Turn them back on next launch")));
    }

    static boolean protect(Screen back, LevelSummary s) {
        Set<String> off = StartupGuard.offIds();
        if (off.isEmpty()) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        String id = s.getLevelId();
        Set<String> used = worldMods(mc.getLevelSource().getBaseDir().resolve(id).resolve("level.dat"));
        Set<String> hit = new LinkedHashSet<>();
        if (used == null) {
            hit.addAll(off);
        } else {
            for (String m : off) {
                if (used.contains(m)) {
                    hit.add(m);
                }
            }
            if (hit.isEmpty()) {
                return false;
            }
        }
        String what = String.join(", ", hit);
        String body = (used == null
                ? "Pentecost cannot tell which mods this world used. It may use " + what + ", which Pentecost turned off."
                : "This world uses " + what + ", which Pentecost turned off.")
                + "\nOpening it now can delete those mods' blocks, items and machines for good."
                + "\nPentecost makes a backup first.";
        mc.setScreen(new ConfirmScreen(yes -> {
            if (yes) {
                EditWorldScreen.makeBackupAndShowToast(mc.getLevelSource(), id);
                Log.info("Startup guard: backed up " + id + " before opening it without " + what);
                mc.createWorldOpenFlows().loadLevel(back, id);
            } else {
                mc.setScreen(back);
            }
        }, Component.literal("This world needs a mod that is off"), Component.literal(body),
                Component.literal("Back up and open"), Component.literal("Cancel")));
        return true;
    }

    static Set<String> worldMods(Path levelDat) {
        try {
            if (!Files.isRegularFile(levelDat)) {
                return null;
            }
            CompoundTag root = NbtIo.readCompressed(levelDat.toFile());
            CompoundTag fml = root.contains("fml", 10) ? root.getCompound("fml")
                    : root.getCompound("Data").getCompound("fml");
            if (!fml.contains("LoadingModList", 9)) {
                return null;
            }
            ListTag list = fml.getList("LoadingModList", 10);
            Set<String> out = new LinkedHashSet<>();
            for (int i = 0; i < list.size(); i++) {
                out.add(list.getCompound(i).getString("ModId"));
            }
            return out;
        } catch (Exception e) {
            Log.warn("Could not read the mod list of " + levelDat + ": " + e);
            return null;
        }
    }
}
