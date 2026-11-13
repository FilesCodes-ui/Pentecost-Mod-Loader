/**
 * WWLS rendezvous. Deploy once as a Google Apps Script web app.
 * It holds a room code and the host's address for a few minutes, so a friend can find the host. No game traffic passes through it.
 *
 *   host:   GET ?a=open&code=ABCD2345&addr=1.2.3.4:25565&ttl=3600
 *   friend: GET ?a=find&code=ABCD2345        ->  {"ok":true,"addr":"1.2.3.4:25565"}
 *   host:   GET ?a=close&code=ABCD2345
 *
 * Nothing is kept longer than the ttl (at most 6 hours, the limit of CacheService).
 */
function doGet(e) {
  var p = e.parameter || {};
  var code = String(p.code || '').toUpperCase();
  var cache = CacheService.getScriptCache();
  if (!/^[A-Z2-9]{6,12}$/.test(code)) {
    return out({ ok: false, error: 'bad code' });
  }
  if (p.a === 'open') {
    var addr = String(p.addr || '');
    if (!/^[0-9a-fA-F:.\[\]]{3,60}:[0-9]{2,5}$/.test(addr) && !/^[a-zA-Z0-9.-]{3,80}:[0-9]{2,5}$/.test(addr)) {
      return out({ ok: false, error: 'bad address' });
    }
    var ttl = Math.max(60, Math.min(21600, parseInt(p.ttl || '3600', 10) || 3600));
    cache.put('room:' + code, addr, ttl);
    return out({ ok: true });
  }
  if (p.a === 'find') {
    var found = cache.get('room:' + code);
    return found ? out({ ok: true, addr: found }) : out({ ok: false, error: 'not found' });
  }
  if (p.a === 'close') {
    cache.remove('room:' + code);
    return out({ ok: true });
  }
  return out({ ok: false, error: 'unknown action' });
}

function out(obj) {
  return ContentService.createTextOutput(JSON.stringify(obj)).setMimeType(ContentService.MimeType.JSON);
}
