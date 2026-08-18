import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';

const CASES = [
  {
    id: 'image-7',
    label: '7图笔记',
    url: 'https://xhslink.cn/o/AsM8OMpdOLO',
    expected: { images: 7 }
  },
  {
    id: 'video-1',
    label: '视频笔记',
    url: 'https://xhslink.cn/o/91iT26p7qax',
    expected: { video: 1, cover: 1, caption: 1 }
  }
];

const OUT = path.resolve('test-report');
fs.mkdirSync(OUT, { recursive: true });

const uniq = (xs) => [...new Set(xs.filter(Boolean))];
const cleanUrl = (s) => String(s || '')
  .replace(/\\u002F/gi, '/')
  .replace(/\\\//g, '/')
  .replace(/&amp;/g, '&')
  .trim();

function classifyNetwork(urls) {
  const xhs = urls.filter(u => /(?:xiaohongshu\.com|xhscdn\.com)/i.test(u));
  const image = xhs.filter(u => /(?:sns-webpic|sns-img|ci\.xiaohongshu|!h5_|\/1040g|\.(?:jpe?g|png|webp)(?:[?#]|$))/i.test(u));
  const video = xhs.filter(u => /(?:sns-video|\.mp4(?:[?#]|$)|\.m3u8(?:[?#]|$)|\/video\/)/i.test(u));
  return { xhs: uniq(xhs), image: uniq(image), video: uniq(video) };
}

async function probe(browser, testCase) {
  const requests = [];
  const failedRequests = [];
  const navigations = [];
  const consoleLines = [];

  const context = await browser.newContext({
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 2.625,
    isMobile: true,
    hasTouch: true,
    locale: 'zh-CN',
    userAgent: 'Mozilla/5.0 (Linux; Android 15; Pixel 8 Pro Build/AP4A.250205.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.0.0 Mobile Safari/537.36 HDResourceExtractor/online-probe'
  });

  const page = await context.newPage();
  page.on('request', req => requests.push(req.url()));
  page.on('requestfailed', req => failedRequests.push({ url: req.url(), error: req.failure()?.errorText || '' }));
  page.on('framenavigated', frame => {
    if (frame === page.mainFrame()) navigations.push(frame.url());
  });
  page.on('console', msg => {
    if (consoleLines.length < 200) consoleLines.push(`[${msg.type()}] ${msg.text()}`);
  });

  let gotoError = '';
  try {
    await page.goto(testCase.url, { waitUntil: 'domcontentloaded', timeout: 35000 });
  } catch (e) {
    gotoError = String(e?.message || e);
  }

  await page.waitForTimeout(9000);

  let pageData = {};
  try {
    pageData = await page.evaluate(() => {
      const cu = (s) => String(s || '').replace(/\\u002F/gi, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&').trim();
      const domImages = [];
      document.querySelectorAll('img').forEach((el, i) => {
        const url = cu(el.currentSrc || el.src || el.getAttribute('src') || '');
        if (!url) return;
        domImages.push({
          index: i,
          url,
          width: Number(el.naturalWidth || el.width || 0),
          height: Number(el.naturalHeight || el.height || 0),
          cls: String(el.className || '')
        });
      });

      const domVideos = [];
      document.querySelectorAll('video, source').forEach((el, i) => {
        const url = cu(el.currentSrc || el.src || el.getAttribute('src') || '');
        if (url) domVideos.push({ index: i, url, tag: el.tagName });
      });

      const meta = {};
      document.querySelectorAll('meta').forEach(el => {
        const k = el.getAttribute('property') || el.getAttribute('name');
        const v = el.getAttribute('content');
        if (k && v && /(?:og:|description|twitter:)/i.test(k)) meta[k] = v;
      });

      const stateImages = [];
      const stateVideos = [];
      const originVideoKeys = [];
      const noteCandidates = [];
      const state = window.__INITIAL_STATE__ || null;
      const seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
      let visited = 0;

      const pushString = (key, value) => {
        const v = cu(value);
        if (!v) return;
        if (/originVideoKey/i.test(key) && !/^https?:/i.test(v)) originVideoKeys.push(v);
        if (/^https?:/i.test(v)) {
          if (/(?:xhscdn\.com|xiaohongshu\.com)/i.test(v)) {
            if (/(?:sns-video|\.mp4(?:[?#]|$)|\.m3u8(?:[?#]|$)|\/video\/)/i.test(v)) stateVideos.push(v);
            if (/(?:sns-webpic|sns-img|ci\.xiaohongshu|!h5_|\/1040g|\.(?:jpe?g|png|webp)(?:[?#]|$))/i.test(v)) stateImages.push(v);
          }
        }
      };

      const walk = (obj, depth, path) => {
        if (!obj || typeof obj !== 'object' || depth > 14 || visited > 30000) return;
        visited++;
        try {
          if (seen && seen.has(obj)) return;
          if (seen) seen.add(obj);
        } catch {}

        let score = 0;
        try { if (obj.video) score += 50; } catch {}
        try { if (Array.isArray(obj.imageList)) score += 40; } catch {}
        try { if (obj.title || obj.desc) score += 15; } catch {}
        try { if (obj.noteId || obj.id) score += 10; } catch {}
        try { if (obj.video?.consumer?.originVideoKey) score += 150; } catch {}
        if (score >= 40) {
          noteCandidates.push({
            score,
            path,
            id: String(obj.noteId || obj.id || ''),
            title: String(obj.title || '').slice(0, 160),
            desc: String(obj.desc || '').slice(0, 240),
            imageListCount: Array.isArray(obj.imageList) ? obj.imageList.length : 0,
            hasVideo: Boolean(obj.video),
            originVideoKey: String(obj.video?.consumer?.originVideoKey || '')
          });
        }

        let keys = [];
        try { keys = Object.keys(obj); } catch { return; }
        for (const key of keys) {
          let child;
          try { child = obj[key]; } catch { continue; }
          if (typeof child === 'string') pushString(key, child);
          else if (child && typeof child === 'object') walk(child, depth + 1, `${path}.${key}`);
          if (visited > 30000) break;
        }
      };
      try { if (state) walk(state, 0, 'state'); } catch {}

      let html = '';
      try { html = document.documentElement?.innerHTML || ''; } catch {}
      const htmlOriginKeys = [];
      const re = /["']originVideoKey["']\s*:\s*["']([^"']+)["']/gi;
      let m;
      while ((m = re.exec(html)) && htmlOriginKeys.length < 50) htmlOriginKeys.push(cu(m[1]));

      const detailTitle = String(document.querySelector('#detail-title')?.textContent || '').trim();
      const detailDesc = String(document.querySelector('#detail-desc')?.textContent || '').trim();

      return {
        href: location.href,
        title: document.title,
        detailTitle,
        detailDesc,
        meta,
        domImages,
        domVideos,
        statePresent: Boolean(state),
        stateVisited: visited,
        stateImages: [...new Set(stateImages)],
        stateVideos: [...new Set(stateVideos)],
        originVideoKeys: [...new Set(originVideoKeys)],
        htmlOriginKeys: [...new Set(htmlOriginKeys)],
        noteCandidates: noteCandidates.sort((a, b) => b.score - a.score).slice(0, 30)
      };
    });
  } catch (e) {
    pageData = { evaluateError: String(e?.message || e), href: page.url() };
  }

  const net = classifyNetwork(requests.map(cleanUrl));
  const screenshotPath = path.join(OUT, `${testCase.id}.png`);
  const htmlPath = path.join(OUT, `${testCase.id}.html`);
  try { await page.screenshot({ path: screenshotPath, fullPage: true }); } catch {}
  try { fs.writeFileSync(htmlPath, await page.content(), 'utf8'); } catch {}

  const report = {
    id: testCase.id,
    label: testCase.label,
    inputUrl: testCase.url,
    expected: testCase.expected,
    gotoError,
    finalUrl: page.url(),
    navigations: uniq(navigations),
    requestCount: requests.length,
    xhsRequestCount: net.xhs.length,
    networkImageCount: net.image.length,
    networkVideoCount: net.video.length,
    failedRequests: failedRequests.slice(0, 100),
    networkImages: net.image.slice(0, 300),
    networkVideos: net.video.slice(0, 100),
    pageData,
    console: consoleLines
  };

  await context.close();
  return report;
}

const browser = await chromium.launch({ headless: true });
const reports = [];
for (const tc of CASES) {
  console.log(`\n========== ${tc.label} ==========`);
  console.log(`打开: ${tc.url}`);
  const r = await probe(browser, tc);
  reports.push(r);
  console.log(`最终地址: ${r.finalUrl}`);
  console.log(`请求: ${r.requestCount} | XHS: ${r.xhsRequestCount}`);
  console.log(`网络图片候选: ${r.networkImageCount}`);
  console.log(`DOM图片: ${r.pageData?.domImages?.length || 0}`);
  console.log(`State图片: ${r.pageData?.stateImages?.length || 0}`);
  console.log(`网络视频候选: ${r.networkVideoCount}`);
  console.log(`State视频: ${r.pageData?.stateVideos?.length || 0}`);
  console.log(`originVideoKey: ${uniq([...(r.pageData?.originVideoKeys || []), ...(r.pageData?.htmlOriginKeys || [])]).length}`);
  console.log(`State存在: ${Boolean(r.pageData?.statePresent)} | 遍历节点: ${r.pageData?.stateVisited || 0}`);
}
await browser.close();

fs.writeFileSync(path.join(OUT, 'report.json'), JSON.stringify(reports, null, 2), 'utf8');

const md = ['# 小红书在线探针报告', '', `生成时间：${new Date().toISOString()}`, ''];
for (const r of reports) {
  const p = r.pageData || {};
  const keys = uniq([...(p.originVideoKeys || []), ...(p.htmlOriginKeys || [])]);
  md.push(`## ${r.label}`);
  md.push(`- 输入：\`${r.inputUrl}\``);
  md.push(`- 最终页面：\`${r.finalUrl}\``);
  md.push(`- 页面标题：${p.title || '(空)'}`);
  md.push(`- __INITIAL_STATE__：${p.statePresent ? '有' : '无'}（遍历 ${p.stateVisited || 0} 个节点）`);
  md.push(`- 网络图片候选：${r.networkImageCount}`);
  md.push(`- DOM 图片：${p.domImages?.length || 0}`);
  md.push(`- State 图片 URL：${p.stateImages?.length || 0}`);
  md.push(`- 网络视频候选：${r.networkVideoCount}`);
  md.push(`- State 视频 URL：${p.stateVideos?.length || 0}`);
  md.push(`- originVideoKey：${keys.length}`);
  md.push(`- 笔记对象候选：${p.noteCandidates?.length || 0}`);
  if (r.gotoError) md.push(`- 页面打开异常：\`${r.gotoError.replace(/`/g, "'").slice(0, 500)}\``);
  md.push('');
}
fs.writeFileSync(path.join(OUT, 'summary.md'), md.join('\n'), 'utf8');
console.log('\n报告已写入 test-report/');
