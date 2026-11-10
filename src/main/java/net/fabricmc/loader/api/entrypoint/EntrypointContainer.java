package net.fabricmc.loader.api.entrypoint;

import net.fabricmc.loader.api.ModContainer;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public interface EntrypointContainer<T> {
    T getEntrypoint();

    ModContainer getProvider();

    default String getDefinition() {
        return "";
    }
}
