package net.fabricmc.loader.api.entrypoint;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
@FunctionalInterface
public interface PreLaunchEntrypoint {
    void onPreLaunch();
}
