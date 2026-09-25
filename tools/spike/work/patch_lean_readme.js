// scratch: README §5t update for the three lean changes (absolute path)
const fs = require('fs');
const file = 'D:/deepseek/ArmedMobs/README.md';
let text = fs.readFileSync(file, 'utf8');
const before = text;
const sub = (from, to) => {
  if (!text.includes(from)) {
    console.log('MISS:', from.slice(0, 70));
    return;
  }
  text = text.replace(from, to);
};

sub('**怎么用**：按住 **Q = 向左歪**、**E = 向右歪**（都能在 选项→控制→武装暴徒 里改），松开自动回正。`client.leanEnabled = false` 时整个功能完全惰性。',
  `**怎么用**：按住 **Q = 向右歪**、**E = 向左歪**（都能在 选项→控制→武装暴徒 里改），松开自动回正。\`client.leanEnabled = false\` 时整个功能完全惰性。

> **2026-09-23 修正（用户回报）**：Q/E 一开始是**反的**（Q 曾经是向左歪），现在 **Q=右、E=左**；改的是**键位映射**（\`LeanClient\` 里两个 \`KeyMapping\` 的 GLFW 键码对调），**lean 数学（偏移沿右向量、roll 与头部同向）一个字没改**。若你觉得"歪头方向/倾斜方向还是反的"，用下面两个独立开关微调，不用改代码：
>
> | 键 | 默认 | 作用 |
> | --- | --- | --- |
> | \`client.leanInvertOffset\` | \`false\` | **只翻横移方向**（相机与枪口一起翻） |
> | \`client.leanInvertRoll\` | \`false\` | **只翻视角 roll**（横移感觉对、只有地平线倾斜反了时用这个） |
>
> **按键 → 偏移方向 → roll 符号对照表**（\`lean\` 为内部值，正 = 玩家右侧）：
>
> | 按键 | \`lean\` | 偏移量（世界方向） | \`roll\` |
> | --- | --- | --- | --- |
> | Q（右歪） | \`+1\` | 沿**右向量** \`(-cos yaw, 0, -sin yaw)\` × 0.6 | \`-\`（12° × −1，地平线随头部倾） |
> | E（左歪） | \`-1\` | 沿**左向量** × 0.6 | \`+\`（12°） |
> | 两个键同时按 | \`0\` | 无 | 无 |
> | \`leanInvertOffset=true\` | — | 整列取反 | 不变 |
> | \`leanInvertRoll=true\` | — | 不变 | 整列取反 |`);

sub(`| **丢弃（Q）** | 在 \`InputEvent.Key\` 里，当歪头键按住时**立刻把 \`options.keyDrop\` 的点击吃掉**（\`consumeClick()\`）——这一次按键**永远不会变成动作，也不会变成数据包** | 客户端就地拦截，没有"先丢掉再还回来"的窗口；而且比较的是**当前绑定**，改键后依然有效 |`,
  `| **丢弃（Q）** | 在 \`InputEvent.Key\` 里，**只要 Q 被歪头键占用就把 \`options.keyDrop\` 的点击吃掉**（\`consumeClick()\`）——这一次按键**永远不会变成动作，也不会变成数据包** | 客户端就地拦截，没有"先丢掉再还回来"的窗口；比较的是**当前绑定**，改键后自动恢复 |
| **背包（E）** | 同样**吃掉 \`options.keyInventory\` 的点击**（2026-09-23 起，不再取消界面） | **短按也不开背包**；而且只动"被我们占用的那个键"，所以命令/其它模组打开背包**不受影响** |`);

sub(`| **背包（E）** | 监听 \`ScreenEvent.Opening\`，歪头期间**取消 \`InventoryScreen\`** | 与 tick 顺序无关，且覆盖所有会打开背包的路径 |`,
  '');

sub(`\`client.leanSuppressVanillaKeys = false\` 可以整体关掉这两条压制（**如果你把歪头键改到别处**，就该关掉它，让 Q/E 恢复正常）。另外服务端还有一道兜底：`,
  `\`client.leanSuppressVanillaKeys = false\` 可以整体关掉这两条压制（完全恢复原版）。**"只禁用被我们占用的那两个键"是按当前键位算的**：只要你把歪头键改绑到别的键，Q/E 立刻恢复原版（这条被闸门断言）。另外服务端还有一道兜底：`);

sub('- 横向偏移 + roll（默认 **0.5 格 + 12°**，`client.leanMaxOffset` / `leanRollDegrees`），进/出都平滑（`leanSpeedTicks = 5` tick 到位），左右同时按则**互相抵消**（视为 0）。',
  '- 横向偏移 + roll（默认 **0.6 格 + 12°**，`client.leanMaxOffset` / `leanRollDegrees`），进/出都平滑（`leanSpeedTicks = 5` tick 到位），左右同时按则**互相抵消**（视为 0）。');

sub('- 相机位置的横移需要 `Camera#setPosition`，它是 `protected`。我**没有**加 access transformer（AT 在加载时生效，写错就是**启动崩溃**，而且我无法在没有客户端的情况下验证），也没有加 mixin（要再加 mixin 配置 + refmap + mods.toml 条目）。做法是**按签名反射**查找（`void` + 一个 `Vec3` 参数，1.20.1 的 `Camera` 里只有这一个），失败就 **catch 住、只打一行 WARN、本次会话退化成"只有 roll"**——**永远不会因为歪头崩客户端**。`/tarkovscav client state` 会打印 `cameraSlide=ok|unavailable`。',
  `- 相机位置的横移需要 \`Camera#setPosition(Vec3)\`，它在原版里是 \`protected\`。**2026-09-23 改成 access transformer（AT）**（\`src/main/resources/META-INF/accesstransformer.cfg\`，在 \`build.gradle\` 里用 \`accessTransformer = file(...)\` 声明），**彻底删掉了原来那条"按签名反射、失败静默降级"的路**——那次静默降级正是"歪头看起来只是转了一下、没有位移"的嫌疑来源。
  - AT 那一行是 \`public net.minecraft.client.Camera m_90581_(Lnet/minecraft/world/phys/Vec3;)V\`：**SRG 成员名**（对着 \`srg_to_official_1.20.1.tsrg\` 查的：\`m_90581_\` → \`setPosition\`），因为运行时用的是 SRG 名；而 **\`.cfg\` 里不能有 \`#\` 注释**——Forge 的解析器会直接报 \`Invalid access transformer line\`（我们第一次就这么被构建拦下来了），所以解释写在 \`build.gradle\` 的注释里。
  - **验证顺序（都可复跑）**：① 构建期 ForgeGradle 会把我们的 AT 应用到 dev 类上（日志 \`JAR transformation complete\`），**编译通过本身就是证明**——没有 AT 的话 \`protected\` 方法根本编译不过；② 产物里必须有 \`META-INF/accesstransformer.cfg\` 且内容仍是 SRG 那一行；③ \`javap -c\` 反查 \`LeanClient\`：必须是**直接** \`invokevirtual net/minecraft/client/Camera.m_90581_(Lnet/minecraft/world/phys/Vec3;)V\`，且**不能出现** \`java/lang/reflect\`/\`Method.invoke\`。这三条都写进了闸门（第 3 节）并在本次交付里逐条核过。
  - 万一 AT 在运行时没生效，\`camera.setPosition\` 会抛 \`IllegalAccessError\` → **catch 住 + ERROR 一行**，\`/tarkovscav client state\` 显示 \`cameraSlide=unavailable\`（**响亮地降级**，不再静默）；正常时应显示 \`cameraSlide=at\`。`);

sub('| `leanMaxOffset` | `0.5` | 满歪时相机（与弹道起点）横移的格数（0–1.5）；**同一个值同时决定两者** |',
  `| \`leanMaxOffset\` | \`0.6\` | 满歪时相机（与弹道起点）横移的格数（0–1.5）；**同一个值同时决定两者** |
| \`leanInvertOffset\` | \`false\` | 只翻横移方向（觉得左右反了用它） |
| \`leanInvertRoll\` | \`false\` | 只翻视角 roll（只有倾斜方向反了用它） |`);

sub('| `leanSuppressVanillaKeys` | `true` | 歪头期间压住原版 Q（丢弃）与 E（背包）；**把歪头键改到别处就该关掉它** |',
  '| `leanSuppressVanillaKeys` | `true` | 把被歪头键**占用的**原版键（Q 丢弃 / E 背包）**完全禁用，短按也算**；改绑歪头键后这些键自动恢复；`false` 则完全不干预 |');

sub('`lean=0.00 (left=false right=false) maxOffset=0.50 roll=12.0 speed=5t suppressVanillaKeys=true cameraSlide=ok`。',
  '`lean=0.00 (left=false right=false) maxOffset=0.60 roll=12.0 speed=5t invertOffset=false invertRoll=false suppressVanillaKeys=true cameraSlide=at`。');

sub('**闸门**：`tools/selftest_lean.js` —— 键位与默认值、两条压制路径与开关、',
  '**肉眼判据（2026-09-23 新增）**：**贴墙歪头**时，枪口/准星应当**真的绕过了墙角**（能看到墙角另一侧的东西），并且 `client state` 的 `cameraSlide=` 必须是 **`at`**（若显示 `unavailable` 就是 AT 没生效，把那一行发我）。短按 Q **不应**丢出物品、短按 E **不应**打开背包。\n\n**闸门**：`tools/selftest_lean.js` —— 键位与默认值、两条压制路径与开关、');

fs.writeFileSync(file, text);
console.log('changed:', before !== text);
