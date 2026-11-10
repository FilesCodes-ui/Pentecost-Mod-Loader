package net.fabricmc.api;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
@FunctionalInterface
public interface DedicatedServerModInitializer {
    void onInitializeServer();
}
