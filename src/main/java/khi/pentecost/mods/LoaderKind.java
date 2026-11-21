package khi.pentecost.mods;

public enum LoaderKind {
    FORGE("Forge"),
    FABRIC("Fabric"),
    QUILT("Quilt"),

    LIBRARY("Library");

    public final String label;

    LoaderKind(String label) {
        this.label = label;
    }
}
