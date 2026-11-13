package net.fabricmc.loader.launch.common;

/**
 * Old Fabric Loader class, still called by some libraries (Cardinal Components).
 * Under Pentecost there is no Fabric launcher, so getLauncher returns null.
 * Libraries take that to mean the game already runs with its readable names, which is true here.
 */
public abstract class FabricLauncherBase implements FabricLauncher {
    public static FabricLauncher getLauncher() {
        return null;
    }
}
