import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';

const OUT = path.resolve('regression-report');
fs.mkdirSync(OUT, { recursive: true });

const IMAGE_URL = 'https://xhslink.cn/o/AsM8OMpdOLO';
const VIDEO_URL = 'https://xhslink.cn/o/91iT26p7qax';
const UA = 'Mozilla/5.0 (Linux; Android 15; Pixel 8 Pro Build/AP4A.250205.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/151.0.0.0 Mobile Safari/537.36 HDResourceExtractor/regression';

const results = [];
const failures = [];

function logResult(name, ok, detail) {
  const row = { name, ok, detail };
  results.push(row);
  console.log(`${ok ? 'PASS' : 'FAIL'} | ${name} | ${detail}`);
  if (!ok) failures.push(row);
}

function clean(s) {
  return String(s || '').replace(/\\u002F/gi, '/').replace(/\\\//g, '/').replace(/&amp;/g, '&').trim();
}

function originalImageUrl(item) {
  const fileId = String(item?.fileId || '').trim();
  if (fileId) return `https://ci.xiaohongshu.com/${fileId}`;
  const raw = clean(item?.url || item?.infoList?.[0]?.url || '');
  if (!raw) return '';
  try {
    const u = new URL(raw.replace(/^http:/i, 'https:'));
    if (u.hostname === 'ci.xiaohongshu.com') return `${u.origin}${u.pathname}`;
    const seg = u.pathname.split('/').filter(Boolean);
    const ti = seg.findIndex(x => /^\d{12}$/.test(x));
    if (ti >= 0 && /^[0-9a-f]{32}$/i.test(seg[ti + 1] || '')) {
      const payload = seg.slice(ti + 2);
      if (payload.length) {
        payload[payload.length - 1] = payload[payload.length - 1].split('!')[0].split('?')[0];
        return `https://ci.xiaohongshu.com/${payload.join('/')}`;
      }
    }
  } catch {}
  return raw.replace(/^http:/i, 'https:');
}

function isImage(buf, contentType) {
  if (!buf || buf.length < 1024) return false;
  const h = buf.subarray(0, 16);
  const jpg = h[0] === 0xff && h[1] === 0xd8;
  const png = h[0] === 0x89 && h[1] === 0x50 && h[2] === 0x4e && h[3] === 0x47;
  const webp = h.subarray(0, 4).toString('ascii') === 'RIFF' && h.subarray(8, 12).toString('ascii') === 'WEBP';
  return jpg || png || webp || /^image\//i.test(contentType || '');
}

function isMp4(buf, contentType) {
  if (!buf || buf.length < 128 * 1024) return false;
  const head = buf.subarray(0, 64).toString('latin1');
  return head.includes('ftyp') || /^video\//i.test(contentType || '') || /octet-stream/i.test(contentType || '');
}

async function openNote(browser, url, id) {
  const context = await browser.newContext({
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 2.625,
    isMobile: true,
    hasTouch: true,
    locale: 'zh-CN',
    userAgent: UA
  });
  const page = await context.newPage();
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 35000 });
  await page.waitForFunction(() => Boolean(window.__INITIAL_STATE__?.noteData?.data?.noteData), null, { timeout: 15000 });
  await page.waitForTimeout(1200);
  await page.screenshot({ path: path.join(OUT, `${id}-page.png`), fullPage: true });
  const note = await page.evaluate(() => {
    const n = window.__INITIAL_STATE__?.noteData?.data?.noteData;
    if (!n) return null;
    return JSON.parse(JSON.stringify(n));
  });
  return { context, page, note };
}

async function getBytes(context, url, referer = '') {
  const variants = [
    { Referer: referer, 'User-Agent': UA, Accept: '*/*', 'Accept-Encoding': 'identity' },
    { 'User-Agent': UA, Accept: '*/*', 'Accept-Encoding': 'identity' }
  ];
  let last = { status: 0, contentType: '', body: Buffer.alloc(0), error: '' };
  for (const headers of variants) {
    try {
      const res = await context.request.get(url, { headers, timeout: 45000, failOnStatusCode: false, maxRedirects: 10 });
      const body = Buffer.from(await res.body());
      const contentType = res.headers()['content-type'] || '';
      last = { status: res.status(), contentType, body, error: '' };
      if (res.status() >= 200 && res.status() < 300 && body.length > 0) return last;
    } catch (e) {
      last = { status: 0, contentType: '', body: Buffer.alloc(0), error: String(e?.message || e) };
    }
  }
  return last;
}

function ffprobe(file) {
  try {
    const raw = execFileSync('ffprobe', ['-v', 'error', '-show_entries', 'format=duration,size:stream=index,codec_name,codec_type,width,height', '-of', 'json', file], { encoding: 'utf8' });
    return JSON.parse(raw);
  } catch (e) {
    return { error: String(e?.message || e) };
  }
}

function extractFrames(file, prefix) {
  for (const sec of [5, 35, 65]) {
    const out = path.join(OUT, `${prefix}-${sec}s.jpg`);
    try {
      execFileSync('ffmpeg', ['-y', '-loglevel', 'error', '-ss', String(sec), '-i', file, '-frames:v', '1', '-q:v', '2', out]);
    } catch {}
  }
}

const browser = await chromium.launch({ headless: true });

// 1) Image regression: exact structured list must remain 7 and every original URL must download as an image.
{
  console.log('\n===== 7图笔记回归 =====');
  let session;
  try {
    session = await openNote(browser, IMAGE_URL, 'image-7');
    const { context, page, note } = session;
    const list = Array.isArray(note?.imageList) ? note.imageList : [];
    logResult('7图笔记：结构化正文图数量', list.length === 7, `期望 7，实际 ${list.length}`);
    logResult('7图笔记：noteId', Boolean(note?.noteId), String(note?.noteId || '(空)'));
    logResult('7图笔记：文案', Boolean(String(note?.title || note?.desc || '').trim()), `title=${String(note?.title || '').slice(0, 60)}`);

    const downloads = [];
    for (let i = 0; i < list.length; i++) {
      const url = originalImageUrl(list[i]);
      const r = await getBytes(context, url, page.url());
      const ok = r.status >= 200 && r.status < 300 && isImage(r.body, r.contentType);
      downloads.push({ index: i + 1, url, status: r.status, bytes: r.body.length, contentType: r.contentType, ok, error: r.error });
      logResult(`正文原图 ${i + 1} 可下载`, ok, `HTTP ${r.status} · ${r.body.length} bytes · ${r.contentType}`);
      if (ok) fs.writeFileSync(path.join(OUT, `image-${i + 1}.bin`), r.body);
    }
    fs.writeFileSync(path.join(OUT, 'image-downloads.json'), JSON.stringify(downloads, null, 2));
    await context.close();
  } catch (e) {
    logResult('7图笔记：页面/结构化数据', false, String(e?.message || e));
    try { await session?.context?.close(); } catch {}
  }
}

// 2) Video regression: caption + cover + originVideoKey + actual original video bytes.
{
  console.log('\n===== 视频笔记回归 =====');
  let session;
  try {
    session = await openNote(browser, VIDEO_URL, 'video-1');
    const { context, page, note } = session;
    const caption = [note?.title, note?.desc].filter(Boolean).join('\n').trim();
    const coverItem = Array.isArray(note?.imageList) ? note.imageList[0] : null;
    const coverUrl = originalImageUrl(coverItem);
    const originKey = String(note?.video?.consumer?.originVideoKey || '').trim().replace(/^\/+/, '');
    const originUrl = originKey ? `https://sns-video-bd.xhscdn.com/${originKey}` : '';
    const h264 = note?.video?.media?.stream?.h264 || [];
    const playbackUrl = clean(h264?.[0]?.masterUrl || h264?.[0]?.master_url || '').replace(/^http:/i, 'https:');

    logResult('视频笔记：文案', Boolean(caption), `${caption.length} chars`);
    logResult('视频笔记：封面字段', Boolean(coverUrl), coverUrl || '(空)');
    logResult('视频笔记：originVideoKey', Boolean(originKey), originKey || '(空)');

    if (coverUrl) {
      const r = await getBytes(context, coverUrl, page.url());
      const ok = r.status >= 200 && r.status < 300 && isImage(r.body, r.contentType);
      logResult('视频封面原图可下载', ok, `HTTP ${r.status} · ${r.body.length} bytes · ${r.contentType}`);
      if (ok) fs.writeFileSync(path.join(OUT, 'video-cover.bin'), r.body);
    }

    if (originUrl) {
      const r = await getBytes(context, originUrl, page.url());
      const ok = r.status >= 200 && r.status < 300 && isMp4(r.body, r.contentType);
      logResult('originVideoKey 原视频可下载', ok, `HTTP ${r.status} · ${r.body.length} bytes · ${r.contentType}`);
      if (ok) {
        const file = path.join(OUT, 'video-origin.mp4');
        fs.writeFileSync(file, r.body);
        const meta = ffprobe(file);
        fs.writeFileSync(path.join(OUT, 'video-origin-ffprobe.json'), JSON.stringify(meta, null, 2));
        const videoStream = meta?.streams?.find(x => x.codec_type === 'video');
        const duration = Number(meta?.format?.duration || 0);
        logResult('原视频媒体结构有效', Boolean(videoStream && duration > 1), `${videoStream?.codec_name || '?'} ${videoStream?.width || '?'}x${videoStream?.height || '?'} · ${duration.toFixed(2)}s`);
        extractFrames(file, 'origin');
      }
    }

    // Keep the H5 playback stream only for comparison; it is never allowed to satisfy the no-watermark assertion.
    if (playbackUrl) {
      const r = await getBytes(context, playbackUrl, page.url());
      const ok = r.status >= 200 && r.status < 300 && isMp4(r.body, r.contentType);
      logResult('H5播放流（仅对照）可下载', ok, `HTTP ${r.status} · ${r.body.length} bytes · ${r.contentType}`);
      if (ok) {
        const file = path.join(OUT, 'video-playback.mp4');
        fs.writeFileSync(file, r.body);
        fs.writeFileSync(path.join(OUT, 'video-playback-ffprobe.json'), JSON.stringify(ffprobe(file), null, 2));
        extractFrames(file, 'playback');
      }
    }

    fs.writeFileSync(path.join(OUT, 'video-structured.json'), JSON.stringify({
      noteId: note?.noteId || '', caption, coverUrl, originKey, originUrl, playbackUrl
    }, null, 2));
    await context.close();
  } catch (e) {
    logResult('视频笔记：页面/结构化数据', false, String(e?.message || e));
    try { await session?.context?.close(); } catch {}
  }
}

await browser.close();

const pass = failures.length === 0;
const report = { pass, generatedAt: new Date().toISOString(), results, failures };
fs.writeFileSync(path.join(OUT, 'regression.json'), JSON.stringify(report, null, 2));

const md = ['# XHS 云端回归结果', '', `总体：${pass ? '✅ PASS' : '❌ FAIL'}`, ''];
for (const r of results) md.push(`- ${r.ok ? '✅' : '❌'} **${r.name}** — ${r.detail}`);
md.push('', '> H5 播放流只用于对照，不能替代 originVideoKey 原视频。');
fs.writeFileSync(path.join(OUT, 'summary.md'), md.join('\n'));

if (!pass) process.exitCode = 1;
