package ir.vibracenter.hamseda

/**
 * JavaScript injected into the Hamseda site only (document start, origin restricted):
 *  1. tracks WebRTC connections / live microphone streams so the app knows when you are inside a room
 *  2. replaces the web Notification API (not available in WebView) with real Android notifications,
 *     and offers window.HamsedaApp.notify({...}) so the site can send notifications / room invitations explicitly
 *  3. reports the colours of the site's current theme (light / dark) so the phone's status bar,
 *     bottom bar and notifications can follow it
 */
object Scripts {
    const val BRIDGE = """
(function () {
  if (window.__hamseda) { return; }
  var H = { pcs: [], streams: [] };
  try { Object.defineProperty(window, '__hamseda', { value: H, configurable: false }); } catch (e) { return; }

  function post(o) {
    try { if (window.HamsedaNative) { window.HamsedaNative.postMessage(JSON.stringify(o)); } } catch (e) {}
  }
  function str(v, n) { return v ? String(v).substring(0, n) : ''; }

  // ---------------- room detection ----------------
  try {
    var NativePC = window.RTCPeerConnection || window.webkitRTCPeerConnection;
    if (NativePC && !NativePC.__hamsedaWrapped) {
      var WrappedPC = function () {
        var pc = Reflect.construct(NativePC, arguments, new.target || WrappedPC);
        H.pcs.push(pc);
        return pc;
      };
      WrappedPC.prototype = NativePC.prototype;
      try { Object.setPrototypeOf(WrappedPC, NativePC); } catch (e) {}
      WrappedPC.__hamsedaWrapped = true;
      window.RTCPeerConnection = WrappedPC;
      if (window.webkitRTCPeerConnection) { window.webkitRTCPeerConnection = WrappedPC; }
    }
  } catch (e) {}

  try {
    var md = navigator.mediaDevices;
    if (md && md.getUserMedia) {
      var origGUM = md.getUserMedia.bind(md);
      md.getUserMedia = function (c) {
        return origGUM(c).then(function (s) { H.streams.push(s); return s; });
      };
    }
  } catch (e) {}

  H.inRoom = function () {
    var i, j, st;
    var keep = [];
    for (i = 0; i < H.pcs.length; i++) {
      var pc = H.pcs[i];
      if (pc.signalingState === 'closed' || pc.connectionState === 'closed') { continue; }
      keep.push(pc);
    }
    H.pcs = keep;
    for (i = 0; i < keep.length; i++) {
      st = keep[i].connectionState || keep[i].iceConnectionState;
      if (st === 'connected' || st === 'connecting' || st === 'completed' ||
          st === 'checking' || st === 'disconnected') { return true; }
    }
    var live = [];
    for (i = 0; i < H.streams.length; i++) {
      var tracks = H.streams[i].getTracks();
      for (j = 0; j < tracks.length; j++) {
        if (tracks[j].readyState === 'live') { live.push(H.streams[i]); break; }
      }
    }
    H.streams = live;
    return live.length > 0;
  };

  // ---------------- notifications ----------------
  function send(title, o) {
    o = o || {};
    var d = o.data;
    if (!d || typeof d !== 'object') { d = {}; }
    post({
      t: 'notify',
      title: str(title, 100),
      body: str(o.body, 300),
      tag: str(o.tag, 64),
      icon: str(o.icon, 500),
      url: str(o.url || d.url || d.link || d.href, 500),
      type: str(o.type || d.type, 20)
    });
  }

  function N(title, opts) {
    opts = opts || {};
    this.title = str(title, 100);
    this.body = str(opts.body, 300);
    this.tag = str(opts.tag, 64);
    this.data = opts.data;
    this.onclick = null; this.onclose = null; this.onerror = null; this.onshow = null;
    send(title, opts);
    var self = this;
    setTimeout(function () {
      try { if (typeof self.onshow === 'function') { self.onshow(new Event('show')); } } catch (e) {}
    }, 0);
  }
  N.prototype.close = function () { post({ t: 'close', tag: this.tag }); };
  N.prototype.addEventListener = function () {};
  N.prototype.removeEventListener = function () {};
  N.prototype.dispatchEvent = function () { return true; };
  N.requestPermission = function (cb) {
    if (typeof cb === 'function') { cb('granted'); }
    return Promise.resolve('granted');
  };
  Object.defineProperty(N, 'permission', { get: function () { return 'granted'; } });
  N.maxActions = 0;
  try {
    Object.defineProperty(window, 'Notification', { value: N, writable: true, configurable: true });
  } catch (e) { window.Notification = N; }

  try {
    if (window.ServiceWorkerRegistration && ServiceWorkerRegistration.prototype) {
      ServiceWorkerRegistration.prototype.showNotification = function (title, opts) {
        send(title, opts);
        return Promise.resolve();
      };
      ServiceWorkerRegistration.prototype.getNotifications = function () { return Promise.resolve([]); };
    }
  } catch (e) {}

  // For the site's own code:  if (window.HamsedaApp) HamsedaApp.notify({ title, body, tag, url, icon, type: 'invite' })
  // type 'invite' makes the phone ring.
  try {
    var App = {
      isApp: true,
      version: 2,
      notify: function (o) { o = o || {}; send(o.title, o); },
      ring: function (o) { o = o || {}; o.type = 'invite'; send(o.title, o); },
      billing: 'bazaar',
      // HamsedaApp.buy({ sku:'vip_month', type:'subscription' | 'inapp', payload:'user-12', consume:false })
      // result: window.addEventListener('hamseda-purchase', e => e.detail)  (or window.onHamsedaPurchase)
      buy: function (o) {
        o = o || {};
        post({ t: 'buy', sku: str(o.sku, 64), type: o.type === 'subscription' ? 'subscription' : 'inapp',
               payload: str(o.payload, 200), consume: !!o.consume });
      },
      restore: function () { post({ t: 'restore' }); },
      consume: function (token) { post({ t: 'consume', token: str(token, 512) }); }
    };
    Object.defineProperty(window, 'HamsedaApp', { value: Object.freeze(App) });
  } catch (e) {}

  // ---------------- theme colours ----------------
  var cv = null, cx = null;
  function toRGBA(s) {
    if (!s) { return null; }
    try {
      if (!cx) {
        cv = document.createElement('canvas');
        cv.width = 1; cv.height = 1;
        cx = cv.getContext('2d', { willReadFrequently: true });
      }
      cx.clearRect(0, 0, 1, 1);
      cx.fillStyle = '#000000';
      cx.fillStyle = s;
      cx.fillRect(0, 0, 1, 1);
      var d = cx.getImageData(0, 0, 1, 1).data;
      return { r: d[0], g: d[1], b: d[2], a: d[3] / 255 };
    } catch (e) { return null; }
  }
  function hex(c) {
    function h(n) { var s = n.toString(16); return s.length < 2 ? '0' + s : s; }
    return '#' + h(c.r) + h(c.g) + h(c.b);
  }
  function solidBgOf(el) {
    while (el && el.nodeType === 1) {
      var c = toRGBA(getComputedStyle(el).backgroundColor);
      if (c && c.a >= 0.95) { return c; }
      el = el.parentElement;
    }
    return null;
  }
  function solidBgAt(x, y) {
    var el = null;
    try { el = document.elementFromPoint(x, y); } catch (e) {}
    return solidBgOf(el);
  }
  function metaTheme() {
    var ms = document.querySelectorAll('meta[name="theme-color"]'), pick = null, i;
    for (i = 0; i < ms.length; i++) {
      var media = ms[i].getAttribute('media');
      if (media) {
        try { if (window.matchMedia(media).matches) { pick = ms[i]; break; } } catch (e) {}
      } else if (!pick) { pick = ms[i]; }
    }
    return pick ? toRGBA(pick.getAttribute('content')) : null;
  }
  function accentColor() {
    var names = ['--primary', '--accent', '--brand', '--color-primary', '--primary-color', '--main-color', '--theme-color'];
    var cs = getComputedStyle(document.documentElement), i, c;
    for (i = 0; i < names.length; i++) {
      c = toRGBA(cs.getPropertyValue(names[i]).trim());
      if (c && c.a >= 0.95) { return c; }
    }
    return metaTheme();
  }

  var last = '';
  function report() {
    try {
      if (!document.body) { return; }
      var w = window.innerWidth || 360, h = window.innerHeight || 640;
      var dark = !!(window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches);
      var fb = metaTheme() || (dark ? { r: 10, g: 16, b: 30, a: 1 } : { r: 255, g: 255, b: 255, a: 1 });
      var bg = solidBgOf(document.body) || solidBgOf(document.documentElement) || fb;
      var top = solidBgAt(w / 2, 2) || bg;
      var bottom = solidBgAt(w / 2, h - 3) || bg;
      var accent = accentColor();
      var msg = { t: 'theme', bg: hex(bg), top: hex(top), bottom: hex(bottom), accent: accent ? hex(accent) : '' };
      var key = JSON.stringify(msg);
      if (key !== last) { last = key; post(msg); }
    } catch (e) {}
  }

  var timer = null;
  function schedule() {
    if (timer) { clearTimeout(timer); }
    timer = setTimeout(report, 150);
  }

  function start() {
    schedule();
    try {
      var mo = new MutationObserver(schedule);
      var attrs = ['class', 'style', 'data-theme', 'data-bs-theme', 'data-color-scheme', 'data-mode'];
      mo.observe(document.documentElement, { attributes: true, attributeFilter: attrs });
      if (document.body) { mo.observe(document.body, { attributes: true, attributeFilter: attrs }); }
      if (document.head) { mo.observe(document.head, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] }); }
    } catch (e) {}
    try {
      var mq = window.matchMedia('(prefers-color-scheme: dark)');
      if (mq.addEventListener) { mq.addEventListener('change', schedule); }
    } catch (e) {}
    window.addEventListener('load', function () { schedule(); setTimeout(report, 800); setTimeout(report, 2000); });
    document.addEventListener('visibilitychange', schedule);
    document.addEventListener('click', function () { setTimeout(report, 350); }, true);
    setInterval(function () { if (!document.hidden) { report(); } }, 4000);
  }
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
"""

    const val IN_ROOM_CHECK =
        "(function(){try{return !!(window.__hamseda&&window.__hamseda.inRoom());}catch(e){return false;}})()"
}
