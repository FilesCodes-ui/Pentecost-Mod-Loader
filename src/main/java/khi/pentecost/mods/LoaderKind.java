package khi.pentecost.mods;

/** Which loader a mod was written for. */
public enum LoaderKind {
    FORGE("Forge"),
    FABRIC("Fabric"),
    QUILT("Quilt");

    public final String label;

    LoaderKind(String label) {
        this.label = label;
    }
}
