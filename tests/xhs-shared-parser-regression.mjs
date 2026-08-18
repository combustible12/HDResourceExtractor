import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';

const PARSER = path.resolve('app/src/main/assets/xhs_parser.js');
const OUT = path.resolve('shared-parser-report');
fs.mkdirSync(OUT, { recursive: true });

const CASES = [
  { id: 'image-7', url: 'https://xhslink.cn/o/AsM8OMpdOLO', kind: 'image' },
  { id: 'video-1', url: 'https://xhslink.cn/o/91iT26p7qax', kind: 'video' }
];
const UA = 'Mozilla/5.0 (Linux; Android 15; Pixel 8 Pro Build/AP4A.250205.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.0.0 Mobile Safari/537.36 HDResourceExtractor/shared-parser-test';

const results = [];
const failures = [];
function check(name, ok, detail) {
  const r = { name, ok: Boolean(ok), detail };
  results.push(r);
  if (!ok) failures.push(r);
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name} | ${detail}`);
}

async function open(browser, tc) {
  const context = await browser.newContext({
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 2.625,
    isMobile: true,
    hasTouch: true,
    locale: 'zh-CN',
    userAgent: UA
  });
  const page = await context.newPage();
  await page.goto(tc.url, { waitUntil: 'domcontentloaded', timeout: 35000 });
  await page.waitForFunction(() => Boolean(window.__INITIAL_STATE__?.noteData?.data?.noteData), null, { timeout: 15000 });
  await page.addScriptTag({ path: PARSER });
  const parsed = await page.evaluate(() => window.HDXhsParser.extract());
  await page.screenshot({ path: path.join(OUT, `${tc.id}.png`), fullPage: true });
  return { context, page, parsed };
}

async function validDownload(context, url, referer, type) {
  const res = await context.request.get(url, {
    headers: {
      'User-Agent': UA,
      Referer: referer,
      Accept: type === 'video' ? 'video/mp4,video/*;q=0.9,*/*;q=0.5' : 'image/avif,image/webp,image/apng,image/*,*/*;q=0.8',
      'Accept-Encoding': 'identity'
    },
    timeout: 45000,
    failOnStatusCode: false,
    maxRedirects: 10
  });
  const body = Buffer.from(await res.body());
  const ct = res.headers()['content-type'] || '';
  const okStatus = res.status() >= 200 && res.status() < 300;
  if (type === 'video') {
    const head = body.subarray(0, 64).toString('latin1');
    return { ok: okStatus && body.length >= 128 * 1024 && (head.includes('ftyp') || /^video\//i.test(ct)), status: res.status(), bytes: body.length, contentType: ct };
  }
  const h = body.subarray(0, 16);
  const magic = (h[0] === 0xff && h[1] === 0xd8) || (h[0] === 0x89 && h[1] === 0x50) || (h.subarray(0,4).toString('ascii') === 'RIFF' && h.subarray(8,12).toString('ascii') === 'WEBP');
  return { ok: okStatus && body.length >= 1024 && (magic || /^image\//i.test(ct)), status: res.status(), bytes: body.length, contentType: ct };
}

const browser = await chromium.launch({ headless: true });
for (const tc of CASES) {
  console.log(`\n===== Shared parser: ${tc.id} =====`);
  let session;
  try {
    session = await open(browser, tc);
    const { context, page, parsed } = session;
    fs.writeFileSync(path.join(OUT, `${tc.id}.json`), JSON.stringify(parsed, null, 2));

    check(`${tc.id}: parser ok`, parsed.ok === true, `source=${parsed.source}`);
    check(`${tc.id}: exact structured source`, parsed.source === 'noteData.data.noteData', `source=${parsed.source}`);
    check(`${tc.id}: caption`, Boolean(parsed.caption), `${String(parsed.caption || '').length} chars`);

    if (tc.kind === 'image') {
      check('image-7: exact image count', parsed.images?.length === 7, `expected=7 actual=${parsed.images?.length || 0}`);
      const downloadRows = [];
      for (const image of parsed.images || []) {
        const r = await validDownload(context, image.originalUrl, page.url(), 'image');
        downloadRows.push({ url: image.originalUrl, ...r });
      }
      check('image-7: all shared-parser originals downloadable', downloadRows.length === 7 && downloadRows.every(x => x.ok), downloadRows.map(x => `${x.status}/${x.bytes}`).join(', '));
      fs.writeFileSync(path.join(OUT, 'image-downloads.json'), JSON.stringify(downloadRows, null, 2));
    } else {
      check('video-1: is video', parsed.isVideo === true, `type=${parsed.type}`);
      check('video-1: cover', Boolean(parsed.cover?.originalUrl), parsed.cover?.originalUrl || '(empty)');
      check('video-1: originVideoKey', Boolean(parsed.originVideoKey), parsed.originVideoKey || '(empty)');
      check('video-1: origin URL host', /^https:\/\/sns-video-bd\.xhscdn\.com\//i.test(parsed.originVideoUrl || ''), parsed.originVideoUrl || '(empty)');
      check('video-1: playback kept separate', Array.isArray(parsed.playbackUrls) && parsed.playbackUrls.length > 0 && !parsed.playbackUrls.includes(parsed.originVideoUrl), `playback=${parsed.playbackUrls?.length || 0}`);
      if (parsed.cover?.originalUrl) {
        const r = await validDownload(context, parsed.cover.originalUrl, page.url(), 'image');
        check('video-1: cover downloadable', r.ok, `HTTP ${r.status} · ${r.bytes} bytes · ${r.contentType}`);
      }
      if (parsed.originVideoUrl) {
        const r = await validDownload(context, parsed.originVideoUrl, page.url(), 'video');
        check('video-1: origin downloadable', r.ok, `HTTP ${r.status} · ${r.bytes} bytes · ${r.contentType}`);
      }
    }

    await context.close();
  } catch (e) {
    check(`${tc.id}: execution`, false, String(e?.message || e));
    try { await session?.context?.close(); } catch {}
  }
}
await browser.close();

const pass = failures.length === 0;
fs.writeFileSync(path.join(OUT, 'results.json'), JSON.stringify({ pass, results, failures }, null, 2));
const md = ['# 共享解析器在线回归', '', `总体：${pass ? '✅ PASS' : '❌ FAIL'}`, '', `解析器：\`app/src/main/assets/xhs_parser.js\``, ''];
for (const r of results) md.push(`- ${r.ok ? '✅' : '❌'} **${r.name}** — ${r.detail}`);
fs.writeFileSync(path.join(OUT, 'summary.md'), md.join('\n'));
if (!pass) process.exitCode = 1;
