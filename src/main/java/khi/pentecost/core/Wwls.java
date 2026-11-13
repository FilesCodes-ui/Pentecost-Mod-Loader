package khi.pentecost.core;

import java.io.IOException;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * World Wide Local Server, the part that needs no game classes.
 *
 * The host asks the router to open a port (UPnP), learns the public address, and files it under a room code
 * at the rendezvous service. A friend asks the service for the code and connects straight to the host.
 * No game traffic passes through the service.
 */
public final class Wwls {
    /** The introduction service: a Google Apps Script web app that keeps a code and an address for a few minutes. */
    public static final String RENDEZVOUS =
            "https://script.google.com/macros/s/AKfycbw9cqh2cmCC47qEp_oENJ-vn2t1LI4fMdTv2gx4vR5MAgsQBQvY9H-tVPQZN-KTeaJ92g/exec";

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS).connectTimeout(Duration.ofSeconds(6)).build();

    /** A port the router opened for the game. */
    public record Mapping(String control, String service, int port, String publicIp) {}

    /** Thrown with a sentence meant for the player. */
    public static final class WwlsException extends Exception {
        public WwlsException(String message) {
            super(message);
        }
    }

    private Wwls() {}

    public static String newCode() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            sb.append(ALPHABET.charAt(r.nextInt(ALPHABET.length())));
        }
        return sb.toString();
    }

    public static String cleanCode(String raw) {
        return raw == null ? "" : raw.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    private static String get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    /** The address the internet sees, or null when it cannot be found. */
    public static String publicIp() {
        for (String url : new String[]{"https://api.ipify.org", "https://checkip.amazonaws.com"}) {
            try {
                String ip = get(url).trim();
                if (ip.matches("[0-9a-fA-F:.]{7,45}")) {
                    return ip;
                }
            } catch (IOException e) {
                // try the next one
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- rendezvous

    private static String call(String action, String code, String extra) throws WwlsException {
        try {
            String body = get(RENDEZVOUS + "?a=" + action + "&code=" + URLEncoder.encode(code, StandardCharsets.UTF_8) + extra);
            if (body.contains("\"ok\":true")) {
                return body;
            }
            if (body.contains("not found")) {
                throw new WwlsException("No open world has that code. Check it, or ask the host to open the world again.");
            }
            throw new WwlsException("The introduction service refused the request.");
        } catch (IOException e) {
            throw new WwlsException("Cannot reach the introduction service. Check your internet connection.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WwlsException("Cancelled.");
        }
    }

    public static void register(String code, String address) throws WwlsException {
        call("open", code, "&addr=" + URLEncoder.encode(address, StandardCharsets.UTF_8) + "&ttl=21600");
    }

    public static String find(String code) throws WwlsException {
        String body = call("find", code, "");
        Matcher m = Pattern.compile("\"addr\":\"([^\"]+)\"").matcher(body);
        if (!m.find()) {
            throw new WwlsException("The introduction service sent an answer Pentecost does not understand.");
        }
        return m.group(1);
    }

    public static void unregister(String code) {
        try {
            call("close", code, "");
        } catch (WwlsException e) {
            // the code expires by itself
        }
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
                    String reply = new String(in.getData(), 0, in.getLength(), StandardCharsets.US_ASCII);
                    Matcher loc = Pattern.compile("(?im)^location:\\s*(\\S+)").matcher(reply);
                    if (!loc.find() || !tried.add(loc.group(1))) {
                        continue;
                    }
                    Gateway g = describe(loc.group(1));
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

    private static Gateway describe(String location) {
        try {
            String xml = get(location);
            Matcher m = Pattern.compile("<serviceType>(urn:schemas-upnp-org:service:WAN(?:IP|PPP)Connection:\\d)</serviceType>"
                    + ".*?<controlURL>(.*?)</controlURL>", Pattern.DOTALL).matcher(xml);
            if (!m.find()) {
                return null;
            }
            URI base = URI.create(location);
            String control = base.resolve(m.group(2).trim()).toString();
            String local;
            try (DatagramSocket probe = new DatagramSocket()) {
                probe.connect(InetAddress.getByName(base.getHost()), 9);
                local = probe.getLocalAddress().getHostAddress();
            }
            return new Gateway(control, m.group(1), local);
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
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            throw new IOException("Router answered " + res.statusCode());
        }
        return res.body();
    }

    /** Asks the router to forward the port. Throws with a sentence the player can act on. */
    public static Mapping openPort(int port) throws WwlsException {
        try {
            Gateway g = discover();
            if (g == null) {
                throw new WwlsException("Your router did not answer. Turn on UPnP in the router settings, "
                        + "or use the playit.gg helper on this page.");
            }
            String add = "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                    + "<NewProtocol>TCP</NewProtocol><NewInternalPort>" + port + "</NewInternalPort>"
                    + "<NewInternalClient>" + g.localIp() + "</NewInternalClient><NewEnabled>1</NewEnabled>"
                    + "<NewPortMappingDescription>Pentecost WWLS</NewPortMappingDescription><NewLeaseDuration>0</NewLeaseDuration>";
            soap(g, "AddPortMapping", add);
            String ipReply = soap(g, "GetExternalIPAddress", "");
            Matcher m = Pattern.compile("<NewExternalIPAddress>(.*?)</NewExternalIPAddress>").matcher(ipReply);
            String routerIp = m.find() ? m.group(1).trim() : "";
            String seen = publicIp();
            if (seen == null) {
                throw new WwlsException("Cannot find your public address. Check your internet connection.");
            }
            if (!routerIp.isEmpty() && !routerIp.equals(seen)) {
                closePort(new Mapping(g.control(), g.service(), port, seen));
                throw new WwlsException("Your internet provider shares one address between several homes, "
                        + "so a direct connection is not possible. Use the playit.gg helper on this page.");
            }
            return new Mapping(g.control(), g.service(), port, seen);
        } catch (IOException e) {
            throw new WwlsException("Your router refused to open the port. Turn on UPnP in the router settings, "
                    + "or use the playit.gg helper on this page.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WwlsException("Cancelled.");
        }
    }

    public static void closePort(Mapping m) {
        try {
            Gateway g = new Gateway(m.control(), m.service(), "");
            soap(g, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + m.port()
                    + "</NewExternalPort><NewProtocol>TCP</NewProtocol>");
        } catch (IOException e) {
            // the router drops it on restart
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
