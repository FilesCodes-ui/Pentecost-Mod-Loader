import { createHmac } from "node:crypto";

const SECRET = "local-test-secret-local-test-secret-0000";
const BASE = "http://127.0.0.1:8787";
const b64u = (b) => Buffer.from(b).toString("base64url");
const token = (u, n) => {
  const body = b64u(JSON.stringify({ u, n, e: Date.now() + 3600000 }));
  return `${body}.${b64u(createHmac("sha256", SECRET).update(`tok|${body}`).digest())}`;
};

function client(uuid, name) {
  const ws = new WebSocket(`${BASE.replace("http", "ws")}/ws`, { headers: { Authorization: `Bearer ${token(uuid, name)}` } });
  const inbox = [];
  const waiters = [];
  ws.onmessage = (e) => {
    const m = JSON.parse(e.data);
    inbox.push(m);
    for (const w of [...waiters]) if (w.pred(m)) { waiters.splice(waiters.indexOf(w), 1); w.res(m); }
  };
  const wait = (pred, ms = 3000) => {
    const hit = inbox.find(pred);
    if (hit) { inbox.splice(inbox.indexOf(hit), 1); return Promise.resolve(hit); }
    return new Promise((res, rej) => { waiters.push({ pred, res }); setTimeout(() => rej(new Error(`${name} timed out`)), ms); });
  };
  const open = new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  return { ws, wait, open, send: (o) => ws.send(JSON.stringify(o)) };
}

const ok = (c, msg) => { if (!c) { console.log("FAIL", msg); process.exitCode = 1; } else console.log("ok  ", msg); };

const rnd = () => [...crypto.getRandomValues(new Uint8Array(16))].map((x) => x.toString(16).padStart(2, "0")).join("");
const UA = rnd(), UB = rnd();
const A = client(UA, "Alice");
const B = client(UB, "Bob");
await Promise.all([A.open, B.open]);
const sa = await A.wait((m) => m.t === "state");
const sb = await B.wait((m) => m.t === "state");
ok(/^[0-9A-Z]{10}$/.test(sb.me.code), "Bob has a friend code");

A.send({ t: "add", code: "ZZZZZZZZZZ", id: 1 });
ok((await A.wait((m) => m.re === 1)).t === "error", "wrong code refused");

A.send({ t: "add", code: sb.me.code.slice(0, 5) + "-" + sb.me.code.slice(5).toLowerCase(), id: 2 });
ok((await A.wait((m) => m.re === 2)).t === "ok", "code with dash and lowercase works");
const req = await B.wait((m) => m.t === "request");
ok(req.from.name === "Alice", "Bob sees Alice's request");

B.send({ t: "msg", to: sa.me.uuid, text: "hi", id: 3 });
ok((await B.wait((m) => m.re === 3)).t === "error", "no messages before accepting");

B.send({ t: "accept", uuid: sa.me.uuid, id: 4 });
await B.wait((m) => m.re === 4);
ok((await A.wait((m) => m.t === "friend")).friend.name === "Bob", "Alice gets Bob as friend");

B.send({ t: "allowJoin", v: false });
B.send({ t: "status", s: { state: "multiplayer", server: "play.example.com:25565", world: "Survival" } });
const p1 = await A.wait((m) => m.t === "presence" && m.status?.state === "multiplayer");
ok(p1.status.server === null, "server address hidden without allow join");
B.send({ t: "allowJoin", v: true, id: 5 });
const p2 = await A.wait((m) => m.t === "presence" && m.status?.server);
ok(p2.status.server === "play.example.com:25565", "server address shown with allow join");

A.send({ t: "msg", to: sb.me.uuid, text: "hello bob", id: 6 });
ok((await B.wait((m) => m.t === "msg")).text === "hello bob", "live message arrives");

A.send({ t: "share", to: sb.me.uuid, name: "My pack", pack: { mods: [{ sha1: "abc" }] }, id: 7 });
ok((await B.wait((m) => m.t === "share")).pack.mods.length === 1, "profile share arrives");

B.ws.close();
ok((await A.wait((m) => m.t === "presence" && m.online === false)).uuid === sb.me.uuid, "Alice sees Bob go offline");

A.send({ t: "msg", to: sb.me.uuid, text: "while away", id: 8 });
ok((await A.wait((m) => m.re === 8)).delivered === false, "offline message stored");
const B2 = client(UB, "Bob");
await B2.open;
const s2 = await B2.wait((m) => m.t === "state");
ok(s2.mail.some((x) => x.text === "while away"), "Bob gets it on return");
ok(s2.friends.some((f) => f.name === "Alice" && f.online), "Bob sees Alice online");

for (let i = 0; i < 12; i++) A.send({ t: "add", code: "QQQQQQQQQ" + (i % 10), id: 100 + i });
const last = await A.wait((m) => m.re === 111);
ok(/Too many/.test(last.error || ""), "guessing codes gets locked out");

const bad = await fetch(`${BASE}/ws`, { headers: { Authorization: `Bearer ${token("cccccccccccccccccccccccccccccccc", "Eve").slice(0, -2)}xx` } });
ok(bad.status === 401, "forged token refused");

A.send({ t: "remove", uuid: sb.me.uuid, id: 9 });
ok((await B2.wait((m) => m.t === "unfriend")).uuid === sa.me.uuid, "unfriend reaches Bob");

A.ws.close(); B2.ws.close();
setTimeout(() => process.exit(), 300);
