import fs from 'node:fs';
import vm from 'node:vm';
import assert from 'node:assert/strict';

function runParser(file, globalName, stateName, state) {
  const window = { [stateName]: state };
  const context = vm.createContext({ window, URL, location: { pathname: '/explore/0123456789abcdef01234567', href: 'https://example.test/' }, document: { querySelector: () => null, querySelectorAll: () => [] } });
  vm.runInContext(fs.readFileSync(file, 'utf8'), context);
  return window[globalName].extract();
}

const xhs = runParser('app/src/main/assets/xhs_parser.js', 'HDXhsParser', '__INITIAL_STATE__', {
  note: { noteDetailMap: { '0123456789abcdef01234567': { note: {
    noteId: '0123456789abcdef01234567', title: 'live', desc: 'caption',
    imageList: [
      { fileId: 'image-a', livePhoto: { media: { masterUrl: 'https://sns-video-hs.xhscdn.com/stream/a.mp4' } } },
      { fileId: 'image-b', live_photo_info: { video: { url: 'https://sns-video-hs.xhscdn.com/stream/b.mp4' } } }
    ],
    video: { consumer: { originVideoKey: 'must-not-win.mp4' } }
  } } } }
});
assert.equal(xhs.type, 'live_photo');
assert.deepEqual(xhs.livePhotos.map(x => x.motionUrl), [
  'https://sns-video-hs.xhscdn.com/stream/a.mp4',
  'https://sns-video-hs.xhscdn.com/stream/b.mp4'
]);
assert.equal(xhs.originVideoUrl, '');
assert.equal(xhs.images.length, 2);

const douyinImages = runParser('app/src/main/assets/douyin_parser.js', 'HDDouyinParser', '__UNIVERSAL_DATA_FOR_REHYDRATION__', {
  scope: { aweme_detail: { aweme_id: '1234567890123456789', aweme_type: 68, desc: 'album', images: [
    { url_list: ['http://p3.douyinpic.com/a.jpeg'] }, { url_list: ['https://p9.douyinpic.com/b.jpeg'] }
  ] } }
});
assert.equal(douyinImages.type, 'image');
assert.equal(douyinImages.images.length, 2);
assert.equal(douyinImages.caption, 'album');

const douyinVideo = runParser('app/src/main/assets/douyin_parser.js', 'HDDouyinParser', '_ROUTER_DATA', {
  loader: { aweme_id: '9876543210987654321', desc: 'video', video: { play_addr: { url_list: ['https://v.douyin.com/playwm?id=1'] }, cover: { url_list: ['https://p.douyinpic.com/c.jpg'] } } }
});
assert.equal(douyinVideo.type, 'video');
assert.equal(douyinVideo.originVideoUrl, 'https://v.douyin.com/play?id=1');
assert.equal(douyinVideo.cover.originalUrl, 'https://p.douyinpic.com/c.jpg');

console.log('platform parser regression: PASS');
