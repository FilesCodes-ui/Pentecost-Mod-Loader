package khi.pentecost.game.menuworld.source;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackRepository;
import net.minecraft.server.packs.repository.ServerPacksSource;
import net.minecraft.server.packs.resources.MultiPackResourceManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Loads the worldgen registries (biomes, dimension types and so on) from the vanilla data pack only. The menu
 * world must not see mod biomes or mod data packs, so only the pack named "vanilla" is selected.
 * Takes a second or two, so call it off the render thread.
 */
public final class MenuRegistries {
    private MenuRegistries() {}

    public static RegistryAccess.Frozen loadVanilla(Path scratchDir) throws IOException {
        Files.createDirectories(scratchDir);
        PackRepository repo = ServerPacksSource.createPackRepository(scratchDir);
        repo.reload();
        repo.setSelected(List.of("vanilla"));
        try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, repo.openAllSelected())) {
            return RegistryDataLoader.load(resources, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
                    RegistryDataLoader.WORLDGEN_REGISTRIES);
        }
    }
}
