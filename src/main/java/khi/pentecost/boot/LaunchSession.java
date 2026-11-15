package khi.pentecost.boot;

/**
 * A Minecraft session handed to Pentecost on its command line, by the Modrinth App wrapper or the official launcher.
 * Only "real" when it has a name, an id and a token that is not the placeholder "0". The token never appears in toString.
 */
public record LaunchSession(String name, String uuid, String token, String type) {
    public static final LaunchSession NONE = new LaunchSession(null, null, null, null);

    /** Reads --username, --uuid, --accessToken and --userType from the arguments. Everything else is ignored. */
    public static LaunchSession parse(String[] args) {
        String name = null;
        String uuid = null;
        String token = null;
        String type = null;
        for (int i = 0; i + 1 < args.length; i++) {
            switch (args[i]) {
                case "--username" -> name = args[++i];
                case "--uuid" -> uuid = args[++i];
                case "--accessToken" -> token = args[++i];
                case "--userType" -> type = args[++i];
                default -> { }
            }
        }
        return new LaunchSession(name, uuid, token, type);
    }

    public boolean real() {
        return notBlank(name) && notBlank(uuid) && notBlank(token) && !token.equals("0");
    }

    public String gameName() { return real() ? name : "Dev"; }
    public String gameUuid() { return real() ? uuid : "00000000-0000-0000-0000-000000000000"; }
    public String gameToken() { return real() ? token : "0"; }
    public String gameType() { return real() ? "msa" : "legacy"; }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    @Override
    public String toString() {
        return "LaunchSession[" + (real() ? "name=" + name : "none") + "]";
    }
}
