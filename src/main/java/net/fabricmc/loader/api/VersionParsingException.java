package net.fabricmc.loader.api;

/** Pentecost's own copy of this Fabric Loader API type. Calls land in Pentecost. No Fabric Loader code runs. */
public class VersionParsingException extends Exception {
    public VersionParsingException() {
        super();
    }

    public VersionParsingException(Throwable t) {
        super(t);
    }

    public VersionParsingException(String s) {
        super(s);
    }

    public VersionParsingException(String s, Throwable t) {
        super(s, t);
    }
}
