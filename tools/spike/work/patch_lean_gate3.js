// scratch: lean gate part 3 - the last three regex fixes
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/tools/selftest_lean.js';
let text = fs.readFileSync(file, 'utf8');
const before = text;
const sub = (from, to) => {
  if (!text.includes(from)) {
    console.log('MISS:', from.slice(0, 80));
    return;
  }
  text = text.replace(from, to);
};
sub('check(/public static java\\.util\\.List<KeyMapping> occupiedVanillaKeys\\(Minecraft minecraft\\)/.test(client)',
  'check(/static java\\.util\\.List<KeyMapping> occupiedVanillaKeys\\(Minecraft minecraft\\)/.test(client)');
sub("check(!/ScreenEvent\\.Opening|setCanceled\\(true\\)/.test(client),",
  "check(!/ScreenEvent\\.Opening|setCanceled\\(true\\)/.test(strip(client)),");
sub("check(/LeanMath\\.offsetFor\\(event\\.getYaw\\(\\), lean, Config\\.LEAN_MAX_OFFSET\\.get\\(\\)\\)/.test(client),\n  'the camera calls it');",
  "check(/LeanMath\\.offsetFor\\(event\\.getYaw\\(\\), signed, Config\\.LEAN_MAX_OFFSET\\.get\\(\\)\\)/.test(client),\n  'the camera calls it');");
fs.writeFileSync(file, text);
console.log('changed:', before !== text);
