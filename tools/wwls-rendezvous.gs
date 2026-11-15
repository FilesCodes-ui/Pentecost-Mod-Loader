/**
 * WWLS rendezvous (hardened, version 3).
 * Holds a room code and the host's public address for a few minutes, so a friend can find the host.
 * No game traffic passes through it.
 *
 *   host:    GET ?a=open&code=ABCDEFGH23&key=<64 hex>&addr=1.2.3.4:25565&ttl=900
 *   friend:  GET ?a=find&code=ABCDEFGH23              ->  {"ok":true,"addr":"1.2.3.4:25565"}
 *   host:    GET ?a=close&code=ABCDEFGH23&key=<64 hex>
 *   anyone:  GET ?a=version&code=ABCDEFGH23           ->  {"ok":true,"v":3}   (to check a deployment went live)
 *
 * Protections:
 *  - The host sends a random secret (key) when it opens a room. Only its SHA-256 hash is stored.
 *    Renewing or closing a room needs the same key, so nobody else can overwrite or close it.
 *  - Codes are exactly 10 characters from the game's alphabet (about 50 bits). With that many possible codes,
 *    guessing a live room is not practical, so lookups are NOT locked out after wrong guesses. (Version 2 paused
 *    every lookup after 30 misses a minute. Because Apps Script cannot see who is calling, one person sending
 *    wrong guesses could stop everybody from joining. That lockout was removed.)
 *  - Lookups take no lock, so a flood of them cannot slow down hosts who are opening or renewing rooms.
 *  - Addresses must be a public IPv4 address with a port from 1024 to 65535. No hostnames, no private ranges,
 *    so a room can never point a friend at a machine inside their own network or at a made-up host name.
 *  - Opening rooms is capped for the whole service per minute, so the cache cannot be filled with junk rooms.
 *  - Rooms last 15 minutes unless the host's game renews them (it does, every 5 minutes, while the world runs).
 *  - This script writes no logs and stores nothing but the room (address and key hash) until it expires.
 *    Google itself still records that the web app was called, as it does for any Google service.
 *
 * Deploy: Deploy > Manage deployments > edit the existing web app > Version: New version > Deploy.
 * Keep "Execute as: Me" and "Who has access: Anyone". The web app URL stays the same.
 */

var VERSION = 3;
var CODE_RE = /^[A-HJ-NP-Z2-9]{10}$/;
var KEY_RE = /^[0-9a-f]{64}$/;
var ADDR_RE = /^([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3})\.([0-9]{1,3}):([0-9]{1,5})$/;
var MAX_TTL = 1200;
var DEFAULT_TTL = 900;
var MAX_OPENS_PER_MINUTE = 120;

function doGet(e) {
  var p = (e && e.parameter) || {};
  var action = String(p.a || '');

  if (action === 'version') {
    return out({ ok: true, v: VERSION });
  }

  var code = String(p.code || '').toUpperCase();
  if (!CODE_RE.test(code)) {
    return out({ ok: false, error: 'bad code' });
  }
  var cache = CacheService.getScriptCache();
  var roomKey = 'room:' + code;

  if (action === 'find') {
    var raw = cache.get(roomKey);
    if (!raw) {
      return out({ ok: false, error: 'not found' });
    }
    return out({ ok: true, addr: JSON.parse(raw).a });
  }

  var key = String(p.key || '').toLowerCase();
  if (!KEY_RE.test(key)) {
    return out({ ok: false, error: 'bad key' });
  }
  var hash = sha256(key);

  if (action === 'open') {
    var addr = String(p.addr || '');
    if (!publicAddress(addr)) {
      return out({ ok: false, error: 'bad address' });
    }
    var ttl = Math.max(60, Math.min(MAX_TTL, parseInt(p.ttl || String(DEFAULT_TTL), 10) || DEFAULT_TTL));
    var lock = LockService.getScriptLock();
    if (!lock.tryLock(5000)) {
      return out({ ok: false, error: 'busy' });
    }
    try {
      if (count(cache, 'opens') >= MAX_OPENS_PER_MINUTE) {
        return out({ ok: false, error: 'busy' });
      }
      var existing = cache.get(roomKey);
      if (existing && JSON.parse(existing).k !== hash) {
        return out({ ok: false, error: 'forbidden' });
      }
      cache.put('n:opens', String(count(cache, 'opens') + 1), 60);
      cache.put(roomKey, JSON.stringify({ a: addr, k: hash }), ttl);
    } finally {
      lock.releaseLock();
    }
    return out({ ok: true });
  }

  if (action === 'close') {
    var current = cache.get(roomKey);
    if (current && JSON.parse(current).k !== hash) {
      return out({ ok: false, error: 'forbidden' });
    }
    cache.remove(roomKey);
    return out({ ok: true });
  }

  return out({ ok: false, error: 'unknown action' });
}

/** A public IPv4 address and a port from 1024 to 65535. */
function publicAddress(addr) {
  var m = ADDR_RE.exec(addr);
  if (!m) {
    return false;
  }
  var o = [parseInt(m[1], 10), parseInt(m[2], 10), parseInt(m[3], 10), parseInt(m[4], 10)];
  var port = parseInt(m[5], 10);
  for (var i = 0; i < 4; i++) {
    if (o[i] > 255) {
      return false;
    }
  }
  if (port < 1024 || port > 65535) {
    return false;
  }
  var a = o[0], b = o[1];
  if (a === 0 || a === 10 || a === 127 || a >= 224) return false;
  if (a === 100 && b >= 64 && b <= 127) return false;
  if (a === 169 && b === 254) return false;
  if (a === 172 && b >= 16 && b <= 31) return false;
  if (a === 192 && b === 168) return false;
  if (a === 192 && b === 0 && o[2] === 0) return false;
  if (a === 198 && (b === 18 || b === 19)) return false;
  return true;
}

function count(cache, name) {
  return parseInt(cache.get('n:' + name) || '0', 10) || 0;
}

function sha256(text) {
  var bytes = Utilities.computeDigest(Utilities.DigestAlgorithm.SHA_256, text, Utilities.Charset.UTF_8);
  return bytes.map(function (b) {
    return ('0' + (b & 0xff).toString(16)).slice(-2);
  }).join('');
}

function out(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
