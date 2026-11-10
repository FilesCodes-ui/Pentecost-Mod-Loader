package net.fabricmc.loader.api;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public interface Version extends Comparable<Version> {
    String getFriendlyString();

    static Version parse(String string) throws VersionParsingException {
        return khi.pentecost.adapter.fabric.FabricLoaderImpl.parseVersion(string);
    }
}
