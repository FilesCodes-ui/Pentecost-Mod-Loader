package khi.pentecost.core;

import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.ModInfo;
import khi.pentecost.mods.ModRunner;
import khi.pentecost.remap.RemapService;

import java.nio.file.Path;
import java.util.List;

/** The running loader's shared state. Adapters read it to answer mod calls. */
public final class PentecostRuntime {
    public static volatile GameClassLoader loader;
    public static volatile List<ModInfo> mods = List.of();
    public static volatile ModRunner runner;
    public static volatile RemapService remap;
    public static volatile Path gameDir;
    public static volatile String[] launchArgs = new String[0];
    public static volatile boolean client = true;
    /** True when Forge's real code is loaded. Forge then starts Forge mods itself. */
    public static volatile boolean forgeLayer;

    private PentecostRuntime() {}

    /** Fabric API modules whose job Forge already does. Loaded as libraries, never started. */
    public static java.util.Set<String> forgeCovers = java.util.Set.of();
}
