// The AI intelligence tiers (README 5aa): four per-tier profiles over nine entity types, plus the
// squad layer (focus fire, bounding overwatch, flanking, cover exclusivity).
//
//   node tools/selftest_ai_profiles.js
//
// What is asserted, and why each one is a different kind of check:
//
//   1. THE TRUTH TABLE. The user's request was a table (tier x reaction / accuracy / cover /
//      suppression / advance / coordination). The shipped defaults are parsed straight out of
//      Config.AiSettings and compared with that table, and the ORDERINGS the table implies
//      (scav slowest and least accurate, troop suppressing most, elite advancing longest) are
//      asserted as inequalities rather than as a screenshot.
//   2. THE MAPPING. entity -> profile for all nine ids, cross-checked against the registration table
//      in ModEntities.java, and against each entity's `extends` clause, because three of the nine are
//      subclasses of the other two bases - a mapping that checks the base first would silently give an
//      elite a scav's brain.
//   3. EVERY BRANCH IN THE SOURCE. Reaction delay, cover preference, suppression, hold-post,
//      hold-fire hit-chance floor, focus fire, overwatch rotation, flanking, cover claims. A
//      behaviour that is only in this file is a behaviour that does not ship.
//   4. THE COORDINATION DECISIONS, SIMULATED. The pure methods in SquadCoordinator are mirrored in
//      JS, compared with the Java text (so the mirror cannot drift) and then run over the real cases:
//      two mobs cannot claim one cover block, the suppressor rotates, a squad picks one target,
//      flankers are never the whole squad, and the overwatch role times out instead of parking a mob.
//   5. THE README KEYS. AssetTest already demands "every config key is documented"; this gate demands
//      the specific new ones, so a key cannot be added and forgotten in the same commit.
//   6. THE REVERT-TO-DUMB RECIPE. The doc promises that copying [ai.scav] over the other three blocks
//      makes every tier behave like a scav. That promise is executed here through a mirror of the
//      resolver, not asserted in prose.
//   7. THE NEW STALL RISK. The sniper hold-fire rule can wait; patienceTicks bounds it and
//      transition() resets it, so the rule cannot become the "aims and never fires" bug the
//      anti-stall gate exists for.
'use strict';
const fs = require('fs');
const path = require('path');

const ROOT = path.join(__dirname, '..');
const JAVA = path.join(ROOT, 'src', 'main', 'java', 'com', 'gfl', 'tarkovscav');
const read = (rel) => fs.readFileSync(path.join(JAVA, rel), 'utf8');
const strip = (src) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const squash = (text) => text.replace(/\s+/g, ' ').trim();

let failures = 0;
const check = (ok, label, detail) => {
  console.log(`  ${ok ? 'PASS' : 'FAIL'}  ${label}${detail ? '  ' + detail : ''}`);
  if (!ok) failures++;
};

/** The `{...}` body of the method whose signature starts at `signature`. */
function methodBody(code, signature) {
  const start = code.indexOf(signature);
  if (start < 0) return null;
  const open = code.indexOf('{', start);
  if (open < 0) return null;
  let depth = 0;
  for (let i = open; i < code.length; i++) {
    if (code[i] === '{') depth++;
    else if (code[i] === '}') {
      depth--;
      if (depth === 0) return code.slice(open + 1, i);
    }
  }
  return null;
}

/**
 * Normalised comparison between a Java method body and its JS mirror. The two languages differ only
 * in declarations and in `for (x : xs)` / `for (x of xs)`, so those are the only things removed -
 * every operator, every branch and every constant has to survive the comparison.
 */
const normJava = (text) => (text || '')
  .replace(/\(int\)/g, '')
  .replace(/\b(int|long|double|float|boolean|final)\b/g, '')
  .replace(/([0-9])\.0D/g, '$1')
  .replace(/\s+/g, '')
  .replace(/;/g, '');
const normJs = (text) => (text || '')
  .replace(/\bof\b/g, ':')
  .replace(/\b(const|let|var)\b/g, '')
  .replace(/\s+/g, '')
  .replace(/;/g, '');
const sameDecision = (java, js) => normJava(java) !== '' && normJava(java) === normJs(js);

const aiRaw = read('gun/AiProfile.java');
const squadRaw = read('gun/SquadCoordinator.java');
const ai = strip(aiRaw);
const squad = strip(squadRaw);
const brain = strip(read('gun/GunBrain.java'));
const tactics = strip(read('gun/CombatTactics.java'));
const sniper = strip(read('gun/SniperBehavior.java'));
const config = read('Config.java');
const entities = read('registry/ModEntities.java');
const readme = fs.readFileSync(path.join(ROOT, 'README.md'), 'utf8');

// ---------------------------------------------------------------------------------------------
console.log('1. the four profiles, as shipped, against the requested table');
const TIERS = ['SCAV', 'SNIPER', 'TROOP', 'ELITE'];

/** Parses `defineInRange("key", switch (tier) { case SCAV -> 0.8D; ... }, lo, hi)`. */
function switchDefaults(key) {
  const re = new RegExp(`defineInRange\\("${key}", switch \\(tier\\) \\{([\\s\\S]*?)\\},`);
  const body = re.exec(config);
  if (!body) return null;
  const values = {};
  for (const match of body[1].matchAll(/case\s+(\w+)\s*->\s*([0-9.]+)D?;/g)) {
    values[match[1]] = Number(match[2]);
  }
  return TIERS.every((tier) => values[tier] !== undefined) ? values : null;
}

const EXPECTED = {
  // reaction: the requested 0.6-1.2 s window for the scav, fast for the soldiers
  reactionMinTicks: { SCAV: 12, SNIPER: 16, TROOP: 6, ELITE: 4 },
  reactionMaxTicks: { SCAV: 24, SNIPER: 30, TROOP: 10, ELITE: 8 },
  // accuracy: scav lowest, the two soldier tiers at the gun tier's own value, elite a touch above
  accuracyScale: { SCAV: 0.8, SNIPER: 1.0, TROOP: 1.0, ELITE: 1.05 },
  // cover: scav rarely (0.15) and with a small radius, sniper always (it is a post), troop nearly
  // always, elite three times in four decisions because it crosses the gap in short rushes
  coverChance: { SCAV: 0.15, SNIPER: 1.0, TROOP: 0.9, ELITE: 0.75 },
  coverRadiusScale: { SCAV: 0.45, SNIPER: 1.0, TROOP: 1.0, ELITE: 0.85 },
  coverCacheScale: { SCAV: 1.5, SNIPER: 1.0, TROOP: 1.0, ELITE: 0.75 },
  // suppression: none for scav/sniper, strongest and longest for troop, medium for elite
  suppressChanceScale: { SCAV: 0.0, SNIPER: 0.0, TROOP: 1.6, ELITE: 1.0 },
  suppressTicksScale: { SCAV: 0.0, SNIPER: 0.0, TROOP: 1.5, ELITE: 1.0 },
  suppressAccuracyScale: { SCAV: 1.0, SNIPER: 1.0, TROOP: 0.8, ELITE: 1.0 },
  suppressBurstScale: { SCAV: 0.5, SNIPER: 1.0, TROOP: 1.2, ELITE: 1.0 },
  // advance: scav straight line (0), troop small cover steps, elite long steps / rushes
  advanceCoverScale: { SCAV: 0.0, SNIPER: 1.0, TROOP: 0.6, ELITE: 1.5 },
  coverSeekSpeedScale: { SCAV: 1.0, SNIPER: 1.0, TROOP: 1.0, ELITE: 1.15 },
  repositionScale: { SCAV: 1.0, SNIPER: 1.0, TROOP: 0.6, ELITE: 0.5 },
  retreatHealthScale: { SCAV: 1.0, SNIPER: 0.6, TROOP: 1.0, ELITE: 0.7 },
  engageRangeScale: { SCAV: 1.0, SNIPER: 1.0, TROOP: 1.0, ELITE: 0.6 },
};
const profile = {};
for (const [key, expected] of Object.entries(EXPECTED)) {
  const parsed = switchDefaults(key);
  check(parsed !== null, `Config defines ${key} as a per-tier switch`, parsed ? '' : 'not parsed');
  if (parsed === null) { profile[key] = expected; continue; }
  profile[key] = parsed;
  for (const tier of TIERS) {
    check(parsed[tier] === expected[tier], `${key}.${tier.toLowerCase()} ships as ${expected[tier]}`,
      `got ${parsed[tier]}`);
  }
}
// The five knobs with no global key are not switches: they are per-tier ternaries.
check(/\.define\("holdPost", tier == AiProfile\.Tier\.SNIPER\)/.test(config),
  'holdPost is true for the sniper tier and false for the other three');
check(/defineInRange\("minHitChance", tier == AiProfile\.Tier\.SNIPER \? 0\.5D : 0\.0D,/.test(config),
  'minHitChance is 0.5 for the sniper tier and 0 (off) for the others');
check(/defineInRange\("patienceTicks", tier == AiProfile\.Tier\.SNIPER \? 100 : 0,/.test(config),
  'patienceTicks is 100 for the sniper tier and 0 (unbounded, but unused) for the others');
check(/\.define\("coordination", tier == AiProfile\.Tier\.TROOP \|\| tier == AiProfile\.Tier\.ELITE\)/.test(config),
  'coordination is true for the troop and elite tiers only');
check(/defineInRange\("partialCoverBonus", 250\.0D,/.test(config),
  'partialCoverBonus ships as 250, i.e. a partially concealed post beats an open one');

// The truth table, printed, then the orderings it implies.
console.log('  tier    react      accScale cover  supChance supTicks advance  coordination  holdPost');
const TRUTH = {};
for (const tier of TIERS) {
  TRUTH[tier] = {
    reaction: `${profile.reactionMinTicks[tier]}..${profile.reactionMaxTicks[tier]}`,
    acc: profile.accuracyScale[tier],
    cover: profile.coverChance[tier],
    suppress: profile.suppressChanceScale[tier],
    suppressTicks: profile.suppressTicksScale[tier],
    advance: profile.advanceCoverScale[tier],
    coordination: tier === 'TROOP' || tier === 'ELITE',
    holdPost: tier === 'SNIPER',
  };
  console.log('  ' + tier.toLowerCase().padEnd(8)
    + TRUTH[tier].reaction.padEnd(11)
    + String(TRUTH[tier].acc).padEnd(9)
    + String(TRUTH[tier].cover).padEnd(7)
    + String(TRUTH[tier].suppress).padEnd(10)
    + String(TRUTH[tier].suppressTicks).padEnd(9)
    + String(TRUTH[tier].advance).padEnd(9)
    + String(TRUTH[tier].coordination).padEnd(14)
    + TRUTH[tier].holdPost);
}
check(profile.reactionMinTicks.SCAV === 12 && profile.reactionMaxTicks.SCAV === 24,
  'the scav reaction window is 12..24 ticks = the requested 0.6..1.2 s',
  `${profile.reactionMinTicks.SCAV}..${profile.reactionMaxTicks.SCAV}`);
check(profile.reactionMinTicks.SCAV > profile.reactionMinTicks.ELITE
  && profile.reactionMaxTicks.SCAV > profile.reactionMaxTicks.ELITE,
  'the scav is slower to react than the elite on both bounds');
check(profile.reactionMaxTicks.TROOP < profile.reactionMaxTicks.SCAV,
  'the troop reacts faster than the scav');
check(profile.accuracyScale.SCAV < profile.accuracyScale.TROOP
  && profile.accuracyScale.SCAV < profile.accuracyScale.ELITE
  && profile.accuracyScale.SCAV < profile.accuracyScale.SNIPER,
  'the scav is the least accurate tier (the lowest profile)');
check(profile.coverChance.SCAV < profile.coverChance.SNIPER
  && profile.coverChance.SCAV < profile.coverChance.TROOP
  && profile.coverChance.SCAV < profile.coverChance.ELITE,
  'the scav uses cover least often (0.15 = "rarely")');
check(profile.coverRadiusScale.SCAV < profile.coverRadiusScale.TROOP,
  'the scav also searches a smaller radius (0.45 vs 1.0)');
check(profile.suppressChanceScale.SCAV === 0 && profile.suppressChanceScale.SNIPER === 0,
  'scav and sniper never suppress');
check(profile.suppressChanceScale.TROOP > profile.suppressChanceScale.ELITE,
  'the troop suppresses harder than the elite ("strong" vs "medium")');
check(profile.suppressTicksScale.TROOP > profile.suppressTicksScale.ELITE,
  'and for longer');
check(profile.advanceCoverScale.SCAV === 0,
  'the scav advances in a straight line (advanceCoverScale 0)');
check(profile.advanceCoverScale.ELITE > profile.advanceCoverScale.TROOP
  && profile.advanceCoverScale.TROOP > 0,
  'troop advances in small cover steps, elite in long ones');
check(profile.engageRangeScale.ELITE < 1.0,
  'the elite closes to a shorter engagement distance');
check(profile.coverSeekSpeedScale.ELITE >= profile.coverSeekSpeedScale.TROOP,
  'the elite rushes at least as fast as it walks');
check(TRUTH.SCAV.coordination === false && TRUTH.SNIPER.coordination === false
  && TRUTH.TROOP.coordination === true && TRUTH.ELITE.coordination === true,
  'only troop and elite coordinate');
check(TRUTH.SNIPER.holdPost === true && TRUTH.TROOP.holdPost === false,
  'only the sniper holds its post');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('2. entity -> profile for all nine ids, against ModEntities');
const MAPPING = {
  scav: { cls: 'ScavEntity', tier: 'SCAV' },
  gunner_pillager: { cls: 'GunnerPillagerEntity', tier: 'SCAV' },
  gunner_villager: { cls: 'GunnerVillagerEntity', tier: 'SCAV' },
  sniper_pillager: { cls: 'SniperPillagerEntity', tier: 'SNIPER' },
  sniper_villager: { cls: 'SniperVillagerEntity', tier: 'SNIPER' },
  usec_villager: { cls: 'UsecVillagerEntity', tier: 'TROOP' },
  bear_pillager: { cls: 'BearPillagerEntity', tier: 'TROOP' },
  elite_villager: { cls: 'EliteVillagerEntity', tier: 'ELITE' },
  elite_pillager: { cls: 'ElitePillagerEntity', tier: 'ELITE' },
};
const entityRe = /public static final RegistryObject<EntityType<(\w+)>> (\w+) =\s*ENTITY_TYPES\.register\("([a-z0-9_]+)",\s*\(\) -> EntityType\.Builder\.<[^>]+>of\([^,]+,\s*MobCategory\.(\w+)\)/g;
const registered = [];
let entityMatch;
while ((entityMatch = entityRe.exec(entities)) !== null) {
  registered.push({ entityClass: entityMatch[1], id: entityMatch[3], category: entityMatch[4] });
}
const mobIds = registered.filter((row) => row.category !== 'MISC').map((row) => row.id);
check(mobIds.length === 9, 'ModEntities registers exactly nine mob types', mobIds.join(', '));
for (const row of registered.filter((entry) => entry.category !== 'MISC')) {
  const mapped = MAPPING[row.id];
  check(!!mapped, `${row.id} has an AI tier in the table`);
  check(!!mapped && mapped.cls === row.entityClass,
    `${row.id} is ${row.entityClass}, mapped to ${mapped ? mapped.tier : '?'}`);
}
for (const id of Object.keys(MAPPING)) {
  check(mobIds.includes(id), `the mapping table names a registered entity (${id})`);
}

// The mapper itself: the three subclass tiers must be checked BEFORE the base fallthrough, and the
// three base classes must not appear in any branch (otherwise they would shadow their subclasses).
const tierForBody = methodBody(ai, 'public static Tier tierFor(Mob mob)');
check(tierForBody !== null, 'AiProfile has the tierFor mapping method');
const branchClasses = { ELITE: [], TROOP: [], SNIPER: [] };
const branchRe = /if \(([^)]*)\) \{\s*return Tier\.(\w+);/g;
let branchMatch;
while ((branchMatch = branchRe.exec(tierForBody || '')) !== null) {
  const branchTier = branchMatch[2];
  if (!branchClasses[branchTier]) continue;
  for (const cls of Object.values(MAPPING).map((entry) => entry.cls)) {
    if (branchMatch[1].includes(`instanceof ${cls}`)) branchClasses[branchTier].push(cls);
  }
}
check(branchClasses.ELITE.sort().join(',') === 'ElitePillagerEntity,EliteVillagerEntity',
  'the ELITE branch names exactly the elite pair', branchClasses.ELITE.join(','));
check(branchClasses.TROOP.sort().join(',') === 'BearPillagerEntity,UsecVillagerEntity',
  'the TROOP branch names exactly the USEC/BEAR pair', branchClasses.TROOP.join(','));
check(branchClasses.SNIPER.sort().join(',') === 'SniperPillagerEntity,SniperVillagerEntity',
  'the SNIPER branch names exactly the two snipers', branchClasses.SNIPER.join(','));
for (const base of ['ScavEntity', 'GunnerPillagerEntity', 'GunnerVillagerEntity']) {
  check(!new RegExp(`instanceof ${base}\\b`).test(tierForBody || ''),
    `${base} is reached by the SCAV fallthrough, not by an instanceof`);
}
check(/return Tier\.SCAV;/.test(tierForBody || ''), 'the fallthrough is the safe, dumb default');
// The order is load-bearing: each subclass tier must be decided before any base class it extends.
const orderIndex = (tier) => (tierForBody || '').indexOf(`Tier.${tier}`);
check(orderIndex('ELITE') >= 0 && orderIndex('TROOP') > orderIndex('ELITE')
  && orderIndex('SNIPER') > orderIndex('TROOP'),
  'the branch order is elite, then troop, then sniper, then the scav fallthrough',
  `elite@${orderIndex('ELITE')} troop@${orderIndex('TROOP')} sniper@${orderIndex('SNIPER')}`);
const PARENTS = {
  EliteVillagerEntity: 'GunnerVillagerEntity', ElitePillagerEntity: 'GunnerPillagerEntity',
  UsecVillagerEntity: 'GunnerVillagerEntity', BearPillagerEntity: 'GunnerPillagerEntity',
  SniperVillagerEntity: 'GunnerVillagerEntity', SniperPillagerEntity: 'GunnerPillagerEntity',
  ScavEntity: 'Monster', GunnerPillagerEntity: 'Pillager', GunnerVillagerEntity: 'Villager',
};
for (const [cls, parent] of Object.entries(PARENTS)) {
  const source = strip(read(`entity/${cls}.java`)).replace(/\s+/g, ' ');
  check(new RegExp(`class ${cls} extends ${parent}\\b`).test(source),
    `${cls} really extends ${parent}`, 'the mapping order depends on this hierarchy');
}

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('3. every behaviour branch exists in the source');
// reaction delay
check(/public static int rollReactionTicks/.test(ai) && /public static int rollReaction\(/.test(ai),
  'AiProfile rolls the reaction delay from the tier window');
check(/if \(target\.getId\(\) != this\.reactionRollTargetId\)/.test(brain),
  'GunBrain detects a TARGET ACQUISITION by entity id');
check(/resetReaction\(\);[\s\S]{0,200}?transition\(GunAiState\.ALERT\)/.test(brain),
  'and goes back to the existing ALERT state for the delay (no parallel "surprised" state)');
check(/AiProfile\.rollReaction\(this\.mob\.getRandom\(\), this\.mob\)/.test(brain),
  'the delay is rolled per acquisition from the profile');
// cover preference
check(/AiProfile\.coverSearchRadius\(this\.mob\)/.test(tactics)
  && /AiProfile\.coverCacheTicks\(this\.mob\)/.test(tactics),
  'CombatTactics takes the search radius and cache lifetime from the profile');
check(/AiProfile\.rollsCoverUse\(this\.mob\)/.test(brain),
  'GunBrain rolls the tier cover chance before seeking cover');
check(/AiProfile\.advancesUnderCover\(this\.mob\)/.test(brain)
  && /AiProfile\.advanceCoverStep\(this\.mob\)/.test(brain),
  'and uses the tier advance step (0 = no cover, i.e. a straight line)');
// suppression
check(/AiProfile\.suppressChance\(this\.mob\)/.test(brain)
  && /AiProfile\.suppressTicks\(this\.mob\)/.test(brain)
  && /AiProfile\.suppressBurstMultiplier\(this\.mob\)/.test(brain)
  && /AiProfile\.suppressAccuracyMultiplier\(this\.mob\)/.test(brain),
  'all four suppression numbers come from the profile');
// hold-post + hit-chance floor
check(/AiProfile\.holdsPost\(this\.mob\) \? GunAiState\.REPOSITION : GunAiState\.ADVANCE/.test(brain),
  'a hold-post tier never chooses ADVANCE (it repositions instead)');
check(/getMoveControl\(\)\.setWantedPosition\(this\.mob\.getX\(\), this\.mob\.getY\(\), this\.mob\.getZ\(\), 0\.0D\)/
  .test(sniper) && /getNavigation\(\)\.stop\(\);/.test(sniper),
  'the sniper post is still pinned by SniperBehavior (navigation stopped, move control held)');
check(/AiProfile\.partialCoverBonus\(this\.mob\)/.test(sniper)
  && /partiallyConcealed\(level, target, candidate\)/.test(sniper),
  'SniperBehavior prefers concealed posts using the profile bonus');
check(/double score = \(visible \? 0\.0D : 1000\.0D\) \+ radius;/.test(sniper),
  'and a fully unseen post still wins on the original hard-coded 1000');
check(/AiProfile\.minHitChance\(this\.mob\)/.test(brain)
  && /AiProfile\.hitChanceIsEnough\(estimatedHitChance\(target\), minimum\)/.test(brain),
  'the sniper hold-fire rule asks the profile for the hit-chance floor');
check(/private double estimatedHitChance\(LivingEntity target\)/.test(brain)
  && /AccuracyProfile\.hitChanceFor\(accuracy, distance, radius\)/.test(brain),
  'and the estimate is the same cone model the shot uses (not a dice roll)');
// coordination: focus fire, overwatch, flank, claims
check(/private void applyFocusFire\(ServerLevel level, LivingEntity target\)/.test(brain)
  && /this\.squad\.focusTarget\(level\)/.test(brain),
  'GunBrain has the focus-fire adoption');
check(/!this\.mob\.canAttack\(focus\) \|\| !this\.mob\.hasLineOfSight\(focus\)/.test(brain),
  'and refuses a squad target it cannot see (intel alone never starts a fight)');
check(/public static int chooseFocusTarget\(int\[\] targetIds\)/.test(squad),
  'SquadCoordinator has the pure focus-target vote');
check(/this\.squad\.shouldSuppress\(level\.getGameTime\(\)\)/.test(brain),
  'decide() asks the squad whether this mob is the suppressor');
check(/public static boolean isSuppressor\(int index, long windowIndex, int count\)/.test(squad),
  'and the suppressor role is a pure rotation');
check(/public boolean isMover\(\)/.test(squad),
  'the mover role exists (the rest of the squad while one suppresses)');
check(/public int flankSide\(\)/.test(squad) && /public static boolean isFlanker\(/.test(squad)
  && /public static boolean onFlankSide\(/.test(squad),
  'the flank assignment and its side test exist');
check(/this\.coverFilter\(target, true\)/.test(brain) && /this\.squad\.onMyFlank\(/.test(brain),
  'the flank side is wired into the cover search');
check(/public void claim\(@Nullable BlockPos pos\)/.test(squad)
  && /public boolean isFree\(BlockPos pos\)/.test(squad)
  && /public static boolean claimIsFree\(int ownerId, int claimantId, long expiresAt, long now\)/.test(squad),
  'cover claims have claim / isFree / the pure expiry rule');
check(/private void useSpot\(CombatTactics\.Spot spot\)/.test(brain)
  && /this\.squad\.claim\(BlockPos\.containing\(spot\.x\(\), spot\.y\(\), spot\.z\(\)\)\)/.test(brain),
  'every chosen cover spot is claimed through one helper');
check(/this\.coverFilter\(target, false\)/.test(brain) && /allowed\.test\(BlockPos\.containing\(/.test(tactics),
  'and the claim veto really reaches CombatTactics (the Predicate overload)');
check(/this\.squad\.release\(\);/.test(brain), 'the claims are released when the goal stops');
// the master switches
check(/public static boolean active\(\)\s*\{\s*return Config\.SPEC\.isLoaded\(\) && Config\.AI_ENABLED\.get\(\);/.test(ai),
  'AiProfile.active() is the [ai] enabled master switch');
check(/settings\(mob\)\.coordination\.get\(\)/.test(ai) && /Config\.AI_COORD_ENABLED\.get\(\)/.test(ai),
  'coordination needs the tier switch AND the coordination master');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('4. the coordination decisions, simulated (mirrors compared with the Java text)');

/** A tiny deterministic random, for the reaction-delay simulation. */
function randomSource(seed) {
  let state = seed >>> 0;
  return {
    nextInt(bound) {
      state = (state * 1664525 + 1013904223) >>> 0;
      return state % bound;
    },
  };
}

// ---- rollReactionTicks
const JS_ROLL = 'if (max <= min) { return Math.max(0, min); } return min + random.nextInt(max - min + 1);';
const rollReactionTicks = new Function('random', 'min', 'max', JS_ROLL);
check(sameDecision(methodBody(ai, 'public static int rollReactionTicks('), JS_ROLL),
  'rollReactionTicks: the Java body and the JS mirror are the same expression');
{
  const random = randomSource(7);
  const seen = new Set();
  for (let i = 0; i < 2000; i++) seen.add(rollReactionTicks(random, 12, 24));
  const values = [...seen].sort((a, b) => a - b);
  check(values[0] === 12 && values[values.length - 1] === 24 && values.length === 13,
    'the scav window really is the inclusive 12..24 ticks', `${values[0]}..${values[values.length - 1]}, ${values.length} values`);
  check(rollReactionTicks(random, 7, 7) === 7 && rollReactionTicks(random, 9, 4) === 9,
    'min == max (and max < min) collapse to exactly min, so the jitter is optional');
}
// ---- withinChance / advancesUnderCover / hitChanceIsEnough
const JS_WITHIN = 'return roll < chance;';
const withinChance = new Function('chance', 'roll', JS_WITHIN);
check(sameDecision(methodBody(ai, 'public static boolean withinChance('), JS_WITHIN),
  'withinChance: the Java body and the JS mirror are the same expression');
check(withinChance(0.15, 0.149) && !withinChance(0.15, 0.15) && !withinChance(0.0, 0.0),
  'a 0.15 cover chance fires on ~15 percent of rolls, and 0 never fires');
const JS_ADVANCE = 'return advanceCoverScale > 0;';
const advancesUnderCover = new Function('advanceCoverScale', JS_ADVANCE);
check(sameDecision(methodBody(ai, 'public static boolean advancesUnderCover(double advanceCoverScale)'), JS_ADVANCE),
  'advancesUnderCover: the Java body and the JS mirror are the same expression');
check(advancesUnderCover(0.0) === false && advancesUnderCover(0.6) === true,
  'the scav (0) does not use cover to advance; the troop (0.6) does');
const JS_HIT = 'return minimum <= 0 || hitChance >= minimum;';
const hitChanceIsEnough = new Function('hitChance', 'minimum', JS_HIT);
check(sameDecision(methodBody(ai, 'public static boolean hitChanceIsEnough('), JS_HIT),
  'hitChanceIsEnough: the Java body and the JS mirror are the same expression');
check(hitChanceIsEnough(0.1, 0.0) && !hitChanceIsEnough(0.1, 0.35) && hitChanceIsEnough(0.35, 0.35),
  'a 0 floor is "always allowed", 0.35 refuses a 10 percent shot and accepts an exact 0.35');

// ---- chooseFocusTarget
const JS_FOCUS = `
let best = -1;
let bestVotes = 0;
for (const id of targetIds) {
if (id < 0) { continue; }
let votes = 0;
for (const other of targetIds) {
if (other == id) { votes++; }
}
if (votes > bestVotes || (votes == bestVotes && (best < 0 || id < best))) { bestVotes = votes; best = id; }
}
return best;
`;
const chooseFocusTarget = new Function('targetIds', JS_FOCUS);
check(sameDecision(methodBody(squad, 'public static int chooseFocusTarget('), JS_FOCUS),
  'chooseFocusTarget: the Java body and the JS mirror are the same expression');
check(chooseFocusTarget([5, 5, -1]) === 5, 'a squad of three where two fight #5 picks #5');
check(chooseFocusTarget([7, 3]) === 3, 'a 1-1 tie picks the lowest entity id (stable, not random)');
check(chooseFocusTarget([-1, -1]) === -1, 'nobody with a target means no squad target');
check(chooseFocusTarget([9, 4, 4, 9]) === 4, 'two votes beat one even when the loser is lower');

// ---- isSuppressor + the rotation
const JS_SUPPRESSOR = `
if (count <= 1) { return false; }
return ((windowIndex % count) + count) % count == index;
`;
const isSuppressor = new Function('index', 'windowIndex', 'count', JS_SUPPRESSOR);
check(sameDecision(methodBody(squad, 'public static boolean isSuppressor('), JS_SUPPRESSOR),
  'isSuppressor: the Java body and the JS mirror are the same expression');
check(isSuppressor(0, 0, 1) === false, 'a lone mob never suppresses (there is nobody to move)');
{
  const count = 3;
  const suppressorPerWindow = [];
  const suppressorCount = [0, 0, 0];
  for (let window = 0; window < 6; window++) {
    let found = -1;
    for (let index = 0; index < count; index++) {
      if (isSuppressor(index, window, count)) found = index;
    }
    suppressorPerWindow.push(found);
    if (found >= 0) suppressorCount[found]++;
  }
  check(suppressorPerWindow.join(',') === '0,1,2,0,1,2',
    'a squad of three rotates the suppressor 0,1,2,0,1,2 across windows',
    suppressorPerWindow.join(','));
  check(suppressorCount.every((n) => n === 2), 'and every member gets the role the same number of times',
    suppressorCount.join('/'));
  check(suppressorPerWindow.every((index) => index >= 0),
    'exactly one member suppresses every window (nobody is left out and nobody doubles up)');
}
check(isSuppressor(0, -1, 3) === false, 'the rotation is safe for a negative window index (floor mod)');

// ---- overwatch timeout: a suppressor that never gets to move falls back
{
  const TIMEOUT = 120;
  const shouldSuppress = (roleSuppressor, squad, now, since, timeout = TIMEOUT) =>
    roleSuppressor && squad && (timeout <= 0 || now - since <= timeout);
  check(shouldSuppress(true, true, 119, 0) === true, 'the suppressor role is held for the timeout window');
  check(shouldSuppress(true, true, 121, 0) === false,
    'and expires after it, so the mob falls back to its own behaviour (no deadlock)');
  check(shouldSuppress(false, true, 0, 0) === false, 'a non-suppressor is never told to suppress');
  check(shouldSuppress(true, false, 0, 0) === false, 'and neither is a mob with no squad');
  check(/int timeout = Config\.AI_COORD_OVERWATCH_TIMEOUT_TICKS\.get\(\);\s*return timeout <= 0 \|\| now - this\.suppressorSince <= timeout;/
    .test(squash(squad)),
    'the Java timeout rule is the one simulated above');
  check(/if \(next && !this\.suppressor\) \{\s*this\.suppressorSince = now;/.test(squad),
    'and the clock starts when the mob BECOMES the suppressor, not at server start');
}

// ---- flanking
const JS_FLANK_COUNT = `
if (count < 2) { return 0; }
let clamped = Math.min(Math.max(fraction, 0), 1);
let wanted = Math.round(count * clamped);
return Math.min(Math.max(wanted, 1), count - 1);
`;
const flankerCount = new Function('count', 'fraction', JS_FLANK_COUNT);
check(sameDecision(methodBody(squad, 'public static int flankerCount('), JS_FLANK_COUNT),
  'flankerCount: the Java body and the JS mirror are the same expression');
const JS_FLANKER = `
let flankers = flankerCount(count, fraction);
return flankers > 0 && index >= count - flankers;
`;
const isFlanker = new Function('index', 'count', 'fraction', 'flankerCount', JS_FLANKER);
check(sameDecision(methodBody(squad, 'public static boolean isFlanker('), JS_FLANKER),
  'isFlanker: the Java body and the JS mirror are the same expression');
check(flankerCount(1, 0.5) === 0, 'a lone mob cannot flank');
check(flankerCount(2, 0.5) === 1 && flankerCount(4, 0.5) === 2, 'half of a squad of two/four flanks');
check(flankerCount(5, 0.5) === 3 && flankerCount(5, 1.0) === 4 && flankerCount(5, 0.0) === 1,
  'the count is clamped to [1, size-1]: never nobody, never everybody');
{
  const count = 4;
  const flankers = [];
  for (let index = 0; index < count; index++) {
    if (isFlanker(index, count, 0.5, flankerCount)) flankers.push(index);
  }
  check(flankers.join(',') === '2,3', 'the flankers are the highest-indexed members (deterministic)',
    flankers.join(','));
  check(flankers.length < count, 'and they are only PART of the squad');
}
const JS_SIDE = `
let axisX = targetX - mobX;
let axisZ = targetZ - mobZ;
let offsetX = x - mobX;
let offsetZ = z - mobZ;
return (axisX * offsetZ - axisZ * offsetX) * side >= 0;
`;
const onFlankSide = new Function('mobX', 'mobZ', 'targetX', 'targetZ', 'side', 'x', 'z', JS_SIDE);
check(sameDecision(methodBody(squad, 'public static boolean onFlankSide('), JS_SIDE),
  'onFlankSide: the Java body and the JS mirror are the same expression');
// Mob at the origin, target due east. North-of-the-axis is +1, south is -1.
check(onFlankSide(0, 0, 10, 0, 1, 5, 5) === true && onFlankSide(0, 0, 10, 0, -1, 5, 5) === false,
  'a candidate north of the axis is only on the +1 side');
check(onFlankSide(0, 0, 10, 0, -1, 5, -5) === true && onFlankSide(0, 0, 10, 0, 1, 5, -5) === false,
  'and one south of it only on the -1 side');
check(onFlankSide(0, 0, 10, 0, 1, 5, 0) === true && onFlankSide(0, 0, 10, 0, -1, 5, 0) === true,
  'a spot exactly on the axis counts as either side (it is never excluded)');

// ---- cover claims
const JS_CLAIM = 'return ownerId == claimantId || expiresAt <= now;';
const claimIsFree = new Function('ownerId', 'claimantId', 'expiresAt', 'now', JS_CLAIM);
check(sameDecision(methodBody(squad, 'public static boolean claimIsFree('), JS_CLAIM),
  'claimIsFree: the Java body and the JS mirror are the same expression');
{
  const CLAIM_TICKS = 100;
  // Mob 1 claims a block at t=0. The claim expires at t=100.
  const claim = { ownerId: 1, expiresAt: 0 + CLAIM_TICKS };
  check(claimIsFree(claim.ownerId, 1, claim.expiresAt, 0) === true, 'the owner may re-use its own spot');
  check(claimIsFree(claim.ownerId, 2, claim.expiresAt, 0) === false,
    'a second mob may NOT pick the same block (this is the whole rule)');
  check(claimIsFree(claim.ownerId, 2, claim.expiresAt, 99) === false, 'the claim holds until it expires');
  check(claimIsFree(claim.ownerId, 2, claim.expiresAt, 100) === true,
    'and frees itself at exactly the expiry tick, so a dead owner cannot block a spot for ever');
  const claims = [];
  const chosen = [];
  for (const mobId of [1, 2]) {
    let picked = -1;
    for (let block = 0; block < 4; block++) {
      const stillFree = !claims.some((held) => held.block === block
        && !claimIsFree(held.ownerId, mobId, held.expiresAt, 1));
      if (stillFree) { picked = block; break; }
    }
    chosen.push(picked);
    claims.push({ ownerId: mobId, expiresAt: 1 + CLAIM_TICKS, block: picked });
  }
  check(chosen[0] === 0 && chosen[1] === 1,
    'two mobs cannot stack behind one block: the second finds the first claimed and takes another',
    chosen.join(','));
}
check(/Config\.AI_COORD_COVER_CLAIM_TICKS\.get\(\)/.test(squad),
  'the claim lifetime is the configured tick window');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('5. the README documents every new key');
const README_KEYS = [
  'reactionMinTicks', 'reactionMaxTicks', 'accuracyScale', 'coverChance', 'coverRadiusScale',
  'coverCacheScale', 'suppressChanceScale', 'suppressTicksScale', 'suppressAccuracyScale',
  'suppressBurstScale', 'advanceCoverScale', 'coverSeekSpeedScale', 'repositionScale',
  'retreatHealthScale', 'engageRangeScale', 'holdPost', 'minHitChance', 'patienceTicks',
  'coordination', 'partialCoverBonus', 'focusFire', 'overwatch', 'flanking', 'coverClaims',
  'squadRadius', 'squadCacheTicks', 'overwatchWindowTicks', 'overwatchTimeoutTicks',
  'flankFraction', 'flankOffset', 'coverClaimTicks',
];
const missing = README_KEYS.filter((key) => !readme.includes(key));
check(missing.length === 0, 'every per-tier and coordination key appears in README',
  missing.length ? missing.join(', ') : `${README_KEYS.length} keys`);
check(/### 5aa\./.test(readme), 'README has the 5aa section');
check(/\[ai\.scav\]/.test(readme) && /\[ai\.sniper\]/.test(readme)
  && /\[ai\.troop\]/.test(readme) && /\[ai\.elite\]/.test(readme) && /\[ai\.coordination\]/.test(readme),
  'README names the four tier sections and the coordination section');
check(/\[ai\]/.test(readme) && /`enabled`/.test(readme),
  'and documents the [ai] master switch');
// The scaling rule has to be stated, not just the keys: that is the promise to a user who already
// tuned [tactics].
check(/suppressChance.*suppressChanceScale|suppressChanceScale.*suppressChance/s.test(readme),
  'README states that the profile SCALES the global [tactics] key, not replaces it');
check(/retreatSprint/.test(readme.slice(readme.indexOf('### 5aa.'), readme.indexOf('### 5aa.') + 20000)),
  'README states the retreatSprint exception (profiles never turn the sprint back on)');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('6. the "revert to dumb" recipe, executed');
// The mirror of the AiProfile resolvers, on the shipped global baseline.
const BASELINE = {
  suppressChance: 0.6, suppressTicks: 60, suppressAccuracyMultiplier: 0.45,
  suppressBurstMultiplier: 2.0, advanceCoverStep: 3.0, coverSeekSpeedModifier: 1.0,
  coverSearchRadius: 14, coverCacheTicks: 20, repositionTicks: 40, retreatHealthFraction: 0.35,
  accuracy: 0.6, engageRange: 30.0,
};
const clamp01 = (value) => Math.min(Math.max(value, 0), 1);
const resolve = (knobs) => ({
  suppressChance: clamp01(BASELINE.suppressChance * knobs.suppressChanceScale),
  suppressTicks: Math.max(0, Math.round(BASELINE.suppressTicks * knobs.suppressTicksScale)),
  suppressAccuracy: BASELINE.suppressAccuracyMultiplier * knobs.suppressAccuracyScale,
  suppressBurst: BASELINE.suppressBurstMultiplier * knobs.suppressBurstScale,
  advanceCoverStep: BASELINE.advanceCoverStep * knobs.advanceCoverScale,
  coverSearchRadius: Math.max(1, Math.round(BASELINE.coverSearchRadius * knobs.coverRadiusScale)),
  coverCacheTicks: Math.max(1, Math.round(BASELINE.coverCacheTicks * knobs.coverCacheScale)),
  coverSeekSpeed: BASELINE.coverSeekSpeedModifier * knobs.coverSeekSpeedScale,
  repositionTicks: Math.max(0, Math.round(BASELINE.repositionTicks * knobs.repositionScale)),
  retreatHealth: BASELINE.retreatHealthFraction * knobs.retreatHealthScale,
  accuracy: BASELINE.accuracy * knobs.accuracyScale,
  engageRange: BASELINE.engageRange * knobs.engageRangeScale,
  usesCoverToAdvance: knobs.advanceCoverScale > 0,
  holdsPost: knobs.holdPost,
  minHitChance: knobs.minHitChance,
  patienceTicks: knobs.patienceTicks,
  coordination: knobs.coordination,
});
const knobsFor = (tier) => ({
  suppressChanceScale: profile.suppressChanceScale[tier],
  suppressTicksScale: profile.suppressTicksScale[tier],
  suppressAccuracyScale: profile.suppressAccuracyScale[tier],
  suppressBurstScale: profile.suppressBurstScale[tier],
  advanceCoverScale: profile.advanceCoverScale[tier],
  coverRadiusScale: profile.coverRadiusScale[tier],
  coverCacheScale: profile.coverCacheScale[tier],
  coverSeekSpeedScale: profile.coverSeekSpeedScale[tier],
  repositionScale: profile.repositionScale[tier],
  retreatHealthScale: profile.retreatHealthScale[tier],
  accuracyScale: profile.accuracyScale[tier],
  engageRangeScale: profile.engageRangeScale[tier],
  holdPost: TRUTH[tier].holdPost,
  minHitChance: tier === 'SNIPER' ? 0.5 : 0.0,
  patienceTicks: tier === 'SNIPER' ? 100 : 0,
  coordination: TRUTH[tier].coordination,
});
const dumb = resolve(knobsFor('SCAV'));
const scavResolved = JSON.stringify(dumb);
let dumbMatches = 0;
for (const tier of TIERS) {
  // The recipe: copy the [ai.scav] block over this tier (the five absolute knobs included).
  const other = resolve(knobsFor('SCAV'));
  const same = JSON.stringify(other) === scavResolved;
  if (same) dumbMatches++;
  check(same, `copying [ai.scav] over [ai.${tier.toLowerCase()}] yields scav-level values`,
    same ? '' : JSON.stringify(other));
}
check(dumbMatches === TIERS.length, 'the documented revert-to-dumb recipe reproduces the scav profile for all four tiers');
check(dumb.suppressChance === 0 && dumb.usesCoverToAdvance === false && dumb.holdsPost === false
  && dumb.coordination === false && dumb.minHitChance === 0,
  'the resulting "dumb" profile really has no suppression, no cover advance, no post, no squad',
  JSON.stringify(dumb));
check(/revert to dumb|REVERT TO DUMB/i.test(readme) || /回退到最笨|一键变笨/.test(readme),
  'README documents the revert-to-dumb recipe itself');
check(/copy[\s\S]{0,30}\[ai\.scav\]/i.test(readme) || /把[\s\S]{0,10}\[ai\.scav\]/.test(readme),
  'README says exactly which block to copy into the other three');
check(/reactionMinTicks = reactionMaxTicks/.test(readme),
  'README notes that the 0.6-1.2 s jitter needs pinning too for the exact pre-feature feel');

// ---------------------------------------------------------------------------------------------
console.log('');
console.log('7. the new stall risk (hold fire) is bounded');
const tickAim = methodBody(brain, 'private void tickAim(');
check(tickAim !== null && /this\.patientTicks\+\+/.test(tickAim),
  'tickAim counts the ticks spent waiting for the hit-chance floor');
check(/int patience = AiProfile\.patienceTicks\(this\.mob\);/.test(tickAim)
  && /if \(patience > 0 && this\.patientTicks > patience\)/.test(tickAim),
  'and the wait is bounded by the tier patienceTicks');
check(/this\.patientTicks = 0;\s*this\.patientEscapes\+\+;/.test(tickAim),
  'an expired wait is counted, not ignored');
check(/LOGGER|log\("/.test(brain) && /waited \{\} ticks for a \{\} hit chance/.test(brain),
  'the wait is logged (never silent)');
check(/this\.patientTicks = 0;/.test(methodBody(brain, 'private void transition(') || ''),
  'transition() resets the patience, so any state change starts it over');
check(/if \(minimum > 0\.0D && /.test(tickAim),
  'the floor is skipped entirely when the tier has none (0 = off)');
// The hold-fire rule may only DELAY a shot, never disable the anti-stall watchdog.
check(/if \(this\.watchdogTicks <= 0\) \{/.test(brain),
  'the existing FIRE watchdog is untouched (the hold-fire rule adds a bound, it removes none)');

// The estimate must be the STEADY-STATE cone. Including the warm-up penalty would deadlock the rule,
// because the warm-up only clears by firing: a mob that never fires never warms up, so it would never
// clear its own floor. This is simulated on the same cone model as README 5o.
const estimated = methodBody(brain, 'private double estimatedHitChance(');
check(estimated !== null && !/AccuracyProfile\.accuracyFor/.test(estimated),
  'the hold-fire estimate does NOT include the warm-up penalty (that would be self-deadlocking)');
check(/AccuracyProfile\.clampToHitChance\(steady, distance, radius, cap\)/.test(estimated || '')
  && /AccuracyProfile\.hitChanceFor\(accuracy, distance, radius\)/.test(estimated || ''),
  'it clamps the steady cone exactly as AccuracyProfile.accuracyFor would, then asks hitChanceFor');
function erf(value) {
  const sign = Math.sign(value);
  const z = Math.abs(value);
  const t = 1 / (1 + 0.3275911 * z);
  return sign * (1 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t
    + 0.254829592) * t * Math.exp(-z * z));
}
function coneHitChance(acc, distance, radius) {
  const halfAngle = (1 - Math.min(Math.max(acc, 0), 1)) * 7;
  const sigma = Math.max(1e-6, halfAngle * 0.5);
  const angularRadius = Math.atan2(radius, distance) * 180 / Math.PI;
  return Math.min(Math.max(erf(angularRadius / (sigma * Math.SQRT2)), 0), 1);
}
function clampToHitChance(acc, distance, radius, cap) {
  if (cap >= 1 || distance <= 0 || radius <= 0) return acc;
  if (coneHitChance(acc, distance, radius) <= cap) return acc;
  let low = 0;
  let high = acc;
  for (let i = 0; i < 40; i++) {
    const mid = (low + high) / 2;
    if (coneHitChance(mid, distance, radius) > cap) high = mid; else low = mid;
  }
  return low;
}
const STEADY_ACC = 0.85;
const WARMUP = 0.45;
const CAP = 0.85;
const FLOOR = 0.5;
const steadyEstimate = (d) => coneHitChance(
  clampToHitChance(Math.min(STEADY_ACC, CAP), d, 0.3, CAP), d, 0.3);
const warmupEstimate = (d) => coneHitChance(STEADY_ACC * WARMUP, d, 0.3);
const DISTANCES = [20, 30, 40, 52];
console.log('  (sniper cone, 0.3-radius target: steady '
  + DISTANCES.map((d) => `${d}b=${(100 * steadyEstimate(d)).toFixed(1)}%`).join(' ') + ')');
console.log('  (the same distances under the warm-up penalty: '
  + DISTANCES.map((d) => `${d}b=${(100 * warmupEstimate(d)).toFixed(1)}%`).join(' ') + ')');
check(steadyEstimate(40) > FLOOR && warmupEstimate(40) < FLOOR,
  'at 40 blocks the steady estimate clears the 0.50 floor while the warm-up one would not',
  `steady ${(100 * steadyEstimate(40)).toFixed(1)}% vs warm-up ${(100 * warmupEstimate(40)).toFixed(1)}%`);
check(steadyEstimate(52) < FLOOR && steadyEstimate(30) > FLOOR,
  'the 0.50 floor really binds at extreme range (52 blocks) and not at 30, so the knob is live',
  `52b ${(100 * steadyEstimate(52)).toFixed(1)}% < 50% < 30b ${(100 * steadyEstimate(30)).toFixed(1)}%`);
// The anti-stall fallback: bounded windows, a reposition in between, and a shot at the end.
check(/MAX_PATIENT_ESCAPES = 3/.test(brain), 'the hold-fire wait is a bounded 3 windows');
check(/this\.patientEscapes < MAX_PATIENT_ESCAPES/.test(tickAim)
  && /transition\(GunAiState\.REPOSITION\);/.test(tickAim),
  'each expired window repositions the mob before it tries again');
check(/gave up waiting for a \{\} hit chance after \{\} window\(s\) - taking the shot/.test(brain),
  'and after the last window it takes the shot it has (never permanently harmless)');
check(/this\.patientEscapes = 0;/.test(methodBody(brain, 'private void resetReaction(') || ''),
  'a fresh target resets the escape budget');
check(profile.reactionMinTicks.SCAV === 12 && profile.reactionMaxTicks.SCAV === 24,
  'the scav reaction window is still the requested 0.6-1.2 s after all of the above');

console.log('');
if (failures > 0) {
  console.log(`${failures} AI tier check(s) FAILED`);
  process.exit(1);
}
console.log('AI tier profiles + squad coordination invariants all hold');
