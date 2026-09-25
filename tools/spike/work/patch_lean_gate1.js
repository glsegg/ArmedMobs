// scratch: update the lean gate for the three 2026-09-23 changes (absolute paths)
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/tools/selftest_lean.js';
let text = fs.readFileSync(file, 'utf8');
const before = text;
const sub = (from, to) => {
  if (!text.includes(from)) {
    console.log('MISS:', from.slice(0, 70));
    return;
  }
  text = text.replace(from, to);
};

// 1. the keys: Q leans RIGHT, E leans LEFT.
sub('check(/public static final KeyMapping LEAN_LEFT = new KeyMapping\\("key\\.tarkovscav\\.lean_left",\\s*InputConstants\\.Type\\.KEYSYM, GLFW\\.GLFW_KEY_Q, CATEGORY\\)/',
  'check(/public static final KeyMapping LEAN_LEFT = new KeyMapping\\("key\\.tarkovscav\\.lean_left",\\s*InputConstants\\.Type\\.KEYSYM, GLFW\\.GLFW_KEY_E, CATEGORY\\)/');
sub("'lean left ships bound to Q'", "'lean left ships bound to E (the mapping was swapped: the user reported Q/E reversed)'");
sub('check(/public static final KeyMapping LEAN_RIGHT = new KeyMapping\\("key\\.tarkovscav\\.lean_right",\\s*InputConstants\\.Type\\.KEYSYM, GLFW\\.GLFW_KEY_E, CATEGORY\\)/',
  'check(/public static final KeyMapping LEAN_RIGHT = new KeyMapping\\("key\\.tarkovscav\\.lean_right",\\s*InputConstants\\.Type\\.KEYSYM, GLFW\\.GLFW_KEY_Q, CATEGORY\\)/');
sub("'and lean right to E'", "'and lean right to Q'");

// 2. the suppression: clicks are consumed for the vanilla keys we OCCUPY, short taps included.
sub('&& /while \\(drop\\.consumeClick\\(\\)\\)/.test(client),', '&& /while \\(vanilla\\.consumeClick\\(\\)\\)/.test(client),');
sub("'the drop key press is consumed the moment it is pressed (it never becomes an action or a packet)'",
  "'every click of an occupied vanilla key is consumed the moment it is pressed (never an action, never a packet)'");
sub("check(/drop\\.getKey\\(\\)\\.getType\\(\\) == InputConstants\\.Type\\.KEYSYM && event\\.getKey\\(\\) == drop\\.getKey\\(\\)\\.getValue\\(\\)/\n  .test(client), 'and the comparison is against the CURRENT binding, so a rebind still works');",
  "check(/public static java\\.util\\.List<KeyMapping> occupiedVanillaKeys\\(Minecraft minecraft\\)/.test(client)\n  && /vanilla\\.getKey\\(\\)\\.getValue\\(\\) == key/.test(client),\n  'and the comparison is against the CURRENT binding, so rebinding a lean key gives the vanilla key back');");

fs.writeFileSync(file, text);
console.log('changed:', before !== text);
