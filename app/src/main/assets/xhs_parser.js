(function (global) {
  'use strict';

  function cleanString(value) {
    return String(value == null ? '' : value).trim();
  }

  function cleanUrl(value) {
    var u = cleanString(value)
      .replace(/\\u002F/gi, '/')
      .replace(/\\\//g, '/')
      .replace(/&amp;/g, '&');
    if (u.indexOf('//') === 0) u = 'https:' + u;
    return u.replace(/^http:/i, 'https:');
  }

  function uniqueStrings(values) {
    var seen = Object.create(null);
    var out = [];
    (values || []).forEach(function (value) {
      var v = cleanString(value);
      if (!v || seen[v]) return;
      seen[v] = true;
      out.push(v);
    });
    return out;
  }

  function imageOriginalUrl(item) {
    if (!item || typeof item !== 'object') return '';
    var fileId = cleanString(item.fileId || item.file_id);
    if (fileId) return 'https://ci.xiaohongshu.com/' + fileId.replace(/^\/+/, '');

    var candidates = [];
    ['urlDefault', 'urlPre', 'url', 'src', 'originalUrl'].forEach(function (key) {
      if (item[key]) candidates.push(item[key]);
    });
    if (Array.isArray(item.infoList)) {
      item.infoList.forEach(function (info) {
        if (!info) return;
        candidates.push(info.url || info.urlDefault || '');
      });
    }

    var raw = cleanUrl(candidates.filter(Boolean)[0] || '');
    if (!raw) return '';

    try {
      var parsed = new URL(raw, location.href);
      var host = String(parsed.hostname || '').toLowerCase();
      if (host === 'ci.xiaohongshu.com') return parsed.origin + parsed.pathname;
      if (/xhscdn\.com$/i.test(host)) {
        var segments = parsed.pathname.split('/').filter(Boolean);
        var timestampIndex = -1;
        for (var i = 0; i < segments.length; i++) {
          if (/^[0-9]{12}$/.test(segments[i])) {
            timestampIndex = i;
            break;
          }
        }
        if (
          timestampIndex >= 0 &&
          /^[0-9a-f]{32}$/i.test(segments[timestampIndex + 1] || '') &&
          segments.length > timestampIndex + 2
        ) {
          var payload = segments.slice(timestampIndex + 2);
          payload[payload.length - 1] = payload[payload.length - 1].split('!')[0].split('?')[0];
          return 'https://ci.xiaohongshu.com/' + payload.join('/');
        }
      }
    } catch (_) {}
    return raw;
  }

  function normalizeImage(item, index) {
    item = item && typeof item === 'object' ? item : {};
    var width = Number(item.width || item.imageWidth || item.image_width || 0) || 0;
    var height = Number(item.height || item.imageHeight || item.image_height || 0) || 0;
    var fileId = cleanString(item.fileId || item.file_id);
    var displayCandidates = [];
    ['urlDefault', 'urlPre', 'url', 'src', 'originalUrl'].forEach(function (key) {
      if (item[key]) displayCandidates.push(cleanUrl(item[key]));
    });
    if (Array.isArray(item.infoList)) {
      item.infoList.forEach(function (info) {
        if (!info) return;
        var u = cleanUrl(info.url || info.urlDefault || '');
        if (u) displayCandidates.push(u);
      });
    }
    return {
      index: index,
      fileId: fileId,
      width: width,
      height: height,
      originalUrl: imageOriginalUrl(item),
      displayUrls: uniqueStrings(displayCandidates)
    };
  }

  function noteScore(obj, expectedId) {
    if (!obj || typeof obj !== 'object') return -1;
    var score = 0;
    try { if (Array.isArray(obj.imageList)) score += 50; } catch (_) {}
    try { if (obj.video) score += 60; } catch (_) {}
    try { if (obj.title || obj.desc) score += 15; } catch (_) {}
    try { if (obj.noteId || obj.id) score += 10; } catch (_) {}
    try { if (obj.video && obj.video.consumer && obj.video.consumer.originVideoKey) score += 160; } catch (_) {}
    try {
      var id = cleanString(obj.noteId || obj.id);
      if (expectedId && id === expectedId) score += 300;
    } catch (_) {}
    return score;
  }

  function extractExpectedId() {
    try {
      var path = String(location.pathname || '');
      var m = path.match(/\/(?:discovery\/item|explore)\/([0-9a-f]{24})(?:\/|$)/i);
      if (m) return m[1];
    } catch (_) {}
    return '';
  }

  function findNote(state) {
    if (!state || typeof state !== 'object') return null;
    var expectedId = extractExpectedId();
    var candidates = [];

    function consider(obj, source) {
      var score = noteScore(obj, expectedId);
      if (score >= 30) candidates.push({ obj: obj, source: source, score: score });
    }

    try { consider(state.noteData && state.noteData.data && state.noteData.data.noteData, 'noteData.data.noteData'); } catch (_) {}
    try { consider(state.noteData && state.noteData.noteData, 'noteData.noteData'); } catch (_) {}
    try {
      var map = state.note && state.note.noteDetailMap;
      if (map && typeof map === 'object') {
        if (expectedId && map[expectedId] && map[expectedId].note) consider(map[expectedId].note, 'note.noteDetailMap.expected.note');
        Object.keys(map).forEach(function (key) {
          if (map[key] && map[key].note) consider(map[key].note, 'note.noteDetailMap.' + key + '.note');
        });
      }
    } catch (_) {}

    // Fallback only. The known Xiaohongshu H5 path above is deliberately preferred.
    var seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
    var visited = 0;
    function walk(obj, depth, source) {
      if (!obj || typeof obj !== 'object' || depth > 12 || visited > 12000) return;
      visited += 1;
      try {
        if (seen && seen.has(obj)) return;
        if (seen) seen.add(obj);
      } catch (_) {}
      consider(obj, source);
      var keys;
      try { keys = Object.keys(obj); } catch (_) { return; }
      for (var i = 0; i < keys.length; i++) {
        var child;
        try { child = obj[keys[i]]; } catch (_) { continue; }
        if (child && typeof child === 'object') walk(child, depth + 1, source + '.' + keys[i]);
        if (visited > 12000) break;
      }
    }
    try { walk(state, 0, 'state'); } catch (_) {}

    if (!candidates.length) return null;
    candidates.sort(function (a, b) { return b.score - a.score; });
    return {
      note: candidates[0].obj,
      source: candidates[0].source,
      score: candidates[0].score,
      visited: visited
    };
  }

  function playbackUrls(note) {
    var urls = [];
    try {
      var stream = note.video && note.video.media && note.video.media.stream;
      if (stream && typeof stream === 'object') {
        Object.keys(stream).forEach(function (codec) {
          var list = stream[codec];
          if (!Array.isArray(list)) return;
          list.forEach(function (entry) {
            if (!entry) return;
            ['masterUrl', 'master_url', 'backupUrls', 'backup_urls'].forEach(function (key) {
              var value = entry[key];
              if (Array.isArray(value)) value.forEach(function (u) { urls.push(cleanUrl(u)); });
              else if (value) urls.push(cleanUrl(value));
            });
          });
        });
      }
    } catch (_) {}
    return uniqueStrings(urls).filter(function (u) { return /^https?:/i.test(u); });
  }

  function domFallback() {
    var images = [];
    var caption = '';
    try {
      var title = cleanString((document.querySelector('#detail-title') || {}).textContent || '');
      var desc = cleanString((document.querySelector('#detail-desc') || {}).textContent || '');
      caption = [title, desc].filter(Boolean).join('\n\n');
    } catch (_) {}
    try {
      document.querySelectorAll('.note-image-box img, .media-container img').forEach(function (el, index) {
        var u = cleanUrl(el.currentSrc || el.src || el.getAttribute('src') || '');
        if (!u) return;
        images.push({
          index: index,
          fileId: '',
          width: Number(el.naturalWidth || el.width || 0) || 0,
          height: Number(el.naturalHeight || el.height || 0) || 0,
          originalUrl: u,
          displayUrls: [u]
        });
      });
    } catch (_) {}
    return { caption: caption, images: images };
  }

  function extract() {
    var state = null;
    try { state = global.__INITIAL_STATE__ || null; } catch (_) {}
    var found = findNote(state);
    var fallback = domFallback();

    if (!found || !found.note) {
      return {
        ok: false,
        source: 'dom-fallback',
        noteId: '',
        type: 'unknown',
        title: '',
        desc: '',
        tags: [],
        caption: fallback.caption,
        images: fallback.images,
        cover: fallback.images[0] || null,
        originVideoKey: '',
        originVideoUrl: '',
        playbackUrls: [],
        isVideo: false
      };
    }

    var note = found.note;
    var noteId = cleanString(note.noteId || note.id);
    var title = cleanString(note.title);
    var desc = cleanString(note.desc);
    var tags = [];
    try {
      if (Array.isArray(note.tagList)) {
        tags = note.tagList.map(function (item) { return cleanString(item && item.name); }).filter(Boolean);
      }
    } catch (_) {}

    var imageList = Array.isArray(note.imageList) ? note.imageList : [];
    var images = imageList.map(function (item, index) { return normalizeImage(item, index); })
      .filter(function (item) { return Boolean(item.originalUrl || item.displayUrls.length); });

    var isVideo = Boolean(note.video);
    var originKey = '';
    try { originKey = cleanString(note.video && note.video.consumer && note.video.consumer.originVideoKey).replace(/^\/+/, ''); } catch (_) {}
    var originUrl = originKey ? 'https://sns-video-bd.xhscdn.com/' + originKey : '';

    var cover = null;
    if (isVideo && images.length) cover = images[0];
    if (!cover) {
      try {
        if (note.cover) cover = normalizeImage(note.cover, 0);
      } catch (_) {}
    }
    if (!cover) {
      try {
        if (note.video && note.video.image) cover = normalizeImage(note.video.image, 0);
      } catch (_) {}
    }

    return {
      ok: true,
      source: found.source,
      score: found.score,
      noteId: noteId,
      type: isVideo ? 'video' : 'image',
      title: title,
      desc: desc,
      tags: tags,
      caption: [title, desc, tags.join(' ')].filter(Boolean).join('\n\n'),
      images: isVideo ? [] : images,
      cover: cover,
      originVideoKey: originKey,
      originVideoUrl: originUrl,
      playbackUrls: playbackUrls(note),
      isVideo: isVideo
    };
  }

  global.HDXhsParser = {
    version: '1.0.0',
    extract: extract,
    imageOriginalUrl: imageOriginalUrl
  };
})(window);
