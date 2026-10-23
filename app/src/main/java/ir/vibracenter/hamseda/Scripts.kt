package ir.vibracenter.hamseda

/**
 * JavaScript injected into the Hamseda site only (document start, origin restricted):
 *  1. tracks WebRTC connections / live microphone streams so the app knows when you are inside a room
 *  2. replaces the web Notification API (not available in WebView) with real Android notifications
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

  // ---- room detection ----
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

  // ---- notifications ----
  function str(v, n) { return v ? String(v).substring(0, n) : ''; }

  function N(title, opts) {
    opts = opts || {};
    this.title = str(title, 100);
    this.body = str(opts.body, 300);
    this.tag = str(opts.tag, 64);
    this.data = opts.data;
    this.onclick = null; this.onclose = null; this.onerror = null; this.onshow = null;
    post({ t: 'notify', title: this.title, body: this.body, tag: this.tag });
    var self = this;
    setTimeout(function () { try { if (typeof self.onshow === 'function') { self.onshow(new Event('show')); } } catch (e) {} }, 0);
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
        opts = opts || {};
        post({ t: 'notify', title: str(title, 100), body: str(opts.body, 300), tag: str(opts.tag, 64) });
        return Promise.resolve();
      };
      ServiceWorkerRegistration.prototype.getNotifications = function () { return Promise.resolve([]); };
    }
  } catch (e) {}
})();
"""

    const val IN_ROOM_CHECK =
        "(function(){try{return !!(window.__hamseda&&window.__hamseda.inRoom());}catch(e){return false;}})()"
}
