package khi.pentecost.mods;

/** Which loader a mod was written for. */
public enum LoaderKind {
    FORGE("Forge"),
    FABRIC("Fabric"),
    QUILT("Quilt"),
    /** A plain library jar bundled inside a mod. It has no metadata and no entrypoints. */
    LIBRARY("Library");

    public final String label;

    LoaderKind(String label) {
        this.label = label;
    }
}
