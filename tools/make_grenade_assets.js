// Generates the item models and the crafting recipes of the five throwables (README 5v), so the table below is
// the single source of truth for "what is it made of" and "which texture does it use".
//
//   node tools/make_grenade_assets.js
'use strict';
const fs = require('fs');
const path = require('path');

const RES = path.join(__dirname, '..', 'src', 'main', 'resources');
const ITEMS = path.join(RES, 'assets', 'tarkovscav', 'models', 'item');
const RECIPES = path.join(RES, 'data', 'tarkovscav', 'recipes');

// id -> the shaped recipe. 'I' iron ingot, 'G' gunpowder, 'T' TNT, 'W' white wool, 'S' sand, 'Q' glowstone
// dust. The short-fuse flashbang is the standard one with less iron, which is also why it has a shorter fuse.
const GRENADES = {
  frag_grenade: { pattern: [' I ', 'IGI', ' I '], keys: { I: 'minecraft:iron_ingot', G: 'minecraft:gunpowder' } },
  he_grenade: { pattern: ['III', 'GTG', 'III'], keys: { I: 'minecraft:iron_ingot', G: 'minecraft:gunpowder', T: 'minecraft:tnt' } },
  smoke_grenade: { pattern: [' W ', 'GSG', ' W '], keys: { W: 'minecraft:white_wool', G: 'minecraft:gunpowder', S: 'minecraft:sand' } },
  flash_grenade: { pattern: ['III', 'GQG', 'III'], keys: { I: 'minecraft:iron_ingot', G: 'minecraft:gunpowder', Q: 'minecraft:glowstone_dust' } },
  flash_grenade_short: { pattern: [' I ', 'GQG', ' I '], keys: { I: 'minecraft:iron_ingot', G: 'minecraft:gunpowder', Q: 'minecraft:glowstone_dust' } },
};

let written = 0;
for (const [id, recipe] of Object.entries(GRENADES)) {
  const model = { parent: 'minecraft:item/generated', textures: { layer0: `tarkovscav:item/${id}` } };
  fs.writeFileSync(path.join(ITEMS, `${id}.json`), `${JSON.stringify(model, null, 2)}\n`);
  written++;
  const json = {
    type: 'minecraft:crafting_shaped',
    pattern: recipe.pattern,
    key: Object.fromEntries(Object.entries(recipe.keys).map(([k, v]) => [k, { item: v }])),
    result: { item: `tarkovscav:${id}`, count: 1 },
  };
  fs.writeFileSync(path.join(RECIPES, `${id}.json`), `${JSON.stringify(json, null, 2)}\n`);
  written++;
}
console.log(`wrote ${written} file(s): 5 item models + 5 recipes`);
