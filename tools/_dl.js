// Minimal downloader used by the recon steps in this project: node is the only tool allowed to
// reach the network here, and PowerShell's Invoke-WebRequest has been unreliable on this machine.
//
//   node tools/_dl.js <url> <outFile>
const https = require('https');
const http = require('http');
const fs = require('fs');

const url = process.argv[2];
const out = process.argv[3];
if (!url || !out) {
  console.error('usage: node tools/_dl.js <url> <outFile>');
  process.exit(2);
}

const get = (u, redirects) => {
  const mod = u.startsWith('https:') ? https : http;
  mod.get(u, (res) => {
    if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location && redirects > 0) {
      res.resume();
      return get(new URL(res.headers.location, u).toString(), redirects - 1);
    }
    console.log(`status=${res.statusCode} ${u}`);
    if (res.statusCode !== 200) {
      res.resume();
      process.exit(1);
    }
    const file = fs.createWriteStream(out);
    res.pipe(file);
    file.on('finish', () => console.log(`saved ${out} ${fs.statSync(out).size} bytes`));
  }).on('error', (e) => {
    console.error(`ERR ${e.message}`);
    process.exit(1);
  });
};

get(url, 5);
