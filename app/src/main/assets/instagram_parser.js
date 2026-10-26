(function (global) {
  'use strict';

  function text(value) {
    return String(value == null ? '' : value).trim();
  }

  function cleanUrl(value) {
    var url = text(value)
      .replace(/\\u0026/gi, '&')
      .replace(/\\u002F/gi, '/')
      .replace(/\\\//g, '/')
      .replace(/&amp;/g, '&');
    if (url.indexOf('//') === 0) url = 'https:' + url;
    return url.replace(/^http:/i, 'https:');
  }

  function isInstagramMediaUrl(value) {
    var url = cleanUrl(value);
    if (!/^https:\/\//i.test(url)) return false;
    return /(?:cdninstagram\.com|fbcdn\.net|instagram\.com)/i.test(url);
  }

  function expectedShortcode() {
    try {
      var match = String(location.pathname || '').match(/\/(?:p|reel|tv)\/([^/?#]+)/i);
      return match ? match[1] : '';
    } catch (_) {
      return '';
    }
  }

  function dimensions(item) {
    item = item && typeof item === 'object' ? item : {};
    return {
      width: Number(item.width || item.original_width || item.config_width || 0) || 0,
      height: Number(item.height || item.original_height || item.config_height || 0) || 0
    };
  }

  function bestVersion(versions) {
    if (!Array.isArray(versions)) return null;
    var ranked = versions.filter(Boolean).map(function (entry) {
      var size = dimensions(entry);
      return {
        url: cleanUrl(entry.url || entry.src || ''),
        width: size.width,
        height: size.height,
        area: size.width * size.height
      };
    }).filter(function (entry) { return isInstagramMediaUrl(entry.url); });
    ranked.sort(function (a, b) { return b.area - a.area; });
    return ranked[0] || null;
  }

  function normalizeMedia(node) {
    if (!node || typeof node !== 'object') return null;
    var size = dimensions(node);
    var video = bestVersion(node.video_versions || node.videoVersions);
    var imageVersions = node.image_versions2 && node.image_versions2.candidates;
    var image = bestVersion(imageVersions || node.image_versions || node.display_resources);
    var isVideo = Number(node.media_type || node.mediaType || 0) === 2 ||
      String(node.__typename || '').toLowerCase().indexOf('video') >= 0 ||
      !!node.is_video || !!node.video_url || !!video;
    var direct = cleanUrl(
      (isVideo && (node.video_url || node.videoUrl)) ||
      node.display_url || node.displayUrl || node.thumbnail_src || node.src || node.url || ''
    );
    var chosen = isVideo ? (video || (isInstagramMediaUrl(direct) ? {
      url: direct, width: size.width, height: size.height
    } : null)) : (image || (isInstagramMediaUrl(direct) ? {
      url: direct, width: size.width, height: size.height
    } : null));
    if (!chosen) return null;
    return {
      type: isVideo ? 'video' : 'image',
      url: chosen.url,
      width: chosen.width || size.width,
      height: chosen.height || size.height
    };
  }

  function childrenOf(node) {
    if (!node || typeof node !== 'object') return [];
    if (Array.isArray(node.carousel_media)) return node.carousel_media;
    if (Array.isArray(node.carouselMedia)) return node.carouselMedia;
    try {
      var edges = node.edge_sidecar_to_children && node.edge_sidecar_to_children.edges;
      if (Array.isArray(edges)) return edges.map(function (edge) { return edge && edge.node; }).filter(Boolean);
    } catch (_) {}
    return [];
  }

  function candidateScore(node, shortcode) {
    if (!node || typeof node !== 'object') return -1;
    var children = childrenOf(node);
    var score = children.length ? 1000 + children.length * 20 : 0;
    if (normalizeMedia(node)) score += 80;
    var code = text(node.code || node.shortcode || node.short_code);
    if (shortcode && code === shortcode) score += 10000;
    return score;
  }

  function collectStates() {
    var states = [];
    ['_sharedData', '__additionalData', '__NEXT_DATA__', '__initialData', '__INITIAL_STATE__'].forEach(function (key) {
      try { if (global[key] && typeof global[key] === 'object') states.push(global[key]); } catch (_) {}
    });
    try {
      document.querySelectorAll('script[type="application/json"], script[data-content-len]').forEach(function (script) {
        var raw = text(script.textContent || '');
        if (!raw || raw.length > 12000000) return;
        try { states.push(JSON.parse(raw)); } catch (_) {}
      });
    } catch (_) {}
    return states;
  }

  function structuredItems() {
    var shortcode = expectedShortcode();
    var candidates = [];
    var seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
    var visited = 0;

    function walk(value, depth) {
      if (!value || typeof value !== 'object' || depth > 15 || visited > 30000) return;
      visited += 1;
      try { if (seen && seen.has(value)) return; if (seen) seen.add(value); } catch (_) {}
      var score = candidateScore(value, shortcode);
      if (score >= 80) candidates.push({ node: value, score: score });
      var keys;
      try { keys = Object.keys(value); } catch (_) { return; }
      for (var i = 0; i < keys.length && visited <= 30000; i++) {
        var child;
        try { child = value[keys[i]]; } catch (_) { continue; }
        if (child && typeof child === 'object') walk(child, depth + 1);
      }
    }

    collectStates().forEach(function (state) { walk(state, 0); });
    candidates.sort(function (a, b) { return b.score - a.score; });
    if (!candidates.length) return [];
    var best = candidates[0].node;
    var children = childrenOf(best);
    if (!children.length) children = [best];
    return children.map(normalizeMedia).filter(Boolean);
  }

  function domItems() {
    var items = [];
    try {
      var article = document.querySelector('article');
      if (!article) return items;
      article.querySelectorAll('img, video').forEach(function (element) {
        var width = Number(element.videoWidth || element.naturalWidth || element.width || 0) || 0;
        var height = Number(element.videoHeight || element.naturalHeight || element.height || 0) || 0;
        if (Math.max(width, height) < 300 || Math.min(width, height) < 180) return;
        var isVideo = String(element.tagName || '').toLowerCase() === 'video';
        var url = cleanUrl(element.currentSrc || element.src || element.getAttribute('src') || '');
        if (!isInstagramMediaUrl(url)) return;
        items.push({ type: isVideo ? 'video' : 'image', url: url, width: width, height: height });
      });
    } catch (_) {}
    return items;
  }

  function unique(items) {
    var seen = Object.create(null);
    return (items || []).filter(function (item) {
      var key = item.url;
      try {
        var parsed = new URL(item.url, location.href);
        key = String(parsed.hostname || '').toLowerCase() + parsed.pathname;
      } catch (_) {}
      if (!key || seen[key]) return false;
      seen[key] = true;
      return true;
    });
  }

  function extract() {
    var structured = structuredItems();
    return {
      platform: 'instagram',
      shortcode: expectedShortcode(),
      source: structured.length ? 'structured' : 'dom',
      items: unique(structured.length ? structured : domItems())
    };
  }

  global.HDInstagramParser = { extract: extract };
})(window);
