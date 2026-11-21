import { DurableObject } from "cloudflare:workers";

const MOJANG = "https://sessionserver.mojang.com/session/minecraft/hasJoined";
const TOKEN_MS = 30 * 86400000;
const CHALLENGE_MS = 120000;
const CODE_ABC = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
const enc = new TextEncoder();

const json = (data, status = 200) =>
  new Response(JSON.stringify(data), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });
const fail = (status, error) => json({ error }, status);

function b64u(bytes) {
  let s = "";
  for (const b of new Uint8Array(bytes)) s += String.fromCharCode(b);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function unb64(s) {
  const t = String(s).replace(/-/g, "+").replace(/_/g, "/");
  return Uint8Array.from(atob(t + "===".slice((t.length + 3) % 4)), (c) => c.charCodeAt(0));
}
const hex = (bytes) => [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, "0")).join("");
const randomHex = (n) => hex(crypto.getRandomValues(new Uint8Array(n)));

let macKey;
async function hmacKey(env) {
  if (!macKey || macKey.secret !== env.SESSION_SECRET) {
    if (!env.SESSION_SECRET || env.SESSION_SECRET.length < 32) throw new Error("SESSION_SECRET is not set");
    const key = await crypto.subtle.importKey("raw", enc.encode(env.SESSION_SECRET), { name: "HMAC", hash: "SHA-256" }, false, ["sign", "verify"]);
    macKey = { secret: env.SESSION_SECRET, key };
  }
  return macKey.key;
}
const mac = async (env, text) => new Uint8Array(await crypto.subtle.sign("HMAC", await hmacKey(env), enc.encode(text)));
async function macOk(env, text, sig) {
  try {
    return await crypto.subtle.verify("HMAC", await hmacKey(env), unb64(sig), enc.encode(text));
  } catch {
    return false;
  }
}

async function readJson(req, max) {
  const len = Number(req.headers.get("content-length") || 0);
  if (len > max) return null;
  const text = await req.text();
  if (text.length > max) return null;
  try {
    const v = JSON.parse(text);
    return v && typeof v === "object" && !Array.isArray(v) ? v : null;
  } catch {
    return null;
  }
}

const hubOf = (env) => env.HUB.get(env.HUB.idFromName("hub"));
const serverIdFor = async (env, nonce, ts) => hex(await mac(env, `chal|${nonce}|${ts}`)).slice(0, 40);

async function challenge(env) {
  const nonce = randomHex(16);
  const ts = Date.now();
  return json({ nonce, ts, serverId: await serverIdFor(env, nonce, ts) });
}

async function login(req, env) {
  const b = await readJson(req, 512);
  if (!b || typeof b.name !== "string" || !/^[A-Za-z0-9_]{1,16}$/.test(b.name)
      || typeof b.nonce !== "string" || !/^[0-9a-f]{32}$/.test(b.nonce) || !Number.isSafeInteger(b.ts)) {
    return fail(400, "bad request");
  }
  if (Math.abs(Date.now() - b.ts) > CHALLENGE_MS) return fail(401, "The sign-in took too long. Try again.");
  const serverId = await serverIdFor(env, b.nonce, b.ts);
  const hub = hubOf(env);
  if (!(await hub.useNonce(b.nonce, b.ts + CHALLENGE_MS))) return fail(401, "That sign-in was already used.");
  const q = `?username=${encodeURIComponent(b.name)}&serverId=${serverId}`;
  const r = env.RELAY_URL && env.RELAY_KEY
    ? await fetch(env.RELAY_URL + q, { headers: { "X-Relay-Key": env.RELAY_KEY } })
    : await fetch(MOJANG + q);
  if (r.status !== 200) return fail(401, "Mojang did not confirm this account.");
  const p = await r.json().catch(() => null);
  if (!p || typeof p.id !== "string" || !/^[0-9a-f]{32}$/.test(p.id) || typeof p.name !== "string") {
    return fail(401, "Mojang did not confirm this account.");
  }
  const expires = Date.now() + TOKEN_MS;
  const body = b64u(enc.encode(JSON.stringify({ u: p.id, n: p.name, e: expires })));
  const token = `${body}.${b64u(await mac(env, `tok|${body}`))}`;
  await hub.register(p.id, p.name);
  return json({ token, uuid: p.id, name: p.name, expires });
}

async function authed(req, env) {
  const h = req.headers.get("authorization") || "";
  const m = /^Bearer ([A-Za-z0-9_-]{10,400})\.([A-Za-z0-9_-]{43})$/.exec(h);
  if (!m || !(await macOk(env, `tok|${m[1]}`, m[2]))) return null;
  try {
    const t = JSON.parse(new TextDecoder().decode(unb64(m[1])));
    return t && t.e > Date.now() && /^[0-9a-f]{32}$/.test(t.u) ? t : null;
  } catch {
    return null;
  }
}

let devKey;
async function devPublicKey(env) {
  devKey ??= await crypto.subtle.importKey("spki", unb64(env.DEV_KEY_SPKI), { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["verify"]);
  return devKey;
}

function proofMessage(challengeBytes, uuidHex, purpose) {
  const tag = enc.encode("pentecost-dev-proof-v1\0");
  const p = enc.encode(purpose);
  const out = new Uint8Array(tag.length + 4 + challengeBytes.length + 16 + 4 + p.length);
  const v = new DataView(out.buffer);
  let i = 0;
  out.set(tag, i); i += tag.length;
  v.setInt32(i, challengeBytes.length); i += 4;
  out.set(challengeBytes, i); i += challengeBytes.length;
  for (let k = 0; k < 16; k++) out[i + k] = parseInt(uuidHex.substr(k * 2, 2), 16);
  i += 16;
  v.setInt32(i, p.length); i += 4;
  out.set(p, i);
  return out;
}

async function devChallenge(env) {
  const c = randomHex(32);
  const ts = Date.now();
  return json({ challenge: c, ts, sig: b64u(await mac(env, `dev|${c}|${ts}`)) });
}

async function devProven(env, b, purpose) {
  if (!b || typeof b.challenge !== "string" || !/^[0-9a-f]{64}$/.test(b.challenge) || !Number.isSafeInteger(b.ts)
      || typeof b.sig !== "string" || typeof b.proof !== "string" || b.proof.length > 2048) return false;
  if (Math.abs(Date.now() - b.ts) > CHALLENGE_MS) return false;
  if (!(await macOk(env, `dev|${b.challenge}|${b.ts}`, b.sig))) return false;
  const bytes = Uint8Array.from(b.challenge.match(/../g), (x) => parseInt(x, 16));
  let ok = false;
  try {
    ok = await crypto.subtle.verify("RSASSA-PKCS1-v1_5", await devPublicKey(env), unb64(b.proof), proofMessage(bytes, env.OWNER, purpose));
  } catch {
    ok = false;
  }
  return ok && (await hubOf(env).useNonce(`dev:${b.challenge}`, b.ts + CHALLENGE_MS));
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    try {
      const route = `${req.method} ${url.pathname}`;
      switch (route) {
        case "GET /auth/challenge":
          return challenge(env);
        case "POST /auth/login":
          return login(req, env);
        case "GET /ws": {
          const who = await authed(req, env);
          if (!who) return fail(401, "Sign in again.");
          if ((req.headers.get("upgrade") || "").toLowerCase() !== "websocket") return fail(426, "WebSocket only.");
          const h = new Headers(req.headers);
          h.set("x-pentecost-uuid", who.u);
          h.set("x-pentecost-name", who.n);
          return hubOf(env).fetch(new Request(req, { headers: h }));
        }
        case "POST /crash": {
          const who = await authed(req, env);
          if (!who) return fail(401, "Sign in again.");
          const b = await readJson(req, 512);
          return json(await hubOf(env).crash(who.u, b));
        }
        case "GET /dev/challenge":
          return devChallenge(env);
        case "POST /dev/crashes": {
          const b = await readJson(req, 4096);
          if (!(await devProven(env, b, "crash-report"))) return fail(403, "Developer proof failed.");
          return json(await hubOf(env).crashes());
        }
        case "POST /dev/ping": {
          const b = await readJson(req, 4096);
          if (!(await devProven(env, b, "feed-ping"))) return fail(403, "Developer proof failed.");
          return json(await hubOf(env).broadcast({ t: "feed", seq: Number.isSafeInteger(b.seq) ? b.seq : 0 }));
        }
        case "GET /health":
          return json({ ok: true });
      }
      return env.ASSETS ? env.ASSETS.fetch(req) : fail(404, "Not found.");
    } catch (e) {
      return fail(500, "Server error.");
    }
  },
};

const MAX_SOCKETS = 4;
const MSG_PER_MIN = 60;
const MAX_FRIENDS = 300;
const MAX_PENDING = 50;
const MAX_MAIL = 100;
const STRIKES_PER_HOUR = 10;
const STATES = ["menu", "singleplayer", "multiplayer", "idle"];

const str = (v, max) => (typeof v === "string" && v.length <= max ? v : null);

function cleanStatus(s) {
  if (!s || typeof s !== "object") return null;
  const state = STATES.includes(s.state) ? s.state : "menu";
  const world = str(s.world, 64);
  const server = str(s.server, 128);
  return {
    state,
    world: world || null,
    server: server && /^[A-Za-z0-9.\-:\[\]]+$/.test(server) ? server : null,
    profile: str(s.profile, 64) || null,
  };
}

function normalizeCode(c) {
  if (typeof c !== "string" || c.length > 20) return null;
  const s = c.toUpperCase().replace(/[\s-]/g, "").replace(/O/g, "0").replace(/[IL]/g, "1");
  return /^[0-9A-HJKMNP-TV-Z]{10}$/.test(s) ? s : null;
}

export class Hub extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.sql = ctx.storage.sql;
    this.sql.exec(`
      CREATE TABLE IF NOT EXISTS users(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, code TEXT UNIQUE, allow_join INTEGER NOT NULL DEFAULT 0, seen INTEGER);
      CREATE TABLE IF NOT EXISTS friends(a TEXT NOT NULL, b TEXT NOT NULL, since INTEGER, PRIMARY KEY(a, b));
      CREATE TABLE IF NOT EXISTS requests(src TEXT NOT NULL, dst TEXT NOT NULL, ts INTEGER, PRIMARY KEY(src, dst));
      CREATE INDEX IF NOT EXISTS requests_dst ON requests(dst);
      CREATE TABLE IF NOT EXISTS nonces(n TEXT PRIMARY KEY, exp INTEGER NOT NULL);
      CREATE TABLE IF NOT EXISTS strikes(uuid TEXT NOT NULL, ts INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS strikes_uuid ON strikes(uuid);
      CREATE TABLE IF NOT EXISTS mail(id INTEGER PRIMARY KEY AUTOINCREMENT, dst TEXT NOT NULL, src TEXT NOT NULL, kind TEXT NOT NULL, body TEXT NOT NULL, ts INTEGER NOT NULL);
      CREATE INDEX IF NOT EXISTS mail_dst ON mail(dst);
      CREATE TABLE IF NOT EXISTS crashes(mod TEXT NOT NULL, version TEXT NOT NULL, kind TEXT NOT NULL, n INTEGER NOT NULL, last INTEGER, PRIMARY KEY(mod, version, kind));
      CREATE TABLE IF NOT EXISTS crash_rl(uuid TEXT NOT NULL, mod TEXT NOT NULL, ts INTEGER NOT NULL, PRIMARY KEY(uuid, mod));
    `);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
  }

  row(q, ...args) {
    return this.sql.exec(q, ...args).toArray()[0];
  }
  rows(q, ...args) {
    return this.sql.exec(q, ...args).toArray();
  }

  useNonce(n, exp) {
    this.sql.exec("DELETE FROM nonces WHERE exp < ?", Date.now());
    if (this.row("SELECT 1 AS x FROM nonces WHERE n = ?", n)) return false;
    this.sql.exec("INSERT INTO nonces(n, exp) VALUES(?, ?)", n, exp);
    return true;
  }

  register(uuid, name) {
    const u = this.row("SELECT name, code FROM users WHERE uuid = ?", uuid);
    if (!u) {
      this.sql.exec("INSERT INTO users(uuid, name, code, seen) VALUES(?, ?, ?, ?)", uuid, name, this.freshCode(), Date.now());
    } else if (u.name !== name || !u.code) {
      this.sql.exec("UPDATE users SET name = ?, code = COALESCE(code, ?), seen = ? WHERE uuid = ?", name, this.freshCode(), Date.now(), uuid);
    }
  }

  crash(uuid, b) {
    const mod = str(b?.mod, 64), version = str(b?.version, 64), kind = str(b?.kind, 96);
    const ok = /^[\w.+\-: ]+$/;
    if (!mod || !version || !kind || !ok.test(mod) || !ok.test(version) || !ok.test(kind)) return { ok: false };
    const now = Date.now();
    const last = this.row("SELECT ts FROM crash_rl WHERE uuid = ? AND mod = ?", uuid, mod);
    if (last && now - last.ts < 3600000) return { ok: true };
    this.sql.exec("INSERT INTO crash_rl(uuid, mod, ts) VALUES(?, ?, ?) ON CONFLICT(uuid, mod) DO UPDATE SET ts = excluded.ts", uuid, mod, now);
    this.sql.exec("INSERT INTO crashes(mod, version, kind, n, last) VALUES(?, ?, ?, 1, ?) ON CONFLICT(mod, version, kind) DO UPDATE SET n = n + 1, last = excluded.last", mod, version, kind, now);
    return { ok: true };
  }

  broadcast(msg) {
    const text = JSON.stringify(msg);
    let n = 0;
    for (const s of this.ctx.getWebSockets()) {
      try { s.send(text); n++; } catch {}
    }
    return { ok: true, sent: n };
  }

  crashes() {
    return { crashes: this.rows("SELECT mod, version, kind, n, last FROM crashes ORDER BY n DESC LIMIT 300") };
  }

  async fetch(req) {
    const uuid = req.headers.get("x-pentecost-uuid");
    const name = req.headers.get("x-pentecost-name");
    if (!uuid || !name) return new Response("forbidden", { status: 403 });
    const open = this.ctx.getWebSockets(uuid);
    if (open.length >= MAX_SOCKETS) {
      try { open[0].close(4000, "Signed in somewhere else."); } catch {}
    }
    this.register(uuid, name);
    const kind = new URL(req.url).searchParams.get("client") === "game" ? "game" : "manager";
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [uuid]);
    server.serializeAttachment({ uuid, name, kind, status: null, win: Date.now(), cnt: 0 });
    this.sendState(server, uuid);
    this.announce(uuid);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, data) {
    if (typeof data !== "string" || data.length > 70000) {
      ws.close(1009, "Too big.");
      return;
    }
    const a = ws.deserializeAttachment();
    const now = Date.now();
    if (now - a.win > 60000) { a.win = now; a.cnt = 0; }
    if (++a.cnt > MSG_PER_MIN) {
      ws.close(1008, "Slow down.");
      return;
    }
    ws.serializeAttachment(a);
    let m;
    try { m = JSON.parse(data); } catch { return; }
    if (!m || typeof m.t !== "string") return;
    const me = a.uuid;
    const reply = (o) => this.send(ws, { ...o, re: m.id ?? null });
    switch (m.t) {
      case "status":
        a.status = cleanStatus(m.s);
        ws.serializeAttachment(a);
        this.announce(me);
        return;
      case "allowJoin":
        this.sql.exec("UPDATE users SET allow_join = ? WHERE uuid = ?", m.v ? 1 : 0, me);
        this.announce(me);
        return reply({ t: "ok" });
      case "refresh":
        return this.sendState(ws, me);
      case "newCode": {
        const code = this.freshCode();
        this.sql.exec("UPDATE users SET code = ? WHERE uuid = ?", code, me);
        return this.toUser(me, { t: "code", code });
      }
      case "add":
        return reply(this.add(me, a.name, m.code));
      case "accept": {
        if (!this.row("SELECT 1 AS x FROM requests WHERE src = ? AND dst = ?", m.uuid, me)) return reply({ t: "error", error: "That request is gone." });
        if (this.friendCount(me) >= MAX_FRIENDS || this.friendCount(m.uuid) >= MAX_FRIENDS) return reply({ t: "error", error: "Friends list is full." });
        this.befriend(me, m.uuid);
        return reply({ t: "ok" });
      }
      case "decline":
        this.sql.exec("DELETE FROM requests WHERE src = ? AND dst = ?", m.uuid, me);
        this.toUser(me, { t: "requestGone", uuid: m.uuid });
        this.toUser(m.uuid, { t: "requestGone", uuid: me });
        return reply({ t: "ok" });
      case "cancel":
        this.sql.exec("DELETE FROM requests WHERE src = ? AND dst = ?", me, m.uuid);
        this.toUser(me, { t: "requestGone", uuid: m.uuid });
        this.toUser(m.uuid, { t: "requestGone", uuid: me });
        return reply({ t: "ok" });
      case "remove":
        this.sql.exec("DELETE FROM friends WHERE (a = ? AND b = ?) OR (a = ? AND b = ?)", me, m.uuid, m.uuid, me);
        this.toUser(me, { t: "unfriend", uuid: m.uuid });
        this.toUser(m.uuid, { t: "unfriend", uuid: me });
        return reply({ t: "ok" });
      case "msg": {
        const text = str(m.text, 500);
        if (!text || !text.trim()) return reply({ t: "error", error: "Empty message." });
        return reply(this.deliver(me, m.to, "msg", { text }));
      }
      case "share": {
        if (!m.pack || typeof m.pack !== "object") return reply({ t: "error", error: "Nothing to share." });
        const body = JSON.stringify({ name: str(m.name, 64) || "Profile", pack: m.pack });
        if (body.length > 64000) return reply({ t: "error", error: "That profile is too big to share." });
        return reply(this.deliver(me, m.to, "share", JSON.parse(body)));
      }
      case "ack": {
        const ids = Array.isArray(m.ids) ? m.ids.filter(Number.isSafeInteger).slice(0, 200) : [];
        for (const id of ids) this.sql.exec("DELETE FROM mail WHERE id = ? AND dst = ?", id, me);
        return;
      }
    }
  }

  async webSocketClose(ws, code) {
    try { ws.close(1000, "Bye."); } catch {}
    this.announce(ws.deserializeAttachment()?.uuid, ws);
  }

  async webSocketError(ws) {
    this.announce(ws.deserializeAttachment()?.uuid, ws);
  }

  add(me, myName, raw) {
    const now = Date.now();
    this.sql.exec("DELETE FROM strikes WHERE ts < ?", now - 3600000);
    if (this.row("SELECT COUNT(*) AS n FROM strikes WHERE uuid = ?", me).n >= STRIKES_PER_HOUR) {
      return { t: "error", error: "Too many wrong codes. Try again in an hour." };
    }
    const code = normalizeCode(raw);
    const target = code && this.row("SELECT uuid, name FROM users WHERE code = ?", code);
    if (!target) {
      this.sql.exec("INSERT INTO strikes(uuid, ts) VALUES(?, ?)", me, now);
      return { t: "error", error: "No player has that code." };
    }
    if (target.uuid === me) return { t: "error", error: "That's your own code." };
    if (this.isFriend(me, target.uuid)) return { t: "error", error: `You and ${target.name} are already friends.` };
    if (this.row("SELECT 1 AS x FROM requests WHERE src = ? AND dst = ?", target.uuid, me)) {
      this.befriend(me, target.uuid);
      return { t: "ok", friend: target.name };
    }
    if (this.row("SELECT 1 AS x FROM requests WHERE src = ? AND dst = ?", me, target.uuid)) {
      return { t: "error", error: `You already asked ${target.name}.` };
    }
    if (this.row("SELECT COUNT(*) AS n FROM requests WHERE src = ?", me).n >= MAX_PENDING) {
      return { t: "error", error: "Too many requests waiting. Cancel some first." };
    }
    this.sql.exec("INSERT INTO requests(src, dst, ts) VALUES(?, ?, ?)", me, target.uuid, now);
    this.toUser(target.uuid, { t: "request", from: { uuid: me, name: myName, ts: now } });
    this.toUser(me, { t: "requestSent", to: { uuid: target.uuid, name: target.name, ts: now } });
    return { t: "ok", sent: target.name };
  }

  befriend(x, y) {
    const now = Date.now();
    this.sql.exec("DELETE FROM requests WHERE (src = ? AND dst = ?) OR (src = ? AND dst = ?)", x, y, y, x);
    this.sql.exec("INSERT OR IGNORE INTO friends(a, b, since) VALUES(?, ?, ?)", x, y, now);
    this.sql.exec("INSERT OR IGNORE INTO friends(a, b, since) VALUES(?, ?, ?)", y, x, now);
    this.toUser(x, { t: "requestGone", uuid: y });
    this.toUser(y, { t: "requestGone", uuid: x });
    this.toUser(x, { t: "friend", friend: this.entry(y, x) });
    this.toUser(y, { t: "friend", friend: this.entry(x, y) });
  }

  isFriend(x, y) {
    return !!this.row("SELECT 1 AS x FROM friends WHERE a = ? AND b = ?", x, y);
  }
  friendCount(x) {
    return this.row("SELECT COUNT(*) AS n FROM friends WHERE a = ?", x).n;
  }

  deliver(me, to, kind, body) {
    if (typeof to !== "string" || !this.isFriend(me, to)) return { t: "error", error: "You can only send to friends." };
    const ts = Date.now();
    const live = this.open(to);
    if (live.length) {
      for (const s of live) this.send(s, { t: kind, from: me, ts, ...body });
      return { t: "ok", delivered: true };
    }
    if (this.row("SELECT COUNT(*) AS n FROM mail WHERE dst = ?", to).n >= MAX_MAIL) {
      return { t: "error", error: "Their inbox is full. Try when they're online." };
    }
    this.sql.exec("INSERT INTO mail(dst, src, kind, body, ts) VALUES(?, ?, ?, ?, ?)", to, me, kind, JSON.stringify(body), ts);
    return { t: "ok", delivered: false };
  }

  open(uuid, except) {
    return uuid ? this.ctx.getWebSockets(uuid).filter((s) => s !== except && s.readyState === WebSocket.OPEN) : [];
  }

  entry(uuid, viewer) {
    const u = this.row("SELECT uuid, name, allow_join FROM users WHERE uuid = ?", uuid);
    const p = this.presence(uuid, u?.allow_join);
    return { uuid, name: u?.name ?? "?", ...p };
  }

  presence(uuid, allowJoin, except) {
    const socks = this.open(uuid, except);
    if (!socks.length) return { online: false, status: null };
    let best = null;
    for (const s of socks) {
      const a = s.deserializeAttachment();
      if (a?.status && (!best || a.kind === "game")) best = a.status;
    }
    if (best && !allowJoin) best = { ...best, server: null };
    return { online: true, status: best };
  }

  announce(uuid, except) {
    if (!uuid) return;
    const u = this.row("SELECT name, allow_join FROM users WHERE uuid = ?", uuid);
    if (!u) return;
    const msg = { t: "presence", uuid, name: u.name, ...this.presence(uuid, u.allow_join, except) };
    for (const f of this.rows("SELECT b FROM friends WHERE a = ?", uuid)) {
      for (const s of this.open(f.b)) this.send(s, msg);
    }
  }

  sendState(ws, me) {
    const u = this.row("SELECT uuid, name, code, allow_join FROM users WHERE uuid = ?", me);
    const friends = this.rows("SELECT b FROM friends WHERE a = ?", me).map((f) => this.entry(f.b, me));
    const incoming = this.rows("SELECT r.src AS uuid, u.name, r.ts FROM requests r JOIN users u ON u.uuid = r.src WHERE r.dst = ?", me);
    const outgoing = this.rows("SELECT r.dst AS uuid, u.name, r.ts FROM requests r JOIN users u ON u.uuid = r.dst WHERE r.src = ?", me);
    const mail = this.rows("SELECT id, src AS 'from', kind, body, ts FROM mail WHERE dst = ? ORDER BY id LIMIT ?", me, MAX_MAIL)
      .map((x) => ({ id: x.id, t: x.kind, from: x.from, ts: x.ts, ...JSON.parse(x.body) }));
    this.send(ws, { t: "state", me: { uuid: u.uuid, name: u.name, code: u.code, allowJoin: !!u.allow_join }, friends, incoming, outgoing, mail });
  }

  toUser(uuid, msg) {
    for (const s of this.open(uuid)) this.send(s, msg);
  }

  send(ws, msg) {
    try { ws.send(JSON.stringify(msg)); } catch {}
  }

  freshCode() {
    for (;;) {
      const b = crypto.getRandomValues(new Uint8Array(10));
      let c = "";
      for (const x of b) c += CODE_ABC[x & 31];
      if (!this.row("SELECT 1 AS x FROM users WHERE code = ?", c)) return c;
    }
  }
}
