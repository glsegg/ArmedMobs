// Downloads the audio track of a Bilibili video, using the public web API with a browser User-Agent and a
// Referer (no login, no cookie). Used by the delivery-11 impact-sound step (README 5v):
//
//   node tools/bili_audio.js BV1j1cZeHEAt tools/spike/work/voice_src/bili/BV1j1cZeHEAt.m4s
//
// It is a separate tool from tools/_dl.js because the CDN answers 403 without the Referer header, and the
// audio URL is not a fixed path: it has to be asked for (view -> cid -> playurl -> dash.audio[0]).
'use strict';
const https = require('https');
const fs = require('fs');
const path = require('path');

const HEADERS = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) '
    + 'Chrome/120.0.0.0 Safari/537.36',
  'Referer': 'https://www.bilibili.com/',
  'Accept': '*/*',
};

const bvid = process.argv[2];
const out = process.argv[3];
if (!bvid || !out) {
  console.error('usage: node tools/bili_audio.js <BVid> <outFile>');
  process.exit(2);
}

function get(url, binary, redirects = 4) {
  return new Promise((resolve, reject) => {
    https.get(url, { headers: HEADERS }, (res) => {
      if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
        res.resume();
        resolve(get(new URL(res.headers.location, url).toString(), binary, redirects - 1));
        return;
      }
      if (res.statusCode !== 200) {
        res.resume();
        reject(new Error(`HTTP ${res.statusCode} for ${url}`));
        return;
      }
      if (binary) {
        const chunks = [];
        res.on('data', (c) => chunks.push(c));
        res.on('end', () => resolve(Buffer.concat(chunks)));
      } else {
        let text = '';
        res.setEncoding('utf8');
        res.on('data', (c) => { text += c; });
        res.on('end', () => resolve(text));
      }
    }).on('error', reject);
  });
}

(async () => {
  const view = JSON.parse(await get(`https://api.bilibili.com/x/web-interface/view?bvid=${bvid}`, false));
  if (view.code !== 0) {
    throw new Error(`view API: code ${view.code} ${view.message}`);
  }
  const { title, duration, cid } = view.data;
  console.log(`title    : ${title}`);
  console.log(`duration : ${duration}s`);
  console.log(`cid      : ${cid}`);
  const play = JSON.parse(await get(
    `https://api.bilibili.com/x/player/playurl?bvid=${bvid}&cid=${cid}&fnval=16&fnver=0&fourk=1`, false));
  if (play.code !== 0) {
    throw new Error(`playurl API: code ${play.code} ${play.message}`);
  }
  const audios = (play.data.dash && play.data.dash.audio) || [];
  if (audios.length === 0) {
    throw new Error('no DASH audio stream in the response');
  }
  const best = audios.sort((a, b) => (b.bandwidth || 0) - (a.bandwidth || 0))[0];
  console.log(`audio    : id=${best.id} ${best.codecs} ${best.bandwidth} bps`);
  const buf = await get(best.baseUrl, true);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.writeFileSync(out, buf);
  console.log(`saved    : ${out} (${buf.length} bytes)`);
})().catch((error) => {
  console.error(`ERR ${error.message}`);
  process.exit(1);
});
