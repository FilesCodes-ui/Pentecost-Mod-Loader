package net.fabricmc.loader.api;

/**
 * Thrown when a mod's entrypoint cannot be loaded or run.
 * Fabric mods name this class (Just Enough Items catches it), so it must exist under this name.
 */
public class EntrypointException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String key;

    /** @deprecated Fabric keeps this form for old callers. */
    @Deprecated
    public EntrypointException(String key, String causeModId, Throwable cause) {
        super("Exception while loading entries for entrypoint '" + key + "' provided by '" + causeModId + "'", cause);
        this.key = key;
    }

    public EntrypointException(String key, Throwable cause) {
        super("Exception while loading entries for entrypoint '" + key + "'!", cause);
        this.key = key;
    }

    public EntrypointException(String message) {
        super(message);
        this.key = "<unknown>";
    }

    public EntrypointException(Throwable cause) {
        super(cause);
        this.key = "<unknown>";
    }

    /** The entrypoint key, such as "main" or "client". */
    public String getKey() {
        return key;
    }
}
