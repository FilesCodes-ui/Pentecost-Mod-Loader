package khi.pentecost.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.core.Wwls;
import net.minecraft.client.Minecraft;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

final class DiscordStatus {
    static final String KEY = "online.discordStatus";
    private static final String APP = "1573450531718955129";
    private static final String INVITE = "https://discord.gg/4ZWm2QNMvM";
    private static final long STARTED = System.currentTimeMillis() / 1000L;
    private static final int OP_HANDSHAKE = 0;
    private static final int OP_FRAME = 1;
    private static final int OP_CLOSE = 2;
    private static final int OP_PING = 3;
    private static final int OP_PONG = 4;

    private record Frame(int op, JsonObject body) {}

    private static volatile boolean started;
    private static RandomAccessFile pipe;
    private static FileInputStream peek;
    private static String sent = "";
    private static long lastJoin;

    private DiscordStatus() {}

    static void install() {
        if (started || APP.isEmpty() || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        started = true;
        Thread t = new Thread(DiscordStatus::loop, "pentecost-discord-status");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    private static void loop() {
        try {
            Thread.sleep(8_000);
            int n = 0;
            while (true) {
                try {
                    if (n++ % 5 == 0) {
                        alive();
                        tick();
                    }
                    listen();
                } catch (IOException | RuntimeException e) {
                    close();
                }
                Thread.sleep(1_000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void alive() {
        try {
            String base = System.getenv("APPDATA");
            if (base == null) {
                return;
            }
            Path dir = Path.of(base, "PentecostManager");
            if (!Files.isDirectory(dir)) {
                return;
            }
            Path file = dir.resolve("game-alive");
            Files.writeString(file, Long.toString(System.currentTimeMillis()));
            file.toFile().deleteOnExit();
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void tick() throws IOException {
        if (!Settings.on(KEY)) {
            if (pipe != null) {
                send(OP_FRAME, activity(null));
                close();
            }
            sent = "";
            return;
        }
        if (pipe == null && !open()) {
            return;
        }
        JsonObject act = new JsonObject();
        AtomicReference<String> where = new AtomicReference<>("In the menu");
        AtomicReference<int[]> players = new AtomicReference<>();
        Minecraft mc = Minecraft.getInstance();
        try {
            mc.submit(() -> {
                if (mc.level != null && !khi.pentecost.game.menuworld.MenuHost.showing()) {
                    var server = mc.getSingleplayerServer();
                    if (server != null) {
                        boolean hosting = WwlsScreen.hosting();
                        where.set(hosting ? "Hosting a world for friends" : "Playing singleplayer");
                        if (hosting) {
                            int now = Math.max(1, server.getPlayerCount());
                            players.set(new int[]{now, Math.max(now, server.getMaxPlayers())});
                        }
                    } else {
                        where.set("Playing on a server");
                    }
                }
            }).get();
        } catch (Exception ignored) {
        }
        act.addProperty("details", where.get());
        act.addProperty("state", "Pentecost " + khi.pentecost.boot.PentecostMain.VERSION + ", " + khi.pentecost.core.PentecostRuntime.mods.size() + " mods");
        JsonObject ts = new JsonObject();
        ts.addProperty("start", STARTED);
        act.add("timestamps", ts);
        JsonObject assets = new JsonObject();
        assets.addProperty("large_image", "pentecost");
        assets.addProperty("large_text", "Pentecost mod loader");
        act.add("assets", assets);
        String code = WwlsScreen.hostCode();
        int[] party = players.get();
        if (code != null && party != null) {
            assets.addProperty("invite_cover_image", "pentecost_invite");
            assets.addProperty("large_url", INVITE);
            JsonArray platforms = new JsonArray();
            platforms.add("desktop");
            act.add("supported_platforms", platforms);
            JsonObject p = new JsonObject();
            p.addProperty("id", UUID.nameUUIDFromBytes(("pentecost-wwls:" + code).getBytes(StandardCharsets.UTF_8)).toString());
            JsonArray size = new JsonArray();
            size.add(party[0]);
            size.add(party[1]);
            p.add("size", size);
            act.add("party", p);
            JsonObject secrets = new JsonObject();
            secrets.addProperty("join", code);
            act.add("secrets", secrets);
        } else {
            JsonArray buttons = new JsonArray();
            JsonObject b = new JsonObject();
            b.addProperty("label", "Get Pentecost");
            b.addProperty("url", "https://github.com/" + khi.pentecost.update.ReleaseInfo.REPO);
            buttons.add(b);
            JsonObject d = new JsonObject();
            d.addProperty("label", "Join the Discord");
            d.addProperty("url", INVITE);
            buttons.add(d);
            act.add("buttons", buttons);
        }
        String text = act.toString();
        if (text.equals(sent)) {
            return;
        }
        send(OP_FRAME, activity(act));
        sent = text;
    }

    private static JsonObject activity(JsonObject act) {
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (act != null) {
            args.add("activity", act);
        }
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "SET_ACTIVITY");
        o.add("args", args);
        o.addProperty("nonce", UUID.randomUUID().toString());
        return o;
    }

    private static JsonObject subscribe(String event) {
        JsonObject o = new JsonObject();
        o.addProperty("cmd", "SUBSCRIBE");
        o.addProperty("evt", event);
        o.add("args", new JsonObject());
        o.addProperty("nonce", UUID.randomUUID().toString());
        return o;
    }

    private static boolean open() {
        for (int i = 0; i < 10; i++) {
            try {
                RandomAccessFile f = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + i, "rw");
                pipe = f;
                peek = new FileInputStream(f.getFD());
                JsonObject hello = new JsonObject();
                hello.addProperty("v", 1);
                hello.addProperty("client_id", APP);
                send(OP_HANDSHAKE, hello);
                send(OP_FRAME, subscribe("ACTIVITY_JOIN"));
                sent = "";
                return true;
            } catch (IOException e) {
                close();
            }
        }
        return false;
    }

    private static void listen() throws IOException {
        if (pipe == null || peek == null) {
            return;
        }
        for (int i = 0; i < 20 && peek.available() >= 8; i++) {
            handle(read());
        }
    }

    private static void write(int op, byte[] data) throws IOException {
        ByteBuffer head = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        head.putInt(op).putInt(data.length);
        pipe.write(head.array());
        pipe.write(data);
    }

    private static Frame read() throws IOException {
        byte[] h = new byte[8];
        pipe.readFully(h);
        ByteBuffer b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN);
        int op = b.getInt(0);
        int len = b.getInt(4);
        if (len < 0 || len > 1_000_000) {
            throw new IOException("bad frame");
        }
        byte[] data = new byte[len];
        pipe.readFully(data);
        if (op == OP_CLOSE) {
            Log.warn("Discord status closed: " + new String(data, StandardCharsets.UTF_8));
            throw new IOException("closed");
        }
        if (op == OP_PING) {
            write(OP_PONG, data);
            return new Frame(op, null);
        }
        JsonObject body = null;
        try {
            JsonElement e = JsonParser.parseString(new String(data, StandardCharsets.UTF_8));
            if (e.isJsonObject()) {
                body = e.getAsJsonObject();
            }
        } catch (RuntimeException ignored) {
        }
        return new Frame(op, body);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static void handle(Frame f) {
        JsonObject o = f.body();
        if (f.op() != OP_FRAME || o == null) {
            return;
        }
        if ("ACTIVITY_JOIN".equals(str(o, "evt"))) {
            JsonElement data = o.get("data");
            if (data != null && data.isJsonObject()) {
                onJoin(str(data.getAsJsonObject(), "secret"));
            }
        }
    }

    private static void onJoin(String secret) {
        long now = System.currentTimeMillis();
        if (now - lastJoin < 5_000) {
            return;
        }
        lastJoin = now;
        String code = Wwls.cleanCode(secret);
        if (code.length() != Wwls.CODE_LENGTH) {
            Log.warn("Discord join ignored: the secret is not a room code.");
            return;
        }
        WwlsScreen.joinFromDiscord(code);
    }

    private static void send(int op, JsonObject body) throws IOException {
        write(op, body.toString().getBytes(StandardCharsets.UTF_8));
        String nonce = str(body, "nonce");
        for (int i = 0; i < 20; i++) {
            Frame f = read();
            handle(f);
            JsonObject o = f.body();
            if (f.op() != OP_FRAME || o == null) {
                continue;
            }
            String evt = str(o, "evt");
            if (nonce.isEmpty()) {
                if ("READY".equals(evt)) {
                    return;
                }
                if ("ERROR".equals(evt)) {
                    throw new IOException("Discord refused the handshake");
                }
            } else if (nonce.equals(str(o, "nonce"))) {
                if ("ERROR".equals(evt)) {
                    Log.warn("Discord refused a command: " + o);
                }
                return;
            }
        }
        throw new IOException("no reply");
    }

    private static void close() {
        try {
            if (pipe != null) {
                pipe.close();
            }
        } catch (IOException ignored) {
        }
        pipe = null;
        peek = null;
    }
}
