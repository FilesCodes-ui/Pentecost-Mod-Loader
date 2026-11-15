package khi.pentecost.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * World Wide Local Server, the part that needs no game classes.
 *
 * The host asks the router to open a port (UPnP), learns the public address, and files it under a room code
 * at the rendezvous service. A friend asks the service for the code and connects straight to the host.
 * No game traffic passes through the service.
 *
 * Safety rules this class keeps:
 *  - Rendezvous traffic never leaves https (redirects are followed only https to https).
 *  - The host proves it owns a room with a random secret. The service stores only a hash of it.
 *  - A friend only connects to a public IPv4 address with a normal port, never to what the service happens to say.
 *  - The router port is opened for a limited time, renewed while the world runs, and closed when the world stops,
 *    when sharing stops, or when the game exits.
 *  - Only a device on the local network that answers the router search is trusted as the router, and its
 *    description and control addresses must be on that same device.
 */
public final class Wwls {
    /** The introduction service: a Google Apps Script web app, source in tools/wwls-rendezvous.gs. */
    public static final String[] BACKENDS = {
            "https://script.google.com/macros/s/AKfycbws6mIR7xUfTCJU_3KAf3K2x0dGIuLd35rW05ygDTFM39CGnjHBKe2h1_5hwVGG77sZkQ/exec",
            "https://script.google.com/macros/s/AKfycbz1MpsGspZgNXKrX-RJpU3eQtDzpOqQD2VgENIUO2juUuR7zdQ7tBQH3LgbG0s0QPa7/exec",
            "https://script.google.com/macros/s/AKfycbxIc8rItX1upr1GmAdqzsVYKerZsgh0gcP90RjGKkFQMlM9WQCdatfRt1Ngfeqhnhwlow/exec",
            "https://script.google.com/macros/s/AKfycbwxMvVbzWWH2GEhlijpeq9yO8-5wQJbkxoECAy1xyy2IB9Po5nTV9BwotlXuCDcNii4/exec",
            "https://script.google.com/macros/s/AKfycbzHUnIpbA_QN6GtRHW7GSF9l9HueDxpb-FVFNrE-htp9Z3kktFAdeaLHEYgNDBJ1MS1/exec",
            "https://script.google.com/macros/s/AKfycbxxwwzT95dqlnB8gJTo-dZFqhNzVxD2J7Y5vW1zMB-3zYqe8mLfZ0jcy6Vu0Mr8lOFm/exec",
            // Add more deployments of the same script here, ideally each from a different Google account.
            // At most 32. A room code's first character says which one holds the room.
    };

    /** Room codes are this long. 32 symbols each, about 50 bits. */
    public static final int CODE_LENGTH = 10;

    private static final int ROOM_SECONDS = 900;
    private static final int RENEW_SECONDS = 300;
    private static final int PORT_LEASE_SECONDS = 3600;
    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern ADDRESS = Pattern.compile("^([0-9.]{7,15}):([0-9]{1,5})$");
    private static final Pattern V4 = Pattern.compile("^([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})\\.([0-9]{1,3})$");

    /** Internet traffic. Redirects are followed only when they stay on https. */
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(6)).build();
    /** Traffic to the router. It never follows a redirect, so a device cannot send the game somewhere else. */
    private static final HttpClient LAN = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();

    /** A port the router opened for the game. lease is the seconds it was opened for, 0 when the router only allows permanent ones. */
    public record Mapping(String control, String service, int port, String publicIp, String localIp, int lease) {}

    /** Thrown with a sentence meant for the player. */
    public static final class WwlsException extends Exception {
        public WwlsException(String message) {
            super(message);
        }
    }

    private Wwls() {}

    // ---------------------------------------------------------------- codes and addresses

    /** The script that holds rooms whose code starts with this character. Fixed by the code, so a friend needs no guessing. */
    static String backendFor(String code) {
        return BACKENDS[shardOf(code, BACKENDS.length)];
    }

    /** Which of n scripts a code belongs to, from its first character. Anything unknown goes to the first. */
    static int shardOf(String code, int n) {
        int i = code == null || code.isEmpty() ? -1 : ALPHABET.indexOf(code.charAt(0));
        return i < 0 ? 0 : i % n;
    }

    /** A new code that belongs to script number shard out of n. */
    static String newCode(int shard, int n) {
        int choices = (ALPHABET.length() - shard + n - 1) / n;
        StringBuilder sb = new StringBuilder();
        sb.append(ALPHABET.charAt(shard + n * RANDOM.nextInt(choices)));
        for (int i = 1; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String newCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    /** A random secret that proves a host owns its room. Never shown, never stored on disk. */
    private static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static String cleanCode(String raw) {
        return raw == null ? "" : raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    /** True for an IPv4 address that is on the public internet, not a private, loopback, shared or reserved one. */
    static boolean publicV4(String ip) {
        Matcher m = V4.matcher(ip);
        if (!m.matches()) {
            return false;
        }
        int[] o = new int[4];
        for (int i = 0; i < 4; i++) {
            o[i] = Integer.parseInt(m.group(i + 1));
            if (o[i] > 255) {
                return false;
            }
        }
        int a = o[0];
        int b = o[1];
        if (a == 0 || a == 10 || a == 127 || a >= 224) return false;
        if (a == 100 && b >= 64 && b <= 127) return false;
        if (a == 169 && b == 254) return false;
        if (a == 172 && b >= 16 && b <= 31) return false;
        if (a == 192 && b == 168) return false;
        if (a == 192 && b == 0 && o[2] == 0) return false;
        if (a == 198 && (b == 18 || b == 19)) return false;
        return true;
    }

    /** The only kind of address the game will connect to for a room: public IPv4, port 1024 to 65535. */
    public static boolean validAddress(String addr) {
        if (addr == null) {
            return false;
        }
        Matcher m = ADDRESS.matcher(addr);
        if (!m.matches() || !publicV4(m.group(1))) {
            return false;
        }
        int port = Integer.parseInt(m.group(2));
        return port >= 1024 && port <= 65535;
    }

    // ---------------------------------------------------------------- http

    /** Reads at most max bytes, so a device or a server cannot make the game read forever. */
    private static String fetch(HttpClient client, String url, int max) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<InputStream> r = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = r.body()) {
            return new String(in.readNBytes(max), StandardCharsets.UTF_8);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** The address the internet sees, or null when it cannot be found. */
    public static String publicIp() {
        for (String url : new String[]{"https://api.ipify.org", "https://checkip.amazonaws.com"}) {
            try {
                String ip = fetch(HTTP, url, 64).trim();
                if (publicV4(ip)) {
                    return ip;
                }
            } catch (IOException | RuntimeException e) {
                // try the next one
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- rendezvous

    private static JsonObject call(String action, String code, String key, String addr) throws WwlsException {
        StringBuilder url = new StringBuilder(backendFor(code)).append("?a=").append(action).append("&code=").append(enc(code));
        if (key != null) {
            url.append("&key=").append(enc(key));
        }
        if (addr != null) {
            url.append("&addr=").append(enc(addr)).append("&ttl=").append(ROOM_SECONDS);
        }
        try {
            JsonObject o = JsonParser.parseString(fetch(HTTP, url.toString(), 4096)).getAsJsonObject();
            if (o.has("ok") && o.get("ok").getAsBoolean()) {
                return o;
            }
            String error = o.has("error") ? o.get("error").getAsString() : "";
            switch (error) {
                case "not found":
                    throw new WwlsException("No open world has that code. Check it, or ask the host to open the world again.");
                case "busy":
                    throw new WwlsException("The introduction service is busy. Try again in a minute.");
                case "forbidden":
                    throw new WwlsException("That code is already taken. Open the world again for a new one.");
                default:
                    throw new WwlsException("The introduction service refused the request.");
            }
        } catch (IOException e) {
            throw new WwlsException("Cannot reach the introduction service. Check your internet connection.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WwlsException("Cancelled.");
        } catch (RuntimeException e) {
            throw new WwlsException("The introduction service sent an answer Pentecost does not understand.");
        }
    }

    private static void register(String code, String address, String key) throws WwlsException {
        call("open", code, key, address);
    }

    private static void unregister(String code, String key) {
        try {
            call("close", code, key, null);
        } catch (WwlsException e) {
            // the code expires by itself in a few minutes
        }
    }

    /** Looks up a room. Only returns an address the game is willing to connect to. */
    public static String find(String code) throws WwlsException {
        JsonObject o = call("find", code, null, null);
        String addr = o.has("addr") && o.get("addr").isJsonPrimitive() ? o.get("addr").getAsString() : "";
        if (!validAddress(addr)) {
            throw new WwlsException("The introduction service sent an address Pentecost will not connect to.");
        }
        return addr;
    }

    // ---------------------------------------------------------------- hosting

    /**
     * A world open to friends. It keeps its room and its router port alive while the world runs and closes
     * both when the world stops, when close() is called, or when the game exits.
     */
    public static final class Session {
        private final String code;
        private final String key;
        private final String address;
        private final Mapping mapping;
        private final BooleanSupplier alive;
        private final ScheduledExecutorService timer;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Thread hook;
        private final java.util.List<Runnable> closers = new java.util.concurrent.CopyOnWriteArrayList<>();
        private long lastRenew = System.nanoTime();

        private Session(String code, String key, String address, Mapping mapping, BooleanSupplier alive) {
            this.code = code;
            this.key = key;
            this.address = address;
            this.mapping = mapping;
            this.alive = alive;
            this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "Pentecost WWLS keep-alive");
                t.setDaemon(true);
                return t;
            });
            this.hook = new Thread(this::close, "Pentecost WWLS exit");
            Runtime.getRuntime().addShutdownHook(hook);
            timer.scheduleWithFixedDelay(this::tick, 5, 5, TimeUnit.SECONDS);
        }

        public String code() {
            return code;
        }

        public boolean closed() {
            return closed.get();
        }

        /** Runs r when the session closes, or at once if it already has. Each step runs once. */
        public void onClose(Runnable r) {
            closers.add(r);
            if (closed.get() && closers.remove(r)) {
                runStep(r);
            }
        }

        private static void runStep(Runnable r) {
            try {
                r.run();
            } catch (RuntimeException e) {
                Log.warn("WWLS close step failed: " + e);
            }
        }

        private void tick() {
            try {
                if (!alive.getAsBoolean()) {
                    close();
                    return;
                }
                if (System.nanoTime() - lastRenew >= RENEW_SECONDS * 1_000_000_000L) {
                    lastRenew = System.nanoTime();
                    register(code, address, key);
                    renewPort(mapping);
                }
            } catch (WwlsException | RuntimeException e) {
                Log.warn("WWLS could not renew the room: " + e.getMessage());
            }
        }

        /** Safe to call more than once and from any thread. */
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            timer.shutdown();
            try {
                Runtime.getRuntime().removeShutdownHook(hook);
            } catch (IllegalStateException e) {
                // the game is already exiting
            }
            unregister(code, key);
            closePort(mapping);
            for (Runnable r : closers) {
                if (closers.remove(r)) {
                    runStep(r);
                }
            }
        }
    }

    /**
     * Opens a port on the router and files the room. The caller starts the world on that port and calls
     * close() on the session if that fails. The session closes itself when alive turns false.
     */
    public static Session host(int port, BooleanSupplier alive) throws WwlsException {
        Mapping mapping = openPort(port);
        String key = newSecret();
        String address = mapping.publicIp() + ":" + port;
        // Try the scripts in a random order. The code made for the one that answers says where friends must ask.
        String code = null;
        WwlsException last = null;
        int n = BACKENDS.length;
        int start = RANDOM.nextInt(n);
        for (int i = 0; i < n && code == null; i++) {
            String candidate = newCode((start + i) % n, n);
            try {
                register(candidate, address, key);
                code = candidate;
            } catch (WwlsException e) {
                last = e;
            }
        }
        if (code == null) {
            closePort(mapping);
            throw last;
        }
        return new Session(code, key, address, mapping, alive);
    }

    // ---------------------------------------------------------------- router (UPnP)

    private record Gateway(String control, String service, String localIp) {}

    private static final String[] SEARCH_TYPES = {
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
            "urn:schemas-upnp-org:service:WANIPConnection:1",
            "upnp:rootdevice"};

    private static Gateway discover() throws IOException {
        java.util.List<InetAddress> locals = new java.util.ArrayList<>();
        java.util.Enumeration<java.net.NetworkInterface> nics = java.net.NetworkInterface.getNetworkInterfaces();
        while (nics != null && nics.hasMoreElements()) {
            java.net.NetworkInterface nic = nics.nextElement();
            if (!nic.isUp() || nic.isLoopback() || nic.isVirtual() || !nic.supportsMulticast()) {
                continue;
            }
            for (InetAddress a : java.util.Collections.list(nic.getInetAddresses())) {
                if (a instanceof java.net.Inet4Address && !a.isLinkLocalAddress()) {
                    locals.add(a);
                }
            }
        }
        java.util.Set<String> tried = new java.util.HashSet<>();
        for (InetAddress local : locals) {
            try (DatagramSocket socket = new DatagramSocket(new java.net.InetSocketAddress(local, 0))) {
                socket.setSoTimeout(700);
                for (String type : SEARCH_TYPES) {
                    String search = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
                            + "ST: " + type + "\r\n\r\n";
                    byte[] out = search.getBytes(StandardCharsets.US_ASCII);
                    socket.send(new DatagramPacket(out, out.length, InetAddress.getByName("239.255.255.250"), 1900));
                }
                long end = System.currentTimeMillis() + 2500;
                while (System.currentTimeMillis() < end) {
                    byte[] buf = new byte[2048];
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    try {
                        socket.receive(in);
                    } catch (java.net.SocketTimeoutException e) {
                        continue;
                    }
                    // The router is a device on this network. A reply from anywhere else is ignored.
                    InetAddress from = in.getAddress();
                    if (!from.isSiteLocalAddress()) {
                        continue;
                    }
                    String reply = new String(in.getData(), 0, in.getLength(), StandardCharsets.US_ASCII);
                    Matcher loc = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(reply);
                    if (!loc.find() || !tried.add(from.getHostAddress() + " " + loc.group(1))) {
                        continue;
                    }
                    Gateway g = describe(loc.group(1), from.getHostAddress());
                    if (g != null) {
                        return g;
                    }
                }
            } catch (IOException e) {
                // this adapter cannot search, try the next one
            }
        }
        return null;
    }

    /** Reads the router's description. Its address and its control address must both be the device that answered. */
    private static Gateway describe(String location, String expectedHost) {
        try {
            URI base = URI.create(location);
            if (!"http".equalsIgnoreCase(base.getScheme()) || !expectedHost.equals(base.getHost())) {
                return null;
            }
            String xml = fetch(LAN, location, 262144);
            Matcher m = Pattern.compile("<serviceType>(urn:schemas-upnp-org:service:WAN(?:IP|PPP)Connection:\\d)</serviceType>"
                    + ".*?<controlURL>(.*?)</controlURL>", Pattern.DOTALL).matcher(xml);
            if (!m.find()) {
                return null;
            }
            URI control = base.resolve(m.group(2).trim());
            if (!"http".equalsIgnoreCase(control.getScheme()) || !expectedHost.equals(control.getHost())
                    || control.getPort() != base.getPort()) {
                return null;
            }
            String local;
            try (DatagramSocket probe = new DatagramSocket()) {
                probe.connect(InetAddress.getByName(expectedHost), 9);
                local = probe.getLocalAddress().getHostAddress();
            }
            return new Gateway(control.toString(), m.group(1), local);
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String soap(Gateway g, String action, String args) throws IOException, InterruptedException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action
                + " xmlns:u=\"" + g.service() + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        HttpRequest req = HttpRequest.newBuilder(URI.create(g.control())).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"" + g.service() + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<InputStream> res = LAN.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = res.body()) {
            String text = new String(in.readNBytes(65536), StandardCharsets.UTF_8);
            if (res.statusCode() != 200) {
                throw new IOException("Router answered " + res.statusCode());
            }
            return text;
        }
    }

    private static void addMapping(Gateway g, int port, int lease) throws IOException, InterruptedException {
        String add = "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol><NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + g.localIp() + "</NewInternalClient><NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>Pentecost WWLS</NewPortMappingDescription><NewLeaseDuration>" + lease
                + "</NewLeaseDuration>";
        soap(g, "AddPortMapping", add);
    }

    /** Asks the router to forward the port. Throws with a sentence the player can act on. */
    public static Mapping openPort(int port) throws WwlsException {
        try {
            Gateway g = discover();
            if (g == null) {
                throw new WwlsException("Your router did not answer. Turn on UPnP in the router settings, "
                        + "or use the playit.gg helper on this page.");
            }
            // A limited time, renewed while the world runs. Some routers only allow permanent ones.
            int lease = PORT_LEASE_SECONDS;
            try {
                addMapping(g, port, lease);
            } catch (IOException e) {
                lease = 0;
                addMapping(g, port, lease);
            }
            String ipReply = soap(g, "GetExternalIPAddress", "");
            Matcher m = Pattern.compile("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>").matcher(ipReply);
            String routerIp = m.find() ? m.group(1).trim() : "";
            String seen = publicIp();
            if (seen == null) {
                closePort(new Mapping(g.control(), g.service(), port, "", g.localIp(), lease));
                throw new WwlsException("Cannot find your public address. Check your internet connection.");
            }
            Mapping mapping = new Mapping(g.control(), g.service(), port, seen, g.localIp(), lease);
            if (!routerIp.isEmpty() && !routerIp.equals(seen)) {
                closePort(mapping);
                throw new WwlsException("Your internet provider shares one address between several homes, "
                        + "so a direct connection is not possible. Use the playit.gg helper on this page.");
            }
            return mapping;
        } catch (IOException e) {
            throw new WwlsException("Your router refused to open the port. Turn on UPnP in the router settings, "
                    + "or use the playit.gg helper on this page.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WwlsException("Cancelled.");
        }
    }

    private static void renewPort(Mapping m) {
        if (m.lease() <= 0) {
            return;
        }
        try {
            addMapping(new Gateway(m.control(), m.service(), m.localIp()), m.port(), m.lease());
        } catch (IOException e) {
            Log.warn("WWLS could not renew the router port: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static void closePort(Mapping m) {
        try {
            Gateway g = new Gateway(m.control(), m.service(), m.localIp());
            soap(g, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + m.port()
                    + "</NewExternalPort><NewProtocol>TCP</NewProtocol>");
        } catch (IOException e) {
            // the router drops it on restart, or when its lease ends
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
