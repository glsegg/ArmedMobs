// scratch: make the name simulation positive (the vanilla profession keys are not in OUR lang file)
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/tools/selftest_entity_registry.js';
let text = fs.readFileSync(file, 'utf8');
const before = text;
const anchor = `  check(typeof base === 'string' && typeof format === 'string',
    \`\${langName}: the villager name and the composition format exist\`);`;
const added = anchor + `
  // The vanilla profession keys belong to Minecraft, not to us, so the sweep below can only prove the
  // FALLBACK ("never a raw key"). The positive case is composed explicitly here: with the vanilla key
  // resolving, the name must read like the request asked for it to.
  const vanillaWeaponsmith = langName === 'zh_cn' ? '武器匠' : 'Weaponsmith';
  check(format.replace('%s', base).replace('%s', vanillaWeaponsmith).startsWith(base),
    \`\${langName}: a resolved profession composes onto our base name\`,
    format.replace('%s', base).replace('%s', vanillaWeaponsmith));`;
text = text.replace(anchor, added);
fs.writeFileSync(file, text);
console.log('changed:', before !== text);
