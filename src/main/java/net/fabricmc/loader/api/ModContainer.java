package net.fabricmc.loader.api;

import net.fabricmc.loader.api.metadata.ModMetadata;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ModContainer {
    ModMetadata getMetadata();

    List<Path> getRootPaths();

    Path getRootPath();

    Optional<Path> findPath(String file);

    Path getPath(String file);

    net.fabricmc.loader.api.metadata.ModOrigin getOrigin();

    Optional<ModContainer> getContainingMod();

    Collection<ModContainer> getContainedMods();
}
