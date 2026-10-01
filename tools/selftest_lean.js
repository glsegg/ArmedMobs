// Feature removal regression: Q/E and the player's camera/projectiles stay with vanilla.
'use strict';
const fs = require('fs');
const path = require('path');
const root = path.resolve(__dirname, '..');
const java = path.join(root, 'src/main/java/com/gfl/tarkovscav');
let checks = 0;
function check(value, message) { if (!value) throw new Error(message); checks++; }
function read(file) { return fs.readFileSync(path.join(root, file), 'utf8'); }
for (const file of ['client/LeanClient.java', 'lean/LeanMath.java', 'lean/LeanState.java',
                    'lean/LeanNetwork.java', 'lean/LeanServerEvents.java']) {
  check(!fs.existsSync(path.join(java, file)), `retired runtime file must be absent: ${file}`);
}
function walk(dir) { return fs.readdirSync(dir, {withFileTypes: true}).flatMap(e =>
  e.isDirectory() ? walk(path.join(dir, e.name)) : e.name.endsWith('.java') ? [path.join(dir, e.name)] : []); }
const source = walk(java).map(p => fs.readFileSync(p, 'utf8')).join('\n');
for (const pattern of [/com\.gfl\.tarkovscav\.lean/, /LeanClient/, /LeanNetwork/,
                        /ViewportEvent\.ComputeCameraAngles/, /GLFW_KEY_[QE]\b/, /Config\.LEAN_/]) {
  check(!pattern.test(source), `retired hook not reintroduced: ${pattern}`);
}
const config = read('src/main/java/com/gfl/tarkovscav/Config.java');
check(!/\bLEAN_\w+/.test(config), 'retired config fields absent');
check(!/accessTransformer\s*=/.test(read('build.gradle')), 'no camera access transformer in build');
check(!fs.existsSync(path.join(root, 'src/main/resources/META-INF/accesstransformer.cfg')), 'retired AT absent');
const hud = read('src/main/java/com/gfl/tarkovscav/client/ClientHudEvents.java');
check(/KillFeedHud\.tick\(/.test(hud) && /FlashOverlay\.tick\(/.test(hud), 'HUD ticks survive feature removal');
check(/EventBusSubscriber/.test(hud) && /Dist\.CLIENT/.test(hud), 'HUD lifecycle still registered on client');
console.log(`Lean removal: ${checks} checks passed`);
