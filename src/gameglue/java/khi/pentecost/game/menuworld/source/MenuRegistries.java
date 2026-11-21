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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MenuRegistries {
    private MenuRegistries() {}

    public static RegistryAccess.Frozen loadVanilla(Path scratchDir) throws IOException {
        Files.createDirectories(scratchDir);
        PackRepository repo = ServerPacksSource.createPackRepository(scratchDir);
        repo.reload();
        repo.setSelected(List.of("vanilla"));
        try (MultiPackResourceManager resources = new MultiPackResourceManager(PackType.SERVER_DATA, repo.openAllSelected())) {
            return RegistryDataLoader.load(resources, RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY),
                    registries());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RegistryDataLoader.RegistryData<?>> registries() {
        Map<Object, RegistryDataLoader.RegistryData<?>> byKey = new LinkedHashMap<>();
        for (RegistryDataLoader.RegistryData<?> d : RegistryDataLoader.WORLDGEN_REGISTRIES) {
            byKey.put(d.key(), d);
        }
        try {
            Class<?> hooks = Class.forName("net.minecraftforge.registries.DataPackRegistriesHooks");
            for (Object o : (List<Object>) hooks.getMethod("getDataPackRegistries").invoke(null)) {
                RegistryDataLoader.RegistryData<?> d = (RegistryDataLoader.RegistryData<?>) o;
                byKey.putIfAbsent(d.key(), d);
            }
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
        }
        return new ArrayList<>(byKey.values());
    }
}
