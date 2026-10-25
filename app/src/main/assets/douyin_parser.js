(function (global) {
  'use strict';
  function text(v) { return String(v == null ? '' : v).trim(); }
  function url(v) {
    var s = text(v).replace(/\\u002F/gi, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&');
    if (s.indexOf('//') === 0) s = 'https:' + s;
    return s.replace(/^http:/i, 'https:');
  }
  function firstUrl(obj) {
    if (!obj) return '';
    var list = obj.url_list || obj.urlList || [];
    return url(Array.isArray(list) ? list[0] : list);
  }
  function score(obj) {
    if (!obj || typeof obj !== 'object') return -1;
    var n = 0;
    if (obj.aweme_id) n += 50;
    if (obj.desc) n += 10;
    if (obj.video) n += 70;
    if (Array.isArray(obj.images)) n += 80;
    return n;
  }
  function findAweme(root, expectedId) {
    var best = null, bestScore = -1, visited = 0;
    var seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
    function walk(obj, depth) {
      if (!obj || typeof obj !== 'object' || depth > 13 || visited++ > 18000) return;
      try { if (seen && seen.has(obj)) return; if (seen) seen.add(obj); } catch (_) {}
      var s = score(obj); if (expectedId && text(obj.aweme_id || obj.awemeId) !== expectedId) s = -1; if (s > bestScore) { best = obj; bestScore = s; }
      var keys; try { keys = Object.keys(obj); } catch (_) { return; }
      keys.forEach(function (key) { try { walk(obj[key], depth + 1); } catch (_) {} });
    }
    walk(root, 0); return bestScore >= 50 ? best : null;
  }
  function image(item, index) {
    var source = (item && (item.url_list || item.urlList)) || [];
    var u = url(Array.isArray(source) ? (source[source.length - 1] || source[0]) : source);
    return { index: index, originalUrl: u, displayUrls: u ? [u] : [] };
  }
  function extract() {
    var roots = [];
    try { roots.push(global.__UNIVERSAL_DATA_FOR_REHYDRATION__); } catch (_) {}
    try { roots.push(global._ROUTER_DATA); } catch (_) {}
    try { roots.push(global.__INITIAL_STATE__); } catch (_) {}
    var aweme = null;
    var match = String(global.location && global.location.href || '').match(/(?:\/video\/|\/note\/|[?&](?:modal_id|aweme_id)=)(\d{15,22})/);
    var expectedId = match ? match[1] : '';
    roots.some(function (root) { aweme = findAweme(root, expectedId); return Boolean(aweme); });
    if (!aweme) return { ok: false, type: 'unknown', caption: '', images: [], livePhotos: [], cover: null, originVideoUrl: '', playbackUrls: [] };
    var images = (Array.isArray(aweme.images) ? aweme.images : []).map(image).filter(function (x) { return x.originalUrl; });
    var livePhotos = [];
    (Array.isArray(aweme.images) ? aweme.images : []).forEach(function (item, index) {
      var video = item && item.video;
      var motion = firstUrl(video && (video.play_addr || video.playAddr)) || firstUrl(video && (video.download_addr || video.downloadAddr));
      if (motion) livePhotos.push({ index: index, image: image(item, index), motionUrl: motion.replace('playwm', 'play') });
    });
    var video = aweme.video || {};
    var play = firstUrl(video.play_addr || video.playAddr).replace('playwm', 'play');
    if (!play && Array.isArray(video.bit_rate || video.bitRate)) {
      var rates = video.bit_rate || video.bitRate;
      rates.sort(function (a, b) { return Number(b.bit_rate || 0) - Number(a.bit_rate || 0); });
      play = firstUrl(rates[0] && (rates[0].play_addr || rates[0].playAddr)).replace('playwm', 'play');
    }
    var coverUrl = firstUrl(video.cover || video.origin_cover || video.dynamic_cover);
    var cover = coverUrl ? { index: 0, originalUrl: coverUrl, displayUrls: [coverUrl] } : (images[0] || null);
    var type = livePhotos.length ? 'live_photo' : (images.length ? 'image' : 'video');
    return { ok: true, noteId: text(aweme.aweme_id), type: type, caption: text(aweme.desc), images: type === 'video' ? [] : images, livePhotos: livePhotos, cover: cover, originVideoUrl: type === 'video' ? play : '', playbackUrls: play ? [play] : [] };
  }
  global.HDDouyinParser = { version: '1.1.0', extract: extract };
})(window);
