// Selects the source clips for every voice pool and writes the cutting plan.
//
//   node tools/voice_plan.js            writes tools/spike/work/voice_plan.json and prints the table
//   node tools/voice_plan.js --check    only prints (fails when a pool cannot reach its clip count)
//
// The pool assignment itself lives in tools/voice_pools.json - this script only resolves it against the
// extracted source recordings, so re-pointing a pool is a one-line data change and never a code change.
//
// WHY A QUEUE AND NOT A PICK: a take can be too short (a lip smack) or too long (a monologue), and only
// ffmpeg knows that. So each pool gets an ORDERED candidate list (round-robin over the event names, so the
// picks do not all come from one event) and tools/make_family_voice_clips.ps1 walks it until enough takes
// pass the duration filter. Nothing is silently shipped just because it was first in the alphabet.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const TABLE = path.join(__dirname, 'voice_pools.json');
const PLAN = path.join(__dirname, 'spike', 'work', 'voice_plan.json');
const checkOnly = process.argv.includes('--check');

const table = JSON.parse(fs.readFileSync(TABLE, 'utf8'));

/** The event token of a source file: strip the family prefix and the trailing index (plus its variant). */
function eventOf(fileName, family) {
  let name = fileName.replace(/\.ogg$/i, '');
  const pattern = family.indexPattern;
  if (pattern === '^(.*?)_\\d+$') {
    // The elite names carry a Chinese category prefix ("友军火力-friendlyfire_1"): strip the index only.
    name = name.replace(/_\d+$/, '');
    return name;
  }
  name = name.replace(new RegExp(pattern), '');
  // "agony1" -> "agony"; "ambush1_l" -> "ambush"; "attention_01_n" -> "attention"
  return name.replace(/[_\-]?\d+([_\-][a-z]{1,2})?$/, '');
}

/** Every .ogg under the family's source dirs, with its event token, sorted by name (deterministic). */
function candidates(familyName) {
  const family = table.families[familyName];
  const found = [];
  for (const rel of family.sourceDirs) {
    const dir = path.join(ROOT, rel);
    if (!fs.existsSync(dir)) {
      throw new Error(`source dir missing: ${rel} - run the extraction step first`);
    }
    for (const file of fs.readdirSync(dir)) {
      if (!file.toLowerCase().endsWith('.ogg')) continue;
      found.push({
        family: familyName,
        file,
        path: path.join(dir, file),
        event: eventOf(file, family),
      });
    }
  }
  found.sort((a, b) => (a.file < b.file ? -1 : a.file > b.file ? 1 : 0));
  return found;
}

/** Round-robin over the event tokens, so the queue alternates instead of exhausting one event first. */
function queueFor(all, tokens, limit) {
  const byToken = tokens.map((token) => ({
    token,
    files: all.filter((c) => c.event.startsWith(token)),
  }));
  const queue = [];
  let added = true;
  let round = 0;
  while (added && queue.length < limit) {
    added = false;
    for (const group of byToken) {
      if (round < group.files.length) {
        queue.push(group.files[round]);
        added = true;
        if (queue.length >= limit) break;
      }
    }
    round++;
  }
  return queue;
}

const wanted = table.perCategoryClips;
const limit = wanted * table.candidateMultiplier;
const plan = { perCategoryClips: wanted, pools: [], counts: {} };
let short = 0;

console.log(`pool            candidates  queued  events (candidate count per event token)`);
for (const [familyName, family] of Object.entries(table.families)) {
  const all = candidates(familyName);
  plan.counts[familyName] = all.length;
  for (const [category, tokens] of Object.entries(family.categories)) {
    const queue = queueFor(all, tokens, limit);
    const perEvent = tokens
      .map((t) => `${t}=${all.filter((c) => c.event.startsWith(t)).length}`)
      .join(' ');
    const pool = `${familyName}_${category}`;
    console.log(`  ${pool.padEnd(14)} ${String(queue.length).padStart(9)} ${String(queue.length).padStart(7)}  ${perEvent}`);
    if (queue.length < wanted) {
      short++;
      console.log(`        !! only ${queue.length} candidate(s) for ${wanted} clip(s)`);
    }
    plan.pools.push({
      pool,
      family: familyName,
      category,
      wanted,
      candidates: queue.map((c) => ({ file: c.file, event: c.event, path: c.path })),
    });
  }
}
plan.minSeconds = table.minSeconds;
plan.maxSeconds = table.maxSeconds;
plan.generatedFrom = 'tools/voice_pools.json';
plan.sourceCounts = plan.counts;

console.log('');
console.log(`source files: ${Object.entries(plan.counts).map(([k, v]) => `${k}=${v}`).join(' ')}`);
const totalWanted = plan.pools.length * wanted;
console.log(`${plan.pools.length} pool(s) x ${wanted} clip(s) = ${totalWanted} clip(s) to cut`);

if (checkOnly) {
  if (short > 0) {
    console.log(`${short} pool(s) cannot reach ${wanted} clips`);
    process.exit(1);
  }
  console.log('every pool has enough candidates');
  process.exit(0);
}

fs.mkdirSync(path.dirname(PLAN), { recursive: true });
fs.writeFileSync(PLAN, JSON.stringify(plan, null, 2));
console.log(`wrote ${path.relative(ROOT, PLAN)}`);
if (short > 0) {
  process.exit(1);
}
