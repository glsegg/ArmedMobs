// scratch: reproduce the grenade gate's failing checks in isolation
const fs = require('fs');
const R = 'D:/deepseek/ArmedMobs/src/main/java/com/gfl/tarkovscav/';
const strip = (s) => s.replace(/\/\*[\s\S]*?\*\//g, '').replace(/\/\/[^\n]*/g, '');
const blastCode = strip(fs.readFileSync(R + 'grenade/GrenadeBlast.java', 'utf8'));
const resupplyCode = strip(fs.readFileSync(R + 'grenade/GrenadeResupplyGoal.java', 'utf8'));
const entityCode = strip(fs.readFileSync(R + 'grenade/GrenadeEntity.java', 'utf8'));
const item = fs.readFileSync(R + 'grenade/GrenadeItem.java', 'utf8');
const kinds = fs.readFileSync(R + 'grenade/GrenadeKind.java', 'utf8');
console.log('A concat ok:', !/"item\.tarkovscav\." \+/.test(item + kinds));
console.log('A concat stripped ok:', !/"item\.tarkovscav\." \+/.test(strip(item) + strip(kinds)));
console.log('B step:', /for \(double travelled = step; travelled <= reach; travelled \+= step\)/.test(blastCode));
console.log('C grenadeitem:', /instanceof GrenadeItem\)[\s\S]{0,200}?setCooldown/.test(resupplyCode));
console.log('D priority:', /rackPriority/.test(resupplyCode), /priority\.equals\("nearest"\)/.test(resupplyCode));
console.log('E enabled:', /resupplyEnabled/.test(resupplyCode),
  /GRENADES_RESUPPLY_ENABLED/.test(resupplyCode));
console.log('F getDefaultItem:', /getDefaultItem\(\)[\s\S]{0,200}?ModItems\.grenadeItem/.test(entityCode));
