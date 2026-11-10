package net.fabricmc.loader.api.metadata;

import java.nio.file.Path;
import java.util.List;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public interface ModOrigin {
    Kind getKind();

    List<Path> getPaths();

    String getParentModId();

    String getParentSubLocation();

    enum Kind {
        PATH, NESTED, UNKNOWN
    }
}
