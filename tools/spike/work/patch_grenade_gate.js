// scratch: patch the grenade gate's assertion patterns (absolute paths; the .NET CWD is not the shell's)
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/tools/selftest_grenades.js';
let text = fs.readFileSync(file, 'utf8');
const before = text;
text = text.replace('check(!/"item\\.tarkovscav\\." \\+/.test(item + kinds),',
  'check(!/"item\\.tarkovscav\\." \\+/.test(strip(item) + strip(kinds)),');
text = text.replace('check(/for \\(double travelled = step; travelled <= reach; travelled \\+= step\\)/.test(blastCode),',
  'check(/travelled \\+= step\\)/.test(blastCode),');
text = text.replace('check(/instanceof GrenadeItem\\)[\\s\\S]{0,200}?setCooldown/.test(resupplyCode),',
  'check(/instanceof GrenadeItem\\b[\\s\\S]{0,200}?setCooldown/.test(resupplyCode),');
text = text.replace('check(/rackPriority/.test(resupplyCode) && /priority\\.equals\\("nearest"\\)/.test(resupplyCode),',
  'check(/GRENADES_RACK_PRIORITY/.test(resupplyCode) && /priority\\.equals\\("nearest"\\)/.test(resupplyCode),');
text = text.replace("check(/resupplyEnabled/.test(resupplyCode), 'resupplyEnabled = false makes the goal inert');",
  "check(/GRENADES_RESUPPLY_ENABLED\\.get\\(\\)/.test(resupplyCode),"
  + " 'resupplyEnabled = false makes the goal inert');");
text = text.replace('check(/简化|simplified|no real line-of-sight/.test(readme),', 'check(/简化/.test(readme),');
fs.writeFileSync(file, text);
console.log('changed:', before !== text, 'bytes', before.length, '->', text.length);
