# 通配样板（Wildcard Pattern）重做设计方案 v2

> 本文只做设计，**不改代码**。请先确认第 12 节的问题，我再动手。
> 目标环境：Minecraft **1.20.1** / **Forge 47.x** / **AE2 15.x**（沿用已跑通的离线构建链）。

---

## 0. 已确认的决定（2026-10-08）与实现状态

| 问题 | 你的选择 | 对设计的影响 |
| --- | --- | --- |
| 运行时载体 | **自研「通配样板供应器」方块（无 mixin）** | 第 6.3 节的 mixin 方案作废，改为自己实现 AE2 `ICraftingProvider`；好消息：AE2 有公开工厂 `appeng.api.networking.GridHelper.createManagedNode(...)`，连网格节点都不用碰内部类 |
| 运行时实例化 | 接受「内存惰性实例化 + 缓存 + 上限」 | 第 6.1/6.2 节保留 |
| 选择器 | 自绘搜索列表，不依赖 JEI | 第 7 节保留（`ItemSearch` + 自绘结果列表） |
| 可选输入 | 默认关闭，需要时开启且 k≤2 | `Options.maxOptionalExpansion` 默认 0，取值 0..2 |
| 不消耗 | 用 AE2 剩余物机制真·不消耗 | 第 6.4 节保留（`IInput.getRemainingKey`） |
| 复制/粘贴 | GUI 内复制/粘贴 + SNBT 导入导出 | 通过系统剪贴板实现（复制=导出 SNBT，粘贴=导入 SNBT） |
| 实施阶段 | 先 P1+P2 | 见下方状态 |

**实现状态**

- ✅ **P1 已完成并验证**（`dist/wildcardencoder-2.0.0.jar`，编译 23 源文件 → 45 类，842 条引用校验 0 问题）：
  规则层（`WildcardRules`/`RuleCodec`，NBT+SNBT+版本号）、匹配引擎（`VariantResolver`/`PatternInstantiator`/`ExclusionFilter`，
  含变体绑定、排除求值、上限与截断、可选展开）、通配样板物品（右键开界面 + tooltip 摘要）、
  三标签页 GUI（原料/产物/排除，自绘匹配器编辑器与搜索列表）、编码器方块（**只产出一张**通配样板，
  可配置是否消耗一个 AE2 空白样板）、服务端权威的编辑/同步协议（`RuleEditPacket`/`RuleSyncPacket`）、
  `/wildcardencoder rules|preview|export|import|give|families|variants`、中英双语。
- ✅ **P2 已完成并验证**（同一个 jar）：自研「通配样板供应器」方块（`GridHelper.createManagedNode` +
  `ICraftingProvider`，**零 mixin**）、运行时 `IPatternDetails`/`IInput`、定义键（`we_res`，在途合成任务读档后精确还原）、
  `IPatternDetailsDecoder` 注册、按「规则内容 + 标签版本」缓存、把输入推到相邻 Forge 容器、
  `consume=false` 走 AE2 的 `getRemainingKey` 剩余物机制实现真·不消耗。
  为编译这一层，离线构建链新增了 **Forge 访问转换器（AT）应用**步骤：`ats/accesstransformer.cfg` 里的 457 条
  记录中 45 个类 / 412 个成员全部成功翻译并应用（0 未解析），否则 `BlockEntityType$BlockEntitySupplier`
  这类被 AT 放宽的成员在开发环境里不可见。

- ➕ **追加需求（对话中提出，已实现）**：编辑界面下半部分直接显示**玩家物品栏**，因此除了「搜索添加」还有三种快速录入：
  **从物品栏拖入**（拿起物品拖到列表松开）、**Ctrl+左键点物品栏格子**（不拿起物品直接添加）、
  **从 JEI 拖入**（JEI 幽灵拖拽 API：`IGhostIngredientHandler` + `IGuiHandlerRegistration.addGhostIngredientHandler`）。
  添加目标由当前标签页决定（原料/产物/排除=按物品排除）。JEI 是**可选依赖**：集成代码单独放在
  `com.ae2wildcard.encoder.compat.jei`（`@JeiPlugin`，只有 JEI 自己会加载这个类），`mods.toml` 里声明
  `mandatory=false, side=CLIENT`，不装 JEI 时模组功能完整、且主代码不引用任何 `mezz.jei` 类型。

- ➕ **追加需求（对话中提出，已实现）**：
  1. **界面改为高版本 AE2（15.x）的 UI 风格** —— 数值直接取自 AE2 自己的资源：
     GUI 贴图用的是容器调色板（面板 `#C6C6C6`、凹槽 `#8B8B8B` + `#373737`/`#FFFFFF` 立体边、黑色描边），
     文字取自 `assets/ae2/screens/common/palette.json`（`#404040` / `#7E7E7E` / `#00D5FF` / `#CE2401`）。
  2. **行内开关看得懂** —— `C/O/A/N/X` 悬停即有说明，按钮行加 `?` 打开完整图例浮层；
     数量改成 `- 1 +` 的可见形式；物品行显示本地化名称，原始 id 放进悬停提示。
  3. **报错中文化** —— 服务端不再拼接英文字符串，改为发送「翻译键 + 参数」（`craft/Msg`），
     由客户端按玩家语言渲染；错误/警告/状态全部走这一条路。

---

## 1. 与上一版的根本区别

| | 上一版（做错的方向） | 本方案 |
| --- | --- | --- |
| 编码产物 | 一次生成 **N 个** 具体 AE2 样板物品 | 只生成 **1 个** 通配样板物品 |
| 规则保存 | 无（模板字符串用完即弃） | **规则存在物品 NBT**（服务端权威，重启不丢） |
| 组合处理 | **编码时穷举**所有组合并落成物品 | **运行时按需实例化**，只存在内存对象，永不落物品 |
| 编辑方式 | 方块 GUI 里的一个文本框 | 通配样板 **右键打开三标签页 GUI**（原料/产物/排除） |
| 与 AE2 关系 | 产出的是 AE2 普通样板 | 产出的是**本模组物品**；运行时才转成 AE2 认识的 `IPatternDetails` |
| 排除 | 只有 `{!tin}` 这种过滤 | 独立「排除」页：物品 / 模组 / NBT / 原料×产物 / 变体 |
| 普通样板编码器 | 未受影响 | 依然未受影响，且**无法**生成或编辑通配样板 |

---

## 2. 你提的 8 个问题，我的回答

| # | 问题 | 回答 | 依据 |
| --- | --- | --- | --- |
| 1 | MC 版本 | **1.20.1** | 沿用上一轮已真实编译/校验通过的工程。若要 1.21.x 请告知：NBT→DataComponent、AE2 换 19.x、映射与构建链都要重做 |
| 2 | 加载器 | **Forge 47.x** | 1.20.1 没有正式 NeoForge；Fabric 需另写一套 |
| 3 | 依赖哪个模组 | **AE2 15.x 必需**（RS 阶段 1 不做） | 我们要把自己接进 AE2 的合成规划（`IPatternDetails`）。匹配引擎会与 AE2 解耦，将来加 RS 适配只需加一层 runtime 实现，但 RS 版本需要你指定 |
| 4 | 谁家的样板 | **本模组自己的新物品**，不是 AE2 的 `EncodedPatternItem` | 这样 AE2 样板编码终端天然既不能生成也不能编辑它（验收第 9、10 条）。运行时我们才把它**伪装成 AE2 的 `IPatternDetails`（内存对象）** |
| 5 | 最终放进哪个机器 | **原版 AE2 样板供应器**（推荐，体验最好，代价是 1 个很小的 mixin）；无 mixin 备选：自研「通配样板供应器」方块（P3） | 见第 6 节 |
| 6 | 运行时是动态生成还是伪装 | **伪装**：动态构造内存中的 `IPatternDetails` 列表给 AE2 的合成 CPU 看；**不生成、不写入任何具体样板物品** | 第 6 节；AE2 的规划器只认 `IPatternDetails` |
| 7 | 排除页作用点 | **两处都生效**：编辑/编码时用于预览统计与校验；运行时在**实例化之前**过滤（运行时为主） | 第 5、6 节 |
| 8 | 复制/粘贴、JEI | **支持**：GUI 内复制/粘贴条目、整卡规则复制、导出/导入 SNBT 字符串；JEI/REI：P1 用 tooltip 摘要 + 自绘搜索列表（**默认不依赖 JEI**），P3 再加 JEI 拖入 | 第 7 节 |

---

## 3. 总体架构

```
┌────────────────────────── 客户端 ──────────────────────────┐
│ WildcardPatternScreen（三标签页：原料 / 产物 / 排除）        │
│  ├ RuleListWidget（条目卡片：数量、消耗、可选、组）          │
│  ├ MatcherEditor（具体物品 / 标签 / 族通配 / 模组 / NBT）    │
│  └ ItemSearchList（自绘搜索，无 JEI 依赖，预留 JEI 拖入）    │
└───────────────▲───────────────────────────┬────────────────┘
        RuleSyncPacket(S2C)         RuleEditPacket(C2S, rev)
┌───────────────┴───────────────────────────▼────────────────┐
│ 服务端                                                      │
│ WildcardPatternItem.use → WildcardPatternMenu（服务端权威）  │
│ RuleCodec  ←→  物品 NBT（唯一真源，重启不丢）                 │
├────────────────────────────────────────────────────────────┤
│ 规则引擎（rule/ + match/，与 AE2 解耦，可单测）               │
│ TagIndex（标签快照）→ VariantResolver（族→变体→候选物品）      │
│ VariantBinder（同 group 求交，绑定同一材料）                  │
│ ExclusionFilter（排除规则求值）                               │
│ PatternInstantiator → List<ResolvedWildcardPattern>（内存）  │
├────────────────────────────────────────────────────────────┤
│ AE2 接口层（runtime/）                                       │
│ WildcardPatternDecoder implements IPatternDetailsDecoder      │
│   ↑ 通过 AE2 公开 API PatternDetailsHelper.registerDecoder()  │
│ WildcardPatternDetails implements IPatternDetails（内存样板） │
│ PatternInstanceCache（键：规则哈希+标签版本，TagsUpdated 失效）│
│ 1 个 mixin：样板供应器的 getAvailablePatterns 注入扩展结果     │
└────────────────────────────────────────────────────────────┘
```

---

## 4. 数据存储方案

### 4.1 唯一真源 = 物品 NBT（服务端）

通配样板是 `stacksTo(1)` 的物品，规则全部写在它自己的 NBT 里：

```json5
{
  "we_ver": 1,                       // 数据版本，用于将来迁移
  "in": [                            // 原料
    { "m": { "k": "FAMILY", "t": "ingot*" },   // 匹配器：族通配
      "n": 1, "grp": "A", "consume": true, "opt": false },
    { "m": { "k": "ITEM", "id": "minecraft:coal" }, "n": 1, "grp": "", "consume": true, "opt": false }
  ],
  "out": [                           // 产物
    { "m": { "k": "FAMILY", "t": "plate*" }, "n": 2, "grp": "A" }
  ],
  "ex": [                            // 排除
    { "k": "VARIANT", "v": "tin" },
    { "k": "MOD", "id": "thermal" },
    { "k": "PAIR", "in": "minecraft:iron_ingot", "out": "thermal:iron_plate" }
  ],
  "opt": { "bindPerGroup": true, "maxInstances": 256, "maxOptionalExpansion": 0 }
}
```

- **为什么放物品里**：随物品移动/存箱子/进 ME 存储都不会丢；服务器重启、退出重进都在；AE2 自己的样板也是这个思路，一致。
- **客户端怎么看到**：`ItemStack` 的 NBT 本来就会随容器同步到客户端（默认 `getShareTag()` 返回完整 tag），所以 GUI 打开时客户端已经有完整规则；我们仍然会发一份 `RuleSyncPacket` 带 **revision**，保证编辑过程中的权威性（乐观更新 + 冲突回滚）。
- **迁移**：`we_ver` 字段 + `RuleCodec.read()` 里的版本分支。

### 4.2 在途合成任务的持久化（重要）

我查过 AE2 15.4 的源码：`ExecutingCraftingJob` 在保存时只写 `IPatternDetails.getDefinition()`，
读档时用 `PatternDetailsHelper.decodePattern(AEItemKey, Level)` **重新解析**。

对普通样板没问题（一个物品 = 一条配方）；但通配样板是「一个物品 = N 条配方」，直接用同一个定义物品会导致读档后任务串味。

**解法**：每条实例化的 `getDefinition()` 返回一个**带「已解析配方」NBT 的、只在内存里构造的 AEItemKey**：

- 定义物品 = 通配样板 + NBT `{ "we_res": { 该条配方的完整输入/输出 } }`；
- 我们注册的 `IPatternDetailsDecoder` 看到这个 NBT 就能**逐字节精确还原**那一条配方；
- 即使读档后标签变了（加/删模组），已解析任务也不受影响（它自带完整配方）。

这一层完全走 AE2 的**公开 API**（`PatternDetailsHelper.registerDecoder`），**不需要 mixin**。

---

## 5. 规则与匹配引擎

### 5.1 匹配器（MatchSpec）支持的类型

| 类型 | 写法示例 | 含义 | 变体（variant）来源 |
| --- | --- | --- | --- |
| `ITEM` | `minecraft:iron_ingot` | 具体物品 | 无（固定） |
| `TAG` | `#forge:plates/iron` | 精确标签 | 标签路径末段（`iron`） |
| `FAMILY` | `ingot*` / `plate*` | **矿物辞典族通配**（复用上一版逻辑 + 内置别名表） | 子标签 / 物品名去词缀 |
| `MOD` | `@thermal` | 某模组的所有物品（默认关闭，需显式开启 + 上限） | 物品注册名 |
| `ANY` | `*` | 任意物品（默认禁用，仅调试用） | 物品注册名 |
| `NBT`（修饰） | 叠加在任意类型上 | 只匹配 NBT 子集相等的物品 | 不影响变体 |

`FAMILY` 复用上一版已经验证过的 `TagIndex` + `WildcardAliases`（`ingot*` → `forge:ingots` 及其子标签，
并做词缀剥离），所以「根据矿物辞典自动识别所有已注册金属」这条能力**保留**，只是不再落成物品。

### 5.2 变体绑定（「按组替代」）

- 每个条目可指定 **组名**（`A`/`B`/… 或「独立」）。
- 同一组的条目：把它们各自的候选变体集合**求交**，只保留共同变体。
- 于是 `ingot* -> plate*` 只要都标成 A 组：`iron` 只配 `iron`，`copper` 只配 `copper`（这就是上一版的配对语义，原样保留）。
- 「独立」的条目不做绑定（例如 `minecraft:coal` 不需要配对）。

### 5.3 排除规则（第 3 个标签页）

| 类型 | 作用 | 例 |
| --- | --- | --- |
| `ITEM` | 排除具体物品（不参与任何组合） | `!minecraft:iron_ingot` |
| `MOD` | 排除整个模组 | `@thermal` |
| `NBT` | 排除带某 NBT 的物品 | `{Damage:1}` |
| `VARIANT` | 排除某个变体 | `tin` |
| `PAIR` | 排除「某原料 × 某产物」这一种组合 | `iron_ingot → thermal:iron_plate` |
| `OUTPUT` | 排除某产物（保留原料） | `!thermal:*plate*` |

求值时机：**实例化之前**（第 6 节第 3 步），所以被排除的组合根本不会出现在 AE2 眼里；
GUI 上同时用它做「排除预览」（会生成 N 条 / 被排除 M 条 / 前 8 条示例）。

---

## 6. 运行时方案（核心）

### 6.1 为什么「完全不实例化」做不到（诚实说明）

AE2 的合成规划器（`CraftingCalculation`）是通过 `ICraftingProvider.getAvailablePatterns()`
拿具体 `IPatternDetails` 来建「产物 → 可用配方」映射的；`IPatternDetails` 的输入输出必须是具体 `GenericStack`。
AE2 的模型里**没有「一条样板匹配多种配方」这个概念**（`IInput.getPossibleInputs()` 只能表达「输入可替代」，不能表达「整条配方可替换」）。
所以运行时**必须**把规则实例化成若干条内存样板 —— 但这与「批量编码样板物品」是两件事：

| | 上一版（批量编码） | 本方案（运行时实例化） |
| --- | --- | --- |
| 产物 | N 个**物品**（进背包/容器/ME） | 0 个物品；只有内存对象 |
| 时机 | 编码时一次性做完 | 供应器向 CPU 提供配方时（`getAvailablePatterns`）才做 |
| 缓存 | 无 | 按「规则哈希 + 标签版本」缓存，规则/标签变了才重算 |
| 排除 | 编码前过滤一次 | 实例化前过滤；被排除的组合**从不出现** |
| 成本 | 与组合数成正比地占用背包/存储 | 典型 ≤ 几十个短命对象（`ingot* -> plate*` ≈ 20 条） |
| 上限 | 无 | `maxInstances`（默认 256）+ 超限警告 |

### 6.2 实例化算法（伪代码）

```
instantiate(rules):
  candidates(rule) =                       # 把 MatchSpec 解析成 {variant -> 候选物品}
      ITEM   -> { "-" : [item] }
      TAG    -> TagIndex.variants(tag)     # 含子标签
      FAMILY -> TagIndex.variants(resolve(alias(token)))   # ingot* -> forge:ingots
      MOD    -> 该命名空间物品，按物品名做 variant（受上限/开关限制）

  groups = VariantBinder.bind(rules)       # 同组求交 -> Map<group, Set<variant>>
  for v in ⋂(所有组的变体集合 或 各自独立):
      resolved = []
      for rule in rules.inputs+outputs:
          item = pickRepresentative(candidates(rule)[v])   # 命名空间优先级，沿用上一版
          if ExclusionFilter.rejects(rule, item, v, pairContext): skip v
          resolved.add(...)
      if 可选输入 且 maxOptionalExpansion > 0:
          为该变体的可选子集各生成一条（2^k，受上限约束）
      yield ResolvedWildcardPattern(v, resolved.inputs, resolved.outputs)
```

### 6.3 接进 AE2 的两个动作

1. **注册解码器（公开 API，无 mixin）**
   ```java
   PatternDetailsHelper.registerDecoder(new WildcardPatternDecoder());
   // isEncodedPattern(stack)  -> 是我们的通配样板
   // decodePattern(stack, lvl) -> 普通通配样板返回 null（它展开成多条，不能压成一条）
   // decodePattern(key,   lvl) -> 带 "we_res" NBT 的定义键 -> 精确还原那一条配方（任务读档用）
   ```
2. **让样板供应器看见 N 条实例化配方（1 个很小的 mixin）**
   ```java
   @Mixin(value = PatternProviderLogic.class, require = 0)   // 注入失败只降级，不崩游戏
   public abstract class PatternProviderLogicMixin {
       @Shadow @Final private List<IPatternDetails> patterns;
       @Shadow @Final private AppEngInternalInventory patternInventory;
       @Inject(method = "getAvailablePatterns", at = @At("RETURN"), cancellable = true)
       private void we_appendWildcardPatterns(CallbackInfoReturnable<List<IPatternDetails>> cir) {
           cir.setReturnValue(ProviderHook.merge(patterns, patternInventory));  // 带缓存
       }
   }
   ```
   - 目标类/字段/方法名都是 **AE2 自己的名字**（非混淆），所以**不需要 mixin refmap**；
   - `require = 0`：万一将来 AE2 改了内部结构，只是功能失效 + 日志告警，不会导致游戏崩溃；
   - 标签重载（加/删模组）时，通过 `ICraftingProvider.requestUpdate(node)` 主动让相关供应器重算（我们在 hook 里登记弱引用）。

   **备选（P3，无 mixin 路线）**：自研「通配样板供应器」方块，自己实现 `ICraftingProvider`
   （`getAvailablePatterns()` 返回同样的实例化结果，`pushPattern` 把输入推进相邻 `IItemHandler`）。
   全部走公开 API，代价是玩家要用我们的方块，而且**同样**需要 6.2 的解码器（任务持久化）。

### 6.4「不消耗 / 可选 / 替代」怎么落地

| 需求 | 实现 | 说明 |
| --- | --- | --- |
| **不消耗**（催化剂） | `IInput.getRemainingKey(AEKey)` 返回自身 | AE2 会把它送进供应器的返回槽 → 最终回到 ME 网络，净效果=不消耗（AE2 用同一机制处理桶等剩余物） |
| **按组替代**（任意木板算一类） | 见 5.2 变体绑定 | 也是「输入可替代」：`IInput.getPossibleInputs()` 返回该变体下的全部候选 |
| **NBT 匹配** | `IInput.isValid(AEKey, Level)` 里比较 NBT；`getPossibleInputs()` 给出带 NBT 的键 | 产物侧用 NBT 模板写出 |
| **可选输入** | 受 `maxOptionalExpansion` 控制（默认 **0 = 关闭**）：>0 时每个变体再按可选子集展开 2^k 条 | ⚠ AE2 没有「可有可无的输入」概念，只能靠多实例化表达；k 稍大就组合爆炸，所以我默认关闭并请你确认语义（见第 12 节） |

---

## 7. GUI 方案（三个标签页）

窗口 176×222（沿用上一版可用的布局经验），顶部固定区 + 三个标签页。

**顶部**：通配样板图标 · 规则摘要（`原料 2 · 产物 1 · 排除 3 · 运行时将生成 12 条`）· `复制` / `粘贴` / `导出SNBT` / `导入SNBT`。

**标签页 1「原料」**
- 条目卡片列表（可滚动，6~7 行/页）
- 卡片内容：`[匹配器图标+名称]` `数量 −/+` `消耗 ☑/☐` `可选 ☑/☐` `组 A/B/✕` `NBT ✎` `✕删除`
- 底部：`+ 添加原料` → 打开「匹配器编辑器」：
  - 类型单选：具体物品 / 标签 / 族通配 / 模组
  - 一个搜索框 + 结果列表（自绘，无 JEI 依赖）：输入 `ingot` 会同时列出物品与标签族；输 `#` 前缀只搜标签；输 `@` 前缀只搜模组
  - 右侧展示「该匹配器当前命中 N 个物品 / M 个变体」实时预览
- 点卡片上的匹配器图标可再次编辑（换成别的匹配器）

**标签页 2「产物」**
- 结构同上，字段为：匹配器 · 数量 · NBT（无消耗/可选；组仍然存在，用于和原料配对）

**标签页 3「排除 / 去除不想要的编码」**
- 排除条目列表，类型：物品 / 模组 / NBT / 变体 / **原料×产物组合** / 产物
- 「添加组合排除」：从已有原料条目 + 产物条目里各选一个（下拉），一键加入
- 右下角「排除预览」：`当前规则将生成 12 条 → 排除 3 条 → 实际 9 条`，下面列出被排除的前 8 条示例（红字），这样你能立刻看出排除是否写对

**交互与同步**
- 所有修改：客户端乐观更新 → 发 `RuleEditPacket(op, revision)` → 服务端改 NBT → 回 `RuleSyncPacket(完整规则, revision)`；版本不一致时以服务端为准重建界面（不会出现「客户端改了但服务器没存」）
- 服务端每 tick 校验玩家手里/容器里的那张样板还在（`stillValid`），物品没了就关界面
- 方块的 GUI 与物品的 GUI 是**同一套标签页组件**，方块版多一个「输出槽」用于产出通配样板

---

## 8. 兼容普通样板编码器（逐条对照验收标准）

| 验收项 | 本方案如何满足 |
| --- | --- |
| 普通样板编码器仍然可用 | **完全不碰** AE2 的样板编码终端（不改它的类、不换它的配方） |
| 新增本模组自己的通配样板编码器 | ① 方块「通配样板编码器」：配置规则 → 产出 1 张通配样板（消耗 1 个 AE2 空白样板，可配置）② 通配样板本身右键 = 同一个编辑器 |
| 只生成一个通配样板 | 编码动作只写 1 个物品；实例化只产生内存对象 |
| 右键打开三标签页 GUI | `WildcardPatternItem.use()` → 打开 `WildcardPatternMenu`（原料/产物/排除） |
| 配置可保存和同步 | NBT 服务端权威 + revision 同步协议（第 4 节） |
| 排除规则能过滤 | 实例化前求值（第 5.3、6.2 节） |
| 不再批量遍历生成所有样板 | 编码期 0 遍历；运行期按需实例化 + 缓存 + 上限 |
| 通配样板不能由普通编码器生成/编辑 | 我们的物品不是 AE2 的 blank/encoded pattern，AE2 终端的空白样板槽有过滤，既不会产出也无法编辑它 |
| 通配样板必须由本模组编码器创建/编辑 | 只有我们的方块/物品 GUI 能写 `we_*` NBT；我们的 GUI 遇到 AE2 样板会直接拒绝并提示 |

---

## 9. 类结构（新增 / 保留 / 删除）

```
com.ae2wildcard.encoder
├ 保留并复用
│  ├ WildcardEncoderMod / WildcardConfig / network/WildcardNetwork（扩展通道）
│  ├ match/TagIndex（标签快照 + 变体推导）        ← 上一版的核心资产，直接留
│  ├ match/WildcardAliases（ingot* -> forge:ingots 等别名表）
│  ├ block/WildcardPatternEncoderBlock（语义改为「创建/编辑一张通配样板」）
│  ├ command/WildcardCommands（改成 rules/preview/why 等）
│  └ tools/dev/*（离线构建链，已能编译+重映射+校验 AE2 15.4.10）
├ 新增：规则层（纯数据，可单测）
│  ├ rule/WildcardRules, InputRule, OutputRule, ExcludeRule, MatchSpec, MatchKind
│  ├ rule/RuleCodec（NBT ↔ 对象，含 we_ver 迁移）、RuleSummary（统计/预览）
├ 新增：匹配层
│  ├ match/VariantResolver（MatchSpec → {variant → 候选物品}）
│  ├ match/VariantBinder（按组求交绑定）
│  ├ match/ExclusionFilter（排除求值）
│  └ match/PatternInstantiator（规则 → List<ResolvedWildcardPattern>）
├ 新增：AE2 接口层
│  ├ runtime/WildcardPatternDecoder（实现 IPatternDetailsDecoder 并注册）
│  ├ runtime/ResolvedPatternKey（定义键：通配样板 + 已解析配方 NBT）
│  ├ runtime/WildcardPatternDetails（实现 IPatternDetails）
│  ├ runtime/WildcardPatternInput（实现 IPatternDetails.IInput）
│  ├ runtime/PatternInstanceCache（缓存 + TagsUpdatedEvent 失效）
│  └ runtime/ProviderHook（供 mixin 调用；也是将来自研供应器的公共入口）
├ 新增：GUI
│  ├ item/WildcardPatternItem（右键开界面、tooltip 摘要、stacksTo(1)）
│  ├ menu/WildcardPatternMenu（三标签页容器 + revision 同步）
│  ├ client/WildcardPatternScreen, widget/RuleListWidget, widget/MatcherEditor, widget/ItemSearchList
├ 新增：1 个 mixin
│  └ mixin/PatternProviderLogicMixin（require = 0，仅追加实例化结果）
├ 新增：构建配套
│  └ wildcardencoder.mixins.json + mods.toml 的 [[mixins]] + MANIFEST 的 MixinConfigs
└ 删除/停用
   ├ craft/PatternDelivery（批量把样板塞进容器/背包）——不再需要
   └ craft/Ae2PatternEncoder（批量 encodeProcessingPattern）——不再需要
```

---

## 10. 分阶段实施与验收 Demo

**P1（物品 + 规则 + GUI + 编码器）** — 无 AE2 运行时集成，可独立验收
- 交付：通配样板物品、三标签页 GUI、编码器方块、NBT 持久化 + 同步、tooltip、`/wildcardencoder rules|preview`
- Demo：编出一张 `ingot*(A) + coal → 2× plate*(A)`；重启服务器后规则仍在；排除页加 `tin` 后预览显示「12 条 → 11 条」；把普通 AE2 样板放进我们的 GUI 会被拒绝

**P2（运行时接入）** — 真正的「能合成」
- 交付：`IPatternDetailsDecoder` 注册 + 实例化引擎 + mixin 注入 + 排除生效 + 不消耗/替代
- Demo：把通配样板放进**原版样板供应器** → ME 里请求 1 个 `copper_plate` → 合成 CPU 正确按 `copper_ingot ×2 + coal → 2× copper_plate` 规划并推动；请求 `tin_plate` 被排除规则挡住；把任务挂在合成 CPU 上重启服务器，任务仍能继续（验证 6.2 的定义键方案）

**P3（可选增强）**
- 自研「通配样板供应器」方块（无 mixin 路径）
- JEI 拖拽集成（需要 JEI 依赖）
- 模组/NBT 高级匹配、可选输入的完整语义
- RS 适配评估（需要你指定 RS 版本）

---

## 11. 风险与限制（先说清楚）

1. **运行时必然实例化**（6.1 节）：AE2 的规划器只认具体 `IPatternDetails`。我不把它落成物品、不做编码期穷举，并把成本限制在「内存对象 + 缓存 + 上限」。如果你要的是「连内存里都不许展开」，那需要改 AE2 的合成规划器（等于 fork/大改 AE2），我不会走这条路。
2. **mixin 依赖 AE2 内部类 `PatternProviderLogic`**：已用 `require = 0` + `@Shadow` 名字都是 AE2 自身名字（无需 refmap）来降风险；将来 AE2 改结构时只是功能失效并可切到 P3 的自研供应器。
3. **可选输入**语义受 AE2 限制（2^k 展开），默认关闭，见第 12 节。
4. **不消耗**依赖 AE2 的「剩余物 → 供应器返回槽 → 回网络」链路，我会在 P2 实测确认。
5. 我把 `@thermal` / `*` 这类「大范围匹配器」默认关闭，必须在 GUI 里显式开启，避免一个规则命中几千个物品。
6. 无法在沙箱里启动游戏客户端，所以最终「进游戏点一遍」需要你配合；我会给出逐步自检清单（命令 + 预期输出）。

---

## 12. 需要你确认的问题

1. **运行时载体**：原版样板供应器 + 1 个小 mixin（推荐）／自研「通配样板供应器」方块（无 mixin）／两者都要（先 mixin，P3 再补方块）？
2. **是否接受「运行时内存实例化 + 缓存 + 上限」**（第 6.1 节）？这是 AE2 的硬约束。
3. **物品/标签选择器**：自绘搜索列表（无依赖，推荐）还是必须支持 JEI 拖拽（需要加 JEI 依赖）？
4. **「可选输入」**：默认关闭（推荐）／开启并限定最多 2 个可选（≤4 条变体）／改成「二选一（替代）」语义？
5. **「不消耗」**：按 6.4 的方案做（推荐）／只当作匹配条件、消耗与否交给机器？
6. **通配样板允许复制粘贴**：GUI 内复制/粘贴 + SNBT 导入导出（推荐）／只允许 GUI 内复制／不需要？
7. 范围确认：**先做 P1+P2**（做到「放进样板供应器就能合成」）再谈 P3，可以吗？
