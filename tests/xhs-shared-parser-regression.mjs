import { chromium } from 'playwright';
import fs from 'node:fs';
import path from 'node:path';

const PARSER = path.resolve('app/src/main/assets/xhs_parser.js');
const OUT = path.resolve('shared-parser-report');
fs.mkdirSync(OUT, { recursive: true });

const CASES = [
  { id: 'image-7', url: 'https://xhslink.cn/o/AsM8OMpdOLO', kind: 'image' },
  {
    id: 'video-1',
    url: 'https://xhslink.cn/o/91iT26p7qax',
    kind: 'video',
    expectedOriginKey: 'pre_post/1040g0cg3239q24ftn2005p3qkr07588e1a7tr98',
    expectedCover: 'https://ci.xiaohongshu.com/1040g2sg3239psn4ln2fg5p3qkr07588e6q24jdg'
  }
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
  return { context, parsed };
}

const browser = await chromium.launch({ headless: true });
for (const tc of CASES) {
  console.log(`\n===== Shared parser: ${tc.id} =====`);
  let session;
  try {
    session = await open(browser, tc);
    const { context, parsed } = session;
    fs.writeFileSync(path.join(OUT, `${tc.id}.json`), JSON.stringify(parsed, null, 2));

    check(`${tc.id}: parser ok`, parsed.ok === true, `source=${parsed.source}`);
    check(`${tc.id}: exact structured source`, parsed.source === 'noteData.data.noteData', `source=${parsed.source}`);
    check(`${tc.id}: caption`, Boolean(parsed.caption), `${String(parsed.caption || '').length} chars`);

    if (tc.kind === 'image') {
      check('image-7: exact image count', parsed.images?.length === 7, `expected=7 actual=${parsed.images?.length || 0}`);
      const originalUrls = (parsed.images || []).map(x => x.originalUrl).filter(Boolean);
      check(
        'image-7: all parser outputs are original image URLs',
        originalUrls.length === 7 && originalUrls.every(url => /^https:\/\/ci\.xiaohongshu\.com\//i.test(url)),
        `originals=${originalUrls.length}`
      );
    } else {
      check('video-1: is video', parsed.isVideo === true, `type=${parsed.type}`);
      check('video-1: cover', Boolean(parsed.cover?.originalUrl), parsed.cover?.originalUrl || '(empty)');
      check('video-1: exact cover URL', parsed.cover?.originalUrl === tc.expectedCover, parsed.cover?.originalUrl || '(empty)');
      check('video-1: originVideoKey', Boolean(parsed.originVideoKey), parsed.originVideoKey || '(empty)');
      check('video-1: exact originVideoKey', parsed.originVideoKey === tc.expectedOriginKey, parsed.originVideoKey || '(empty)');
      check('video-1: origin URL host', /^https:\/\/sns-video-bd\.xhscdn\.com\//i.test(parsed.originVideoUrl || ''), parsed.originVideoUrl || '(empty)');
      check(
        'video-1: origin URL matches key',
        parsed.originVideoUrl === `https://sns-video-bd.xhscdn.com/${tc.expectedOriginKey}`,
        parsed.originVideoUrl || '(empty)'
      );
      check(
        'video-1: playback kept separate',
        Array.isArray(parsed.playbackUrls) && parsed.playbackUrls.length > 0 && !parsed.playbackUrls.includes(parsed.originVideoUrl),
        `playback=${parsed.playbackUrls?.length || 0}`
      );
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
const md = [
  '# 共享解析器在线回归',
  '',
  `总体：${pass ? '✅ PASS' : '❌ FAIL'}`,
  '',
  '`xhs_parser.js` 这里只验证 Android 共用解析器是否输出正确资源地址；实际原图/原视频下载由前一步云端资源回归负责，避免重复下载 32MB 原视频。',
  ''
];
for (const r of results) md.push(`- ${r.ok ? '✅' : '❌'} **${r.name}** — ${r.detail}`);
fs.writeFileSync(path.join(OUT, 'summary.md'), md.join('\n'));
if (!pass) process.exitCode = 1;
