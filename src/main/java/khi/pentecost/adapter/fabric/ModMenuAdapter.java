package khi.pentecost.adapter.fabric;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Forge puts a Mods button on the title screen and the pause menu. Mod Menu adds its own to both.
 * Pentecost routes every Mods button to one place, so the second button is noise.
 * This turns Mod Menu's extra buttons off once. A player who turns them back on keeps them.
 */
public final class ModMenuAdapter {
    private ModMenuAdapter() {}

    public static void oneModsButton() {
        Path marker = Dirs.mk(Dirs.config().resolve("pentecost")).resolve("modmenu-buttons.done");
        Path file = Dirs.config().resolve("modmenu.json");
        if (Files.exists(marker)) {
            return;
        }
        try {
            JsonObject cfg = Files.isRegularFile(file)
                    ? JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject()
                    : new JsonObject();
            cfg.addProperty("modify_title_screen", false);
            cfg.addProperty("modify_game_menu", false);
            Files.writeString(file, new GsonBuilder().setPrettyPrinting().create().toJson(cfg),
                    StandardCharsets.UTF_8);
            Files.writeString(marker, "Pentecost turned off Mod Menu's extra Mods buttons once.\n");
            Log.info("Mod Menu: extra Mods buttons off. The one Mods button opens the screen you choose");
        } catch (IOException | RuntimeException e) {
            Log.error("Cannot adjust Mod Menu's buttons: " + e);
        }
    }
}
