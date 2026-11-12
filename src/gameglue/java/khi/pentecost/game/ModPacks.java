package khi.pentecost.game;

import khi.pentecost.boot.Log;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.LoaderKind;
import khi.pentecost.mods.ModInfo;
import net.minecraft.SharedConstants;
import net.minecraft.client.resources.ClientPackSource;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.repository.RepositorySource;
import net.minecraft.world.flag.FeatureFlagSet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Every mod's files reach the game, whatever loader the mod was written for.
 *
 * On the Forge layer, Forge loads the files of Forge mods only. Fabric and Quilt mods would lose
 * their textures, translations, shaders and recipes. Pentecost adds one resource pack per such mod
 * to every pack list the game builds, for assets and for world data.
 */
public final class ModPacks {
    /** Jar to { has assets, has data }. Read once. */
    private static final Map<Path, boolean[]> CONTENTS = new HashMap<>();
    private static boolean reported;

    private ModPacks() {}

    /** Gets the pack sources the game is about to use and returns them with Pentecost's source added. */
    public static Object extend(Object sourceArray) {
        RepositorySource[] in = (RepositorySource[]) sourceArray;
        // Without the Forge layer, Fabric API loads these files itself.
        if (!PentecostRuntime.forgeLayer) {
            return in;
        }
        boolean client = false;
        for (RepositorySource s : in) {
            if (s instanceof ClientPackSource) {
                client = true;
            }
        }
        PackType type = client ? PackType.CLIENT_RESOURCES : PackType.SERVER_DATA;
        List<ModInfo> mods = modsWith(type);
        if (mods.isEmpty()) {
            return in;
        }
        if (!reported && client) {
            reported = true;
            Log.info("Resource packs added for " + mods.size() + " Fabric/Quilt mod jar(s)");
        }
        RepositorySource[] out = Arrays.copyOf(in, in.length + 1);
        out[in.length] = consumer -> {
            for (ModInfo m : mods) {
                if (type == PackType.SERVER_DATA && khi.pentecost.core.Hooks.modFailed(m.id())) {
                    Log.warn(m.name() + " failed to start, so its world data stays out");
                    continue;
                }
                consumer.accept(Pack.create("pentecost/" + m.id(), Component.literal(m.name()), true,
                        name -> new FilePackResources(name, m.jar().toFile(), true),
                        new Pack.Info(Component.literal("Files of the mod " + m.name()),
                                SharedConstants.getCurrentVersion().getPackVersion(type), FeatureFlagSet.of()),
                        type, Pack.Position.BOTTOM, false, PackSource.BUILT_IN));
            }
        };
        return out;
    }

    private static synchronized List<ModInfo> modsWith(PackType type) {
        List<ModInfo> out = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (ModInfo m : PentecostRuntime.mods) {
            if (m.loader() == LoaderKind.FORGE || !seen.add(m.jar())) {
                continue;
            }
            boolean[] has = CONTENTS.computeIfAbsent(m.jar(), ModPacks::scan);
            if (has[type == PackType.CLIENT_RESOURCES ? 0 : 1]) {
                out.add(m);
            }
        }
        return out;
    }

    private static boolean[] scan(Path jar) {
        boolean[] has = new boolean[2];
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements() && !(has[0] && has[1])) {
                String n = en.nextElement().getName();
                if (n.startsWith("assets/")) {
                    has[0] = true;
                } else if (n.startsWith("data/")) {
                    has[1] = true;
                }
            }
        } catch (IOException e) {
            Log.error("Cannot read " + jar.getFileName() + " for its files: " + e);
        }
        return has;
    }
}
