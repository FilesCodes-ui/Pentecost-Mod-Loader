package net.fabricmc.loader.api;

public class EntrypointException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final String key;

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

    public String getKey() {
        return key;
    }
}
