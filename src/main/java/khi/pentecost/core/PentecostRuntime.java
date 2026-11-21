package khi.pentecost.core;

import khi.pentecost.loader.GameClassLoader;
import khi.pentecost.mods.ModInfo;
import khi.pentecost.mods.ModRunner;
import khi.pentecost.remap.RemapService;

import java.nio.file.Path;
import java.util.List;

public final class PentecostRuntime {
    public static volatile GameClassLoader loader;
    public static volatile List<ModInfo> mods = List.of();
    public static volatile ModRunner runner;
    public static volatile RemapService remap;
    public static volatile Path gameDir;
    public static volatile String[] launchArgs = new String[0];
    public static volatile boolean client = true;
    public static volatile boolean forgeLayer;

    private PentecostRuntime() {}

    public static java.util.Set<String> forgeCovers = java.util.Set.of();

    public static java.util.List<khi.pentecost.mods.ModInfo> allMods = java.util.List.of();

    public static java.util.Map<String, java.util.List<khi.pentecost.control.Features.Feature>> features =
            java.util.Map.of();
}
