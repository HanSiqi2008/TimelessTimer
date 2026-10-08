# TimelessTimer 测试报告

两条独立验证路径：**离线核心逻辑测试**（不依赖 Minecraft，可反复运行）与 **Forge 完整构建**。

---

## 1 离线核心逻辑测试

**为什么要它**：`gradle build` 需要先下载并反编译 Minecraft/Forge（数分钟到数十分钟），而配置解析、cron 匹配、文件规范这些纯逻辑完全不需要 MC API。因此把核心类与桩类一起编译运行，能在构建之前就把逻辑问题全部抓出来——事实上它确实抓出了两个真 bug（见第 3 节）。

### 运行方式

```powershell
$jdk = '<你的 JDK 17 路径>'
$prod = 'TimelessTimer\src\main\java\com\hsq08\timelesstimer'
& "$jdk\bin\javac" -encoding UTF-8 -d .harness\out `
    "$prod\Config.java","$prod\Toml.java","$prod\ParseException.java","$prod\TimerSpec.java", `
    "$prod\UnitFiles.java","$prod\UnitSpec.java","$prod\UnitEntry.java", `
    '.harness\stub\com\hsq08\timelesstimer\TimelessTimer.java', `
    '.harness\stub\com\hsq08\timelesstimer\Messages.java', `
    '.harness\test\com\hsq08\timelesstimer\CoreTests.java'
& "$jdk\bin\java" -cp .harness\out com.hsq08.timelesstimer.CoreTests
```

### 结果

```
PASSED=115 FAILED=0
```

### 覆盖范围

| 分组 | 条数 | 覆盖内容 |
|---|---|---|
| `config.*`、`toml.*` | 21 | config.toml 解析、版本/名称/方案校验、生成文件与规范样例逐字节一致、以及各类格式错误的拒绝 |
| `countdown.*` | 12 | 秒/分/时/天字段解析与边界（0、59、23、3650、越界 60/24/3651）、第 5 字段必须为 `*`、禁止逗号与负号、字段数必须为 5 |
| `schedule.*` | 20 | 逗号多值、`*` 通配、周几 0/7 均为周日、1~6 为周一~周六、经典 cron 的"日/周或"语义、`nextAfter` 跨天跨年推算、范围/步长/`H` 一律拒绝 |
| `unit.*` | 34 | 单元文件序列化与往返、引号/反斜杠/中文转义、`autostart` 与 `next` 的"不传则不写"、缺省值（countdown=false、schedule=true）、8 类格式错误的拒绝 |
| `name.*`、`path.*`、`scan.*` | 19 | 命名规则（含中文/空格/点/斜杠/`..` 拒绝）、目录布局、**路径穿越防护**、真实临时目录的加载与失败收集 |
| `messages.*`、`reload.*` | 9 | 表头严格为 `[name]:`（无尾随空格）、reload 后单元集合按磁盘重建、坏配置被拒绝但不影响单元读取 |

### 关键断言摘录

```
ok   config.file.header          → 生成文件以规范样例的原文开头
ok   countdown.duration          → "0 8 1 6 *" = 527,280 秒（6天1时8分）
ok   schedule.match.0800/1200/1800 → "0 8,12,18 1 6 *" 三个时间点均命中
ok   schedule.dom.or.dow.dow     → "0 0 1 * 1" 在 2026-06-08（周一）命中
ok   unit.roundtrip.next（3 项） → --next="" 写入 next=""，不传则完全不写
ok   unit.schedule.default.autostart → 未写 autostart 的 schedule 默认 true
ok   path.traversal.refused      → ../../evil 被拒绝
ok   messages.list.header        → [TimelessTimer]:（无尾随空格）
ok   reload.bad.config.rejected  → version="9" 被拒绝
```

---

## 2 Forge 完整构建

**结果：`BUILD SUCCESSFUL`**（`clean build`，完整日志见工作区 `build.log`）

```
> Task :clean
> Task :compileJava
> Task :processResources
> Task :classes
> Task :jar
> Task :downloadMcpConfig
> Task :extractSrg
> Task :createMcpToSrg
> Task :reobfJar
> Task :jarJar SKIPPED
> Task :reobfJarJar SKIPPED
> Task :assemble
> Task :build
```

构建命令：

```powershell
$env:JAVA_HOME    = '<工作区>\.jdk17\jdk-17.0.20.1+1'
$env:GRADLE_USER_HOME = '<工作区>\.gradle-home'
gradle -p TimelessTimer --project-cache-dir <工作区>\.pcache clean build --no-daemon --console=plain
```

产物：`TimelessTimer/build/libs/timelesstimer-0.0.1.jar`，64,303 字节，
SHA-256 `66FF6CBC0307262B0A9630A34DD19F6964172D1B39F9DD3A0972385BEB6CC470`。

> 注意：本工作区的文件权限不允许沙箱派生的子进程（`javac`、Gradle daemon）写入项目目录，
> 因此这次构建在独立提权进程中执行。详见 `README.md` 第 7 节的"本机环境说明"。

### 2.1 构建阶段暴露的编译错误（已修复）

`compileJava` 首次跑通后报出 3 个真实编译错误，全部修正后构建通过：

| 错误 | 位置 | 原因与修复 |
|---|---|---|
| `找不到符号: 方法 getWorldPath(LevelResource)` | `TimelessTimer.resolveGameDirectory` | `ServerLifecycleHooks.getWorldPath` 在此版本不存在，改用 `MinecraftServer#getWorldPath` |
| `找不到符号: 方法 toAbsolutePath()` （`File`） | 同上 | `MinecraftServer#getServerDirectory` 返回 `File`，补 `.toPath()` |
| `performPrefixedCommand` 参数不匹配 | `TimerEngine.dispatch` | 此映射只有 `(CommandSourceStack, String)` 两参重载；改为该重载并检查返回值，返回 0 时写 warning |

另外把 `TimelessTimer.UnitEntry` 这种限定名改为同包简名，避免与局部类名解析混淆。

---

## 3 测试发现并修复的缺陷

| # | 问题 | 影响 | 修复 |
|---|---|---|---|
| 1 | TOML 解析器要求"所有键必须写在某个表内"，而规范的 `config.toml` 把 `version = "1"` 写在**任何表之外**（根级键） | 默认生成的 `config.toml` 会在下次启动时被判为格式错误，模组直接不生效 | 解析器支持根级键（`Toml.Table.ROOT`），`Config` 从根级读 `version`；单元文件仍禁止根级键 |
| 2 | `/timer list` 表头用 `prefixed("")` 生成，输出 `[TimelessTimer]: `（**多一个尾随空格**） | 与规范样例 `[TimelessTimer]:` 不一致 | 新增 `Messages.header()`，表头不带尾随空格 |

第 1 处是"测试先于构建"抓到的，它会让模组在真实服务器上完全失效；第 3 节的编译错误则只有真正跑一次
Forge 构建才能发现。

---

## 4 未覆盖的部分（需真实服务器验证）

离线测试用桩类替换了 MC API，因此以下行为**未**被自动测试覆盖，需要在 1.20.1 Forge 服务端上手验证：

| 项 | 验证方法 |
|---|---|
| 指令注册与权限节点 | `/timer` 在 OP 3 级与无权限玩家下的可见性；LuckPerms 装/不装两种情况 |
| 聊天文案实际显示 | 每条指令的输出是否与规范逐字一致 |
| vanilla 计时精度 | `scheduler = "5 0 0 0 *"` 观察约 5 秒后触发 |
| fox 独立线程计时 | 用 `/debug` 或卡顿插件制造 TPS 下降，确认倒计时不受影响、卡顿期间 exec 延后 |
| `next` 链（含 A→A、A→B→A） | 建两个短倒计时互相链接，观察是否先结束后启动 |
| autostart 顺序 | 多个 `autostart = true` 单元，确认按文件名字母序启动 |
| 单元文件损坏提示 | 故意写坏一个 `.toml`，用管理员账号登录看聊天栏提示 |
| reload | 改 `name`/`timer` 后 `/timer reload`，确认新 name 生效、旧计时器已停 |
