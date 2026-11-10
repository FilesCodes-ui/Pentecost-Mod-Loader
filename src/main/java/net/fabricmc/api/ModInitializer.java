package net.fabricmc.api;

/**
 * Pentecost's own copy of the Fabric entrypoint interface. Fabric mods implement this name,
 * and the call lands in Pentecost. No Fabric Loader code runs.
 */
@FunctionalInterface
public interface ModInitializer {
    void onInitialize();
}
