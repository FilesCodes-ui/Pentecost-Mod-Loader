package khi.pentecost.game;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import khi.pentecost.boot.Log;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

public final class OnlineFriends {
    public record Friend(String uuid, String name, boolean online, String state, String world, String server) {}

    public record Request(String uuid, String name, long ts) {}

    public record Line(boolean mine, String text, long ts) {}

    private static final String SOCKET = "wss://api.pentecost-modloader.workers.dev/ws?client=game";
    private static final OnlineFriends INSTANCE = new OnlineFriends();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    private final Map<String, Friend> friends = new ConcurrentHashMap<>();
    private final List<Request> incoming = new CopyOnWriteArrayList<>();
    private final List<Request> outgoing = new CopyOnWriteArrayList<>();
    private final Map<String, List<Line>> chats = new ConcurrentHashMap<>();
    private final Map<String, Integer> unread = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonObject>> waiting = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();
    private volatile WebSocket socket;
    private volatile boolean connected;
    private volatile boolean started;
    private volatile String myName = "";
    private volatile String myCode = "";
    private volatile String why = "Connecting";
    private volatile boolean allowJoin;
    private volatile String lastStatus = "";
    private volatile String boundUuid = "";

    private OnlineFriends() {}

    public static OnlineFriends get() {
        return INSTANCE;
    }

    public static void install() {
        INSTANCE.start();
    }

    private void start() {
        if (started || !System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return;
        }
        started = true;
        Thread t = new Thread(this::loop, "pentecost-friends");
        t.setDaemon(true);
        t.start();
    }

    private static String currentUuid() {
        try {
            return Account.signedIn() ? Account.uuid().replace("-", "") : "";
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void reset() {
        WebSocket s = socket;
        socket = null;
        connected = false;
        if (s != null) {
            try {
                s.abort();
            } catch (RuntimeException ignored) {
            }
        }
        friends.clear();
        incoming.clear();
        outgoing.clear();
        chats.clear();
        unread.clear();
        waiting.clear();
        myName = "";
        myCode = "";
        allowJoin = false;
        lastStatus = "";
        boundUuid = "";
        why = "Connecting";
    }

    private void loop() {
        int fails = 0;
        try {
            Thread.sleep(12_000);
            while (true) {
                String bound = currentUuid();
                String token = OnlineSession.token();
                if (token == null) {
                    if (!boundUuid.isEmpty() && !bound.equals(boundUuid)) {
                        reset();
                    }
                    why = Account.signedIn() ? "Connecting" : "Sign in to Minecraft to use friends";
                    Thread.sleep(5_000);
                    continue;
                }
                boolean switched = false;
                try {
                    connect(token);
                    boundUuid = bound;
                    fails = 0;
                    int tick = 0;
                    while (connected) {
                        Thread.sleep(5_000);
                        if (!connected) {
                            break;
                        }
                        if (!currentUuid().equals(boundUuid)) {
                            reset();
                            switched = true;
                            break;
                        }
                        if (++tick % 5 == 0) {
                            WebSocket s = socket;
                            if (s != null) {
                                s.sendText("ping", true);
                            }
                        }
                        sendStatus();
                    }
                } catch (InterruptedException e) {
                    throw e;
                } catch (Exception e) {
                    why = "Cannot reach the server";
                    Log.warn("Pentecost friends connection: " + e.getMessage());
                }
                connected = false;
                socket = null;
                if (switched) {
                    fails = 0;
                    continue;
                }
                fails = Math.min(fails + 1, 6);
                Thread.sleep(Math.min(60_000L, 3_000L << fails));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void connect(String token) throws Exception {
        WebSocket ws = HTTP.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .connectTimeout(Duration.ofSeconds(15))
                .buildAsync(URI.create(SOCKET), new Listener())
                .get(20, TimeUnit.SECONDS);
        socket = ws;
    }

    private final class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket ws) {
            socket = ws;
            connected = true;
            why = "";
            lastStatus = "";
            ws.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            if (socket != ws) {
                buf.setLength(0);
                ws.request(1);
                return null;
            }
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                try {
                    handle(text);
                } catch (RuntimeException e) {
                    Log.warn("Pentecost friends message: " + e);
                }
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            if (socket != ws) {
                return null;
            }
            connected = false;
            socket = null;
            why = reason == null || reason.isBlank() ? "Disconnected" : reason;
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            if (socket != ws) {
                return;
            }
            connected = false;
            socket = null;
            why = "Connection lost";
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : "";
    }

    private static Friend friendOf(JsonObject o) {
        String state = "";
        String world = "";
        String server = "";
        if (o.has("status") && o.get("status").isJsonObject()) {
            JsonObject s = o.getAsJsonObject("status");
            state = str(s, "state");
            world = str(s, "world");
            server = str(s, "server");
        }
        boolean online = o.has("online") && o.get("online").getAsBoolean();
        return new Friend(str(o, "uuid"), str(o, "name"), online, state, world, server);
    }

    private static Request requestOf(JsonObject o) {
        long ts = o.has("ts") && o.get("ts").isJsonPrimitive() ? o.get("ts").getAsLong() : 0;
        return new Request(str(o, "uuid"), str(o, "name"), ts);
    }

    private void handle(String text) {
        if (text.equals("pong")) {
            return;
        }
        JsonObject m = JsonParser.parseString(text).getAsJsonObject();
        String t = str(m, "t");
        if (m.has("re") && !m.get("re").isJsonNull()) {
            Consumer<JsonObject> done = waiting.remove(str(m, "re"));
            if (done != null) {
                done.accept(m);
            }
        }
        switch (t) {
            case "state" -> {
                JsonObject me = m.getAsJsonObject("me");
                myName = str(me, "name");
                myCode = str(me, "code");
                allowJoin = me.has("allowJoin") && me.get("allowJoin").getAsBoolean();
                friends.clear();
                for (JsonElement e : m.getAsJsonArray("friends")) {
                    Friend f = friendOf(e.getAsJsonObject());
                    friends.put(f.uuid(), f);
                }
                incoming.clear();
                for (JsonElement e : m.getAsJsonArray("incoming")) {
                    incoming.add(requestOf(e.getAsJsonObject()));
                }
                outgoing.clear();
                for (JsonElement e : m.getAsJsonArray("outgoing")) {
                    outgoing.add(requestOf(e.getAsJsonObject()));
                }
                JsonArray ack = new JsonArray();
                int fresh = 0;
                for (JsonElement e : m.getAsJsonArray("mail")) {
                    JsonObject mail = e.getAsJsonObject();
                    if (mail.has("id")) {
                        ack.add(mail.get("id").getAsLong());
                    }
                    if (str(mail, "t").equals("msg")) {
                        addLine(str(mail, "from"), false, str(mail, "text"), mail.has("ts") ? mail.get("ts").getAsLong() : System.currentTimeMillis());
                        fresh++;
                    }
                }
                if (ack.size() > 0) {
                    JsonObject a = new JsonObject();
                    a.addProperty("t", "ack");
                    a.add("ids", ack);
                    send(a, null);
                }
                if (fresh > 0) {
                    toast("Messages from friends", fresh == 1 ? "You have a new message." : "You have " + fresh + " new messages.");
                }
                lastStatus = "";
                sendStatus();
            }
            case "presence" -> {
                Friend old = friends.get(str(m, "uuid"));
                if (old != null) {
                    Friend now = friendOf(m);
                    friends.put(now.uuid(), now);
                    if (!now.server().isEmpty() && old.server().isEmpty()) {
                        toast(now.name() + " is hosting", "Open Friends to join.");
                    }
                }
            }
            case "request" -> {
                Request r = requestOf(m.getAsJsonObject("from"));
                incoming.removeIf(x -> x.uuid().equals(r.uuid()));
                incoming.add(r);
                toast(r.name() + " sent a friend request", "Open Friends to answer.");
            }
            case "requestSent" -> {
                Request r = requestOf(m.getAsJsonObject("to"));
                outgoing.removeIf(x -> x.uuid().equals(r.uuid()));
                outgoing.add(r);
            }
            case "requestGone" -> {
                String id = str(m, "uuid");
                incoming.removeIf(x -> x.uuid().equals(id));
                outgoing.removeIf(x -> x.uuid().equals(id));
            }
            case "friend" -> {
                Friend f = friendOf(m.getAsJsonObject("friend"));
                friends.put(f.uuid(), f);
                incoming.removeIf(x -> x.uuid().equals(f.uuid()));
                outgoing.removeIf(x -> x.uuid().equals(f.uuid()));
            }
            case "unfriend" -> {
                String id = str(m, "uuid");
                friends.remove(id);
                chats.remove(id);
                unread.remove(id);
            }
            case "feed" -> FeedHooks.ping();
            case "code" -> myCode = str(m, "code");
            case "msg" -> {
                String from = str(m, "from");
                addLine(from, false, str(m, "text"), m.has("ts") ? m.get("ts").getAsLong() : System.currentTimeMillis());
                Friend f = friends.get(from);
                if (!(Minecraft.getInstance().screen instanceof FriendsScreen)) {
                    toast(f == null ? "New message" : f.name(), str(m, "text"));
                } else {
                    FriendsScreen.ping(from);
                }
            }
            default -> {
            }
        }
    }

    private void addLine(String friend, boolean mine, String text, long ts) {
        List<Line> list = chats.computeIfAbsent(friend, k -> new CopyOnWriteArrayList<>());
        list.add(new Line(mine, text, ts));
        while (list.size() > 200) {
            list.remove(0);
        }
        if (!mine) {
            unread.merge(friend, 1, Integer::sum);
        }
    }

    private static void toast(String title, String body) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> PentecostToast.show(title, body, () -> {
            if (!(mc.screen instanceof FriendsScreen)) {
                mc.setScreen(new FriendsScreen(mc.screen));
            }
        }));
    }

    private void send(JsonObject o, Consumer<JsonObject> done) {
        WebSocket s = socket;
        if (s == null || !connected) {
            if (done != null) {
                JsonObject err = new JsonObject();
                err.addProperty("t", "error");
                err.addProperty("error", "Not connected yet.");
                done.accept(err);
            }
            return;
        }
        if (done != null) {
            String id = Long.toString(ids.incrementAndGet());
            o.addProperty("id", id);
            waiting.put(id, done);
        }
        s.sendText(o.toString(), true);
    }

    private void sendStatus() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> {
            String state = "menu";
            String world = null;
            String server = null;
            if (mc.level != null) {
                var local = mc.getSingleplayerServer();
                if (local != null) {
                    state = "singleplayer";
                    world = local.getWorldData().getLevelName();
                    server = WwlsScreen.hostAddress();
                } else {
                    state = "multiplayer";
                }
            }
            JsonObject s = new JsonObject();
            s.addProperty("state", state);
            if (world != null && world.length() <= 64) {
                s.addProperty("world", world);
            }
            if (server != null) {
                s.addProperty("server", server);
            }
            JsonObject root = new JsonObject();
            root.addProperty("t", "status");
            root.add("s", s);
            String text = root.toString();
            if (text.equals(lastStatus)) {
                return;
            }
            WebSocket ws = socket;
            if (ws != null && connected) {
                lastStatus = text;
                ws.sendText(text, true);
            }
        });
    }

    public boolean connected() {
        return connected;
    }

    public String why() {
        return why;
    }

    public String name() {
        return myName;
    }

    public String code() {
        return myCode;
    }

    public boolean allowJoin() {
        return allowJoin;
    }

    public List<Friend> friends() {
        List<Friend> list = new ArrayList<>(friends.values());
        list.sort(Comparator.comparing((Friend f) -> !f.online()).thenComparing(f -> f.name().toLowerCase(java.util.Locale.ROOT)));
        return list;
    }

    public Friend friend(String uuid) {
        return uuid == null ? null : friends.get(uuid);
    }

    public List<Request> incoming() {
        return incoming;
    }

    public List<Request> outgoing() {
        return outgoing;
    }

    public List<Line> chat(String uuid) {
        List<Line> list = chats.get(uuid);
        return list == null ? List.of() : list;
    }

    public int unread(String uuid) {
        return unread.getOrDefault(uuid, 0);
    }

    public int unreadTotal() {
        int n = 0;
        for (int v : unread.values()) {
            n += v;
        }
        return n;
    }

    public void read(String uuid) {
        unread.remove(uuid);
    }

    public void refresh() {
        JsonObject o = new JsonObject();
        o.addProperty("t", "refresh");
        send(o, null);
    }

    public void add(String code, Consumer<String> result) {
        JsonObject o = new JsonObject();
        o.addProperty("t", "add");
        o.addProperty("code", code);
        send(o, r -> {
            if (str(r, "t").equals("error")) {
                result.accept(str(r, "error"));
            } else if (r.has("friend")) {
                result.accept("You and " + str(r, "friend") + " are friends now.");
            } else {
                result.accept("Request sent to " + str(r, "sent") + ".");
            }
        });
    }

    public void answer(String type, String uuid) {
        JsonObject o = new JsonObject();
        o.addProperty("t", type);
        o.addProperty("uuid", uuid);
        send(o, null);
    }

    public void message(String uuid, String text, Consumer<String> failed) {
        String clean = text.trim();
        if (clean.isEmpty()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("t", "msg");
        o.addProperty("to", uuid);
        o.addProperty("text", clean);
        send(o, r -> {
            if (str(r, "t").equals("error")) {
                failed.accept(str(r, "error"));
            } else {
                addLine(uuid, true, clean, System.currentTimeMillis());
            }
        });
    }

    public void setAllowJoin(boolean on) {
        allowJoin = on;
        JsonObject o = new JsonObject();
        o.addProperty("t", "allowJoin");
        o.addProperty("v", on);
        send(o, null);
    }

    public void newCode() {
        JsonObject o = new JsonObject();
        o.addProperty("t", "newCode");
        send(o, null);
    }
}
