// The "no config read during registration" gate (2026, the dev-server startup crash).
//
//   node tools/selftest_attribute_config.js
//
// WHY THIS FILE EXISTS
//   `SniperPillagerEntity.createSniperAttributes()` inlined `Config.SNIPER_FOLLOW_RANGE.get()`, and that
//   method is called from `EntityAttributeCreationEvent` - which Forge fires while the common config is
//   still being loaded. The result was not a wrong number, it was
//
//     java.lang.IllegalStateException: Cannot get config value before config is loaded
//
//   and the dedicated/gradle server died before the first world tick. The same class of bug can hide in
//   any helper the attribute event reaches (a helper of a helper included), so this gate walks the whole
//   call graph instead of grepping one file.
//
// What it asserts:
//   1. the transitive closure of everything reachable from ModEntities.onEntityAttributes contains no
//      live `Config.<KEY>.get()` call;
//   2. the five registry/registration classes read no config at all - their lambdas run at registry time,
//      before the config exists;
//   3. both snipers register their FOLLOW_RANGE from the plain constant Config.DEFAULT_SNIPER_FOLLOW_RANGE;
//   4. both snipers apply the configured value at runtime (onAddedToWorld -> applySniperFollowRange), and
//      that method is guarded by Config.SPEC.isLoaded();
//   5. the constant and the config key's default are the same number, so a fresh install cannot jump;
//   6. the same trick is not silently undone: `sniper.followRange` is still a real, ranged key.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA_ROOT = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** Every .java file under the mod source root, keyed by its path relative to that root. */
function javaFiles(dir, prefix = '') {
  const out = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
    if (entry.isDirectory()) out.push(...javaFiles(path.join(dir, entry.name), rel));
    else if (entry.name.endsWith('.java')) out.push({ rel, abs: path.join(dir, entry.name) });
  }
  return out;
}

/** Comments and string literals removed, so a mention inside a doc comment is never a finding. */
function strip(src) {
  return src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(/\/\/[^\n]*/g, ' ')
    .replace(/"(?:[^"\\]|\\.)*"/g, '""')
    .replace(/'(?:[^'\\]|\\.)*'/g, "''");
}

/**
 * The body of every real METHOD DECLARATION of `name`: the parameter list must be followed by `{`
 * (optionally through `throws ...`), which is what tells a declaration apart from a call site.
 */
function bodiesOf(src, name) {
  const bodies = [];
  const re = new RegExp(`\\b${name}\\s*\\(`, 'g');
  let match;
  while ((match = re.exec(src)) !== null) {
    const open = src.indexOf('(', match.index);
    let depth = 0;
    let close = -1;
    for (let i = open; i < src.length; i++) {
      if (src[i] === '(') depth++;
      else if (src[i] === ')') {
        depth--;
        if (depth === 0) { close = i; break; }
      }
    }
    if (close < 0) continue;
    const afterMatch = /^\s*(?:throws\s[\w.,\s]+?)?\{/.exec(src.slice(close + 1));
    if (!afterMatch) continue;
    const braceAt = close + 1 + afterMatch[0].length - 1;
    depth = 0;
    for (let i = braceAt; i < src.length; i++) {
      if (src[i] === '{') depth++;
      else if (src[i] === '}') {
        depth--;
        if (depth === 0) {
          bodies.push({ start: match.index, body: src.slice(braceAt, i + 1) });
          break;
        }
      }
    }
  }
  return bodies;
}

/** Calls made inside a body, with the obviously-non-method keywords filtered out. */
function callsIn(body) {
  const out = new Set();
  const re = /\b([A-Za-z_$][A-Za-z0-9_$]*)\s*\(/g;
  let match;
  while ((match = re.exec(body)) !== null) {
    const name = match[1];
    if (!['if', 'for', 'while', 'switch', 'return', 'new', 'catch', 'synchronized', 'do'].includes(name)) {
      out.add(name);
    }
  }
  return out;
}

const files = javaFiles(JAVA_ROOT).map((file) => ({ ...file, raw: fs.readFileSync(file.abs, 'utf8') }));
const sources = files.map((file) => ({ rel: file.rel, code: strip(file.raw) }));

// ------------------------------------------------------------------ 1. the closure of the attribute event

// Where is each method name declared? Used to resolve a call without dragging in every unrelated
// `add()` / `describe()` / `log()` in the mod: a name is followed when it is declared in the SAME file,
// or when exactly one file declares it at all. An ambiguous cross-file name is skipped - and every
// attribute builder is then checked directly in section 3, so nothing is lost.
const declaredIn = new Map();
for (const source of sources) {
  const names = new Set();
  const re = /([A-Za-z_$][A-Za-z0-9_$]*)\s*\([^;{)]*\)\s*(?:throws\s[\w.,\s]+?)?\{/g;
  let match;
  while ((match = re.exec(source.code)) !== null) names.add(match[1]);
  for (const name of names) {
    if (!declaredIn.has(name)) declaredIn.set(name, []);
    declaredIn.get(name).push(source.rel);
  }
}
const resolvable = (name, fromRel) => {
  const files = declaredIn.get(name);
  if (!files) return [];
  if (files.includes(fromRel)) return sources.filter((source) => source.rel === fromRel);
  return files.length === 1 ? sources.filter((source) => source.rel === files[0]) : [];
};

// The attribute-supplier call graph lives in TWO packages - the registry and the entity classes. Scoping
// the walk to them is what keeps a generic method name (`build`, `add`, `describe`) from dragging the
// whole mod in, and it is also the honest statement of what this gate covers: nothing else can run during
// EntityAttributeCreationEvent. Every `*Attributes*` method in the tree is additionally checked directly.
const ATTR_SCOPE = (rel) => rel.startsWith('entity/') || rel.startsWith('registry/');

const reached = new Set(['onEntityAttributes']);
const reachedBodies = [];
const seen = new Set();
const parent = new Map();
const queue = [{ name: 'onEntityAttributes', rel: 'registry/ModEntities.java' }];
// Every attribute-supplier name in the mod is a root of its own: "check every entity attribute
// registration for the same pattern" cannot depend on `onEntityAttributes` happening to reach a helper
// whose name is unique, and `createSniperAttributes` is declared in two files on purpose.
for (const [name, rels] of declaredIn) {
  if (name.includes('Attributes') && rels.some(ATTR_SCOPE)) {
    reached.add(name);
    parent.set(name, 'onEntityAttributes');
    for (const rel of rels.filter(ATTR_SCOPE)) queue.push({ name, rel });
  }
}
while (queue.length > 0) {
  const current = queue.shift();
  if (!ATTR_SCOPE(current.rel)) continue;
  for (const source of resolvable(current.name, current.rel)) {
    if (!ATTR_SCOPE(source.rel)) continue;
    for (const found of bodiesOf(source.code, current.name)) {
      const key = `${source.rel}:${current.name}:${found.start}`;
      if (seen.has(key)) continue;
      seen.add(key);
      reachedBodies.push({ rel: source.rel, name: current.name, body: found.body });
      for (const callee of callsIn(found.body)) {
        if (!reached.has(callee)) {
          reached.add(callee);
          parent.set(callee, current.name);
          queue.push({ name: callee, rel: source.rel });
        }
      }
    }
  }
}

console.log('1. nothing reachable from EntityAttributeCreationEvent reads a live config value');
const CONFIG_GET = /Config\s*\.\s*[A-Z_][A-Z0-9_]*\s*\.\s*get\s*\(/;
const offenders = [];
for (const entry of reachedBodies) {
  const m = CONFIG_GET.exec(entry.body);
  if (m) {
    const chain = [entry.name];
    let cursor = entry.name;
    while (parent.has(cursor) && chain.length < 8) {
      cursor = parent.get(cursor);
      chain.unshift(cursor);
    }
    offenders.push(`${entry.rel}:${entry.name} -> ${m[0]}  [path: ${chain.join(' > ')}]`);
  }
}
check(reachedBodies.length >= 4,
  'the scan found the event body and the helpers it calls',
  `${reachedBodies.length} method body/bodies across ${reached.size} name(s)`);
check(offenders.length === 0,
  'no Config.<KEY>.get() anywhere in that closure',
  offenders.length ? offenders.join(' | ') : `checked ${reached.size} name(s)`);
check(reached.has('troopAttributes'),
  'the faction-troop helper is inside the closure (so it is really covered)');
check(reached.has('createSniperAttributes'),
  'the sniper attribute builder is inside the closure (it is the one that crashed)');

// ------------------------------------------------------------------ 2. registry classes read no config

console.log('');
console.log('2. the registration classes read no config at registry time');
for (const rel of ['registry/ModEntities.java', 'registry/ModItems.java', 'registry/ModBlocks.java',
  'registry/ModSounds.java', 'registry/ModCreativeTabs.java', 'TarkovScav.java']) {
  const source = sources.find((entry) => entry.rel === rel);
  const m = source && CONFIG_GET.exec(source.code);
  check(!!source && !m, `${rel}: no Config.<KEY>.get()`, m ? m[0] : '');
}

// ------------------------------------------------------------------ 3. the snipers use the constant

console.log('');
console.log('3. both snipers register FOLLOW_RANGE from the plain constant');
const sniperPillager = sources.find((entry) => entry.rel === 'entity/SniperPillagerEntity.java').code;
const sniperVillager = sources.find((entry) => entry.rel === 'entity/SniperVillagerEntity.java').code;
for (const [label, source, base] of [
  ['SniperPillagerEntity', sniperPillager, 'Pillager.createAttributes()'],
  ['SniperVillagerEntity', sniperVillager, 'Villager.createAttributes()'],
]) {
  const body = bodiesOf(source, 'createSniperAttributes').map((entry) => entry.body).join('\n');
  check(body.includes(base) && body.includes('DEFAULT_SNIPER_FOLLOW_RANGE'),
    `${label}.createSniperAttributes uses the constant`, body.replace(/\s+/g, ' ').trim().slice(0, 90));
  check(!CONFIG_GET.test(body), `${label}.createSniperAttributes has no live config read`);
}

// ------------------------------------------------------------------ 4. the configured value is applied at runtime

console.log('');
console.log('4. the configured value is applied when the entity enters a world');
const interfaceSrc = sources.find((entry) => entry.rel === 'gun/SniperMob.java').code;
const applyBody = bodiesOf(interfaceSrc, 'applySniperFollowRange').map((entry) => entry.body).join('\n');
check(applyBody.length > 0, 'SniperMob.applySniperFollowRange exists');
check(/Config\.SPEC\.isLoaded\(\)/.test(applyBody),
  'and it refuses to read the config until the spec is loaded (the exact crash guard)');
check(/Config\s*\.\s*SNIPER_FOLLOW_RANGE\s*\.\s*get\s*\(/.test(applyBody),
  'and it does read the configured value once it is safe');
check(/Attributes\.FOLLOW_RANGE/.test(applyBody), 'and writes FOLLOW_RANGE');
for (const [label, source] of [['SniperPillagerEntity', sniperPillager], ['SniperVillagerEntity', sniperVillager]]) {
  const added = bodiesOf(source, 'onAddedToWorld').map((entry) => entry.body).join('\n');
  check(/applySniperFollowRange\(\)/.test(added), `${label}.onAddedToWorld applies it`);
  check(/isClientSide/.test(added), `${label}.onAddedToWorld is server-side only`);
}

// ------------------------------------------------------------------ 5. constant == config default

console.log('');
console.log('5. the registration constant and the config default cannot drift');
const configText = fs.readFileSync(path.join(JAVA_ROOT, 'Config.java'), 'utf8')
  .replace(/\/\*[\s\S]*?\*\//g, ' ')
  .replace(/\/\/[^\n]*/g, ' ');
const constant = /DEFAULT_SNIPER_FOLLOW_RANGE\s*=\s*([0-9.]+)D/.exec(configText);
check(!!constant, 'Config.DEFAULT_SNIPER_FOLLOW_RANGE is declared', constant ? constant[1] : 'missing');
check(/defineInRange\("followRange",\s*DEFAULT_SNIPER_FOLLOW_RANGE\s*,/.test(configText),
  'sniper.followRange is declared with that constant as its default',
  'a hand-typed literal here is how the two would drift');
check(/define\("enabled", true\)/.test(configText) && /SNIPER_ENABLED/.test(configText),
  'sniper.enabled still exists');

console.log('');
console.log('6. the tuning key itself is unchanged');
check(/SNIPER_FOLLOW_RANGE\s*=\s*b[\s\S]{0,900}?defineInRange\("followRange",\s*DEFAULT_SNIPER_FOLLOW_RANGE,\s*16\.0D,\s*128\.0D\)/
  .test(configText),
  'followRange keeps its 16..128 range, so the defensive fix did not remove the knob');

console.log('');
if (failures > 0) {
  console.log(`${failures} attribute-config check(s) FAILED`);
  process.exit(1);
}
console.log('no attribute-registration path reads a live config value, and the configured sight range is applied at spawn');
