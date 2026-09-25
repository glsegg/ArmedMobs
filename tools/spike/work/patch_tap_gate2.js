// scratch: lean gate - fix the wire/clamp assertions and document the three new keys in the README
const fs = require('fs');
const gate = 'D:/deepseek/ArmedMobs/tools/selftest_lean.js';
let g = fs.readFileSync(gate, 'utf8');
const gb = g;
const sub = (from, to) => {
  if (!g.includes(from)) {
    console.log('MISS:', from.slice(0, 70));
    return;
  }
  g = g.replace(from, to);
};
sub("&& /new ServerboundLean\\(LeanMath\\.clamp\\(lean\\)\\)/.test(network)",
  "&& /new ServerboundLean\\(LeanMath\\.clamp\\(lean\\), holding\\)/.test(network)");
sub("check(/else if \\(!send && sent != 0\\.0F\\) \\{\\s*sent = 0\\.0F;\\s*LeanNetwork\\.send\\(0\\.0F\\);/.test(client),",
  "check(/else if \\(!send && sent != 0\\.0F\\) \\{\\s*sent = 0\\.0F;\\s*sentHolding = false;\\s*LeanNetwork\\.send\\(0\\.0F, false\\);/.test(client),");
fs.writeFileSync(gate, g);
console.log('gate changed:', gb !== g);

// README: the tap/long-press contract + the three keys
const readme = 'D:/deepseek/ArmedMobs/README.md';
let r = fs.readFileSync(readme, 'utf8');
const rb = r;
const subR = (from, to) => {
  if (!r.includes(from)) {
    console.log('README MISS:', from.slice(0, 70));
    return;
  }
  r = r.replace(from, to);
};
subR(`| **丢弃（Q）** | 在 \`InputEvent.Key\` 里，**只要 Q 被歪头键占用就把 \`options.keyDrop\` 的点击吃掉**（\`consumeClick()\`）——这一次按键**永远不会变成动作，也不会变成数据包** | 客户端就地拦截，没有"先丢掉再还回来"的窗口；比较的是**当前绑定**，改键后自动恢复 |`,
  `| **按下** | \`InputEvent.Key\` 的 PRESS 里立刻**吃掉 \`options.keyDrop\` / \`options.keyInventory\` 的点击**并**记下按下时刻** | 原版是在**按下那一瞬间**执行动作的，而"这是轻点还是长按"要到松开才知道——所以必须先把点击拿走，再由我们决定要不要重放 |
| **轻点松开（< \`tapThresholdTicks\`，默认 5 tick = 250ms）** | **我们代为执行原版动作**：E → 打开 \`InventoryScreen\`（与 \`Minecraft#handleKeybinds\` 那条一致）；Q → \`player.drop(false)\` 丢**一个**（旁观者不丢），**只丢一次** | "短按才会开"是用户要的语义；重放在**松开时**发生，所以不会和原版抢 |
| **长按（≥ 阈值）松开** | **什么都不做**（纯 peek） | "长按不开"；按住期间原版动作也早就被吃掉了 |`);

subR(`| **背包（E）** | 同样**吃掉 \`options.keyInventory\` 的点击**（2026-09-23 起，不再取消界面） | **短按也不开背包**；而且只动"被我们占用的那个键"，所以命令/其它模组打开背包**不受影响** |`,
  `| **歪头起始时机** | \`client.leanStartMode = immediate\`（默认）按下即开始歪——手感跟手，代价是"轻点一下"会有极短的一次歪头、松手立刻回正；\`afterThreshold\` 则要按住到阈值才歪，轻点完全不歪 | 两种都可用，配置切换 |`);

subR(`| \`leanSuppressVanillaKeys\` | \`true\` | 把被歪头键**占用的**原版键（Q 丢弃 / E 背包）**完全禁用，短按也算**；改绑歪头键后这些键自动恢复；\`false\` 则完全不干预 |`,
  `| \`leanSuppressVanillaKeys\` | \`true\` | 拦截被歪头键**占用的**原版键（短按由我们重放原版动作、长按不触发）；改绑歪头键后这些键自动恢复；\`false\` 则完全不干预 |
| \`leanTapThresholdTicks\` | \`5\` | 按住多少 tick 才算"长按/peek"；**短于它 = 轻点 = 原版动作** |
| \`leanStartMode\` | \`immediate\` | \`immediate\`（按下即歪）/ \`afterThreshold\`（到阈值才歪） |
| \`leanReplayVanillaOnTap\` | \`true\` | 轻点是否由我们重放原版动作（false = 轻点也什么都不做，即上一版行为） |`);

subR('`lean=0.00 (left=false right=false) maxOffset=0.60 roll=12.0 speed=5t invertOffset=false invertRoll=false suppressVanillaKeys=true cameraSlide=at`。',
  '`lean=0.00 (left=false right=false) maxOffset=0.60 roll=12.0 speed=5t invertOffset=false invertRoll=false tapThreshold=5t startMode=immediate replayOnTap=true holding=false suppressVanillaKeys=true cameraSlide=at`。');

subR('短按 Q **不应**丢出物品、短按 E **不应**打开背包。',
  '**轻点 E 应打开背包、按住 E 应歪头且不开背包、轻点 Q 应丢出一个、按住 Q 应不丢**（2026-09-23 用户改定的语义）。');
fs.writeFileSync(readme, r);
console.log('README changed:', rb !== r);
