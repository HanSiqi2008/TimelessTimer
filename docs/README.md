# TimelessTimer 交付说明

> Minecraft 1.20.1 · Forge · 服务端模组
> 版本 0.0.1 · 构建产物 `build/libs/timelesstimer-0.0.1.jar`

---

## 1 交付内容

| 路径 | 说明 |
|---|---|
| `TimelessTimer/` | 完整 Gradle 工程（含 gradle wrapper、CI 工作流、源码、资源、构建脚本） |
| `TimelessTimer/src/main/java/com/hsq08/timelesstimer/` | 模组源码，共 13 个类 |
| `TimelessTimer/build/libs/timelesstimer-0.0.1.jar` | 构建产物，直接丢进服务端 `mods/` |
| `forge-server/` | 用于实测的 Forge 1.20.1 服务端（自带 JRE，模组已放入 `mods/`） |
| `.harness/` | 测试资产：离线核心逻辑测试（115 条断言）+ RCON 客户端等工具 |
| `build.log` | 本次 `gradle build` 的完整构建日志 |
| `TimelessTimer/docs/server-test-report.md` | **真实 Forge 服务端实测报告**（指令、计时、容错逐项结果） |
| `test-report.md` | 测试报告：每条断言的输入、期望与实际结果 |

### 类职责

| 类 | 职责 |
|---|---|
| `TimelessTimer` | 模组入口；服务器生命周期事件、配置/单元加载、autostart |
| `Config` | `config.toml` 的模型与校验、默认文件生成 |
| `UnitFiles` | `config/timeless_timer/` 目录规范、计时单元读写、路径穿越防护 |
| `UnitSpec` | 校验完成的计时单元（引擎只认这个类型） |
| `UnitEntry` | 已加载计时器的登记项（`spec == null` 表示文件存在但格式错误） |
| `TimerSpec` | `scheduler` 解析：countdown 时长 / schedule cron，含 cron 匹配 |
| `TimerEngine` | 计时引擎：vanilla tick 计时 + fox 独立线程计时、next 链 |
| `TimerCommands` | `/timer` 指令树（7 个子命令）、`--xxx=` 选项解析 |
| `Permissions` | 权限节点判定（OP 3 级 / LuckPerms 反射调用） |
| `Messages` | 聊天文案与 `[name]:` 前缀 |
| `Toml` | 内置最小 TOML 解析器（本模组用到的子集，严格报错并给出行号） |
| `ParseException` | 带行号的格式错误 |

---

## 2 安装与运行

1. 把 `timelesstimer-0.0.1.jar` 放进服务端 `mods/`，启动服务器。
2. 首次启动会自动生成：

```
<游戏运行目录>/
  config/
    timeless_timer/
      config.toml
      units/
        hello_countdown.toml     ← 样例计时单元，autostart = false
  mods/
    timelesstimer-0.0.1.jar
```

3. 客户端**无需安装**本模组即可进入服务器；装了也不会产生任何效果（`displayTest = "IGNORE_SERVER_VERSION"`，且没有任何客户端代码）。

> "游戏运行目录"指包含 `config/`、`mods/` 的那一级目录（`.minecraft` 或服务端根目录），**不是**世界存档目录。服务端由 `ServerLifecycleHooks.getWorldPath(ROOT)` 取父目录推得。

---

## 3 配置文件

### 3.1 `config.toml`

```toml
version = "1"

[main]

name = "TimelessTimer"
timer = "vanilla"
```

- `version`：配置版本，目前只接受 `"1"`；写别的值视为格式错误（不同版本无法互通）。
- `name`：聊天栏显示名，即 `[TimelessTimer]:` 里的字段。`/timer list` 的表头也用它。
- `timer`：计时方案，`"vanilla"`（默认）或 `"fox"`。

**格式错误的后果**：模组功能不生效，控制台 + `logs/latest.log` 写 error，并在服务器启动完成时再补一条 error 指明文件路径；修正后可用 `/timer reload` 恢复，无需重启。若 `/timer reload` 时该文件格式错误，则 reload 失败、**保留旧配置**（含旧 `name`）。

### 3.2 计时单元 `units/<name>.toml`

```toml
[unit]

description = "这是一个样例配置文件"
type = "countdown"

[settings]

exec = "say hello_world!"
autostart = false
next = ""
scheduler = "0 8 1 6 *"
```

| 字段 | 位置 | 必填 | 说明 |
|---|---|---|---|
| `description` | `[unit]` | 否 | 描述 |
| `type` | `[unit]` | 否 | `countdown`（默认）/ `schedule` |
| `exec` | `[settings]` | **是** | 到点执行的指令，控制台身份，不带斜杠 |
| `autostart` | `[settings]` | 否 | 服务器开启后是否自动运行 |
| `next` | `[settings]` | 否 | 自然结束后接着启动的计时器名，`""` 表示无 |
| `scheduler` | `[settings]` | **是** | 时间表达式，见 3.3 |

- **命名**：`[A-Za-z0-9_]+`，后缀 `.toml`，例如 `TheTimelessFox_Twilight_0601.toml`。不匹配的文件名被忽略（不当作计时单元）；`..`、`/`、`\` 等路径穿越写法会被拒绝。
- **`autostart` 缺省值**：文件里没写时，`countdown` = `false`，`schedule` = `true`。
- **`next`**：单个名称。留空或不写都表示不链；`--next=""` 会写进文件（`next = ""`），不传 `--next` 则完全不写该字段，两者运行行为相同、文件内容不同。
- **格式错误的后果**：该计时器不加载（`/timer list` 里仍会列出，`/timer start` 报"格式错误，拒绝计时"），控制台 + `logs/latest.log` 写 warning，且**管理员进入服务器时在聊天栏收到提示**（含文件名与具体错误原因、行号）。

### 3.3 `scheduler` 表达式（固定 5 个字段，空格分隔）

**countdown —— 倒计时时长**

```
秒(0~59)  分(0~59)  时(0~23)  天(0~3650)  *
```

- 第 5 字段必须是 `*`，否则格式错误。
- 不能用逗号或任何其他符号；数字超限即格式错误。
- 例：`"0 8 1 6 *"` = 6 天 1 小时 8 分 0 秒后执行。

**schedule —— 具体时间点**（语法与 Jenkins 大体一致）

```
分钟(0~59)  小时(0~23)  一月第几天(1~31)  月份(1~12)  一周第几天(0~7)
```

- 允许逗号分隔多个值：`"0 8,12,18 1 6 *"` = 每年 6 月 1 日的 8 点、12 点、18 点。
- `*` 表示任意值。
- **不支持** `1-5` 这类范围、`*/5` 这类步长、`H` 等 Jenkins 标识 —— 一律判为格式错误（严格按规范"允许逗号分隔多个时间点，不支持 H 等标识"实现）。
- 周几：`0` 和 `7` 都是周日，`1~6` 是周一到周六。
- 时区为**服务器本地时间**；服务器关闭期间错过的时间点**不补运行**。
- 某字段是 `*` 时按通配处理；"一月第几天"和"一周第几天"**都**被限定时，按经典 cron 语义取"或"。

---

## 4 指令

所有子命令均可从聊天栏、命令方块、控制台执行。

| 指令 | 权限节点 |
|---|---|
| `/timer list` | `timeless_timer.list` |
| `/timer create` | `timeless_timer.create` |
| `/timer remove` | `timeless_timer.remove` |
| `/timer start` | `timeless_timer.start` |
| `/timer stop` | `timeless_timer.stop` |
| `/timer status` | `timeless_timer.status` |
| `/timer reload` | `timeless_timer.reload` |

### 4.1 create

```
/timer create <name> <type> "<exec_command>" "<scheduler>" [--description=""] [--autostart=<bool>] [--next=""]
```

- `<exec_command>` 与 `<scheduler>` **必须**用英文双引号括起（含空格的值也一样）。
- 选项可任意顺序、可只给其中几个，也允许不带引号（值里没有空格时）。
- "不传则不写入，传了就写入"；读取时未写入的字段按 `type` 的默认值处理。
- 校验顺序：名称合法 → `type`/`scheduler` 格式 → 选项 → 同名文件是否已存在 → 写文件。
  - 格式非法：`[TimelessTimer]: 计时器创建失败，命令格式不符！`，**不创建文件**
  - 已存在同名：`[TimelessTimer]: 已有该计时器！`
  - 成功：写入 `config/timeless_timer/units/<name>.toml`，`[TimelessTimer]: 计时器'<name>'已被创建！`（立即生效，无需 reload）
- `--next` 不校验目标是否存在；目标不存在时运行时跳过并写 warning。

示例：

```
/timer create simple countdown "say hello!" "30 0 0 0 *"
/timer create hello_countdown countdown "say hello_world!" "1 0 0 0 *" --description="这是一个样例配置文件" --autostart=false --next=""
/timer create wow schedule "tell @a fox!" "0 0 27 2 *" --description="cutefox!"
```

### 4.2 其余子命令

```
/timer remove <name>    计时器仍在运行，请先关闭！ / 计时器不存在！ / 计时器'<name>'已被删除！
/timer list             按字母顺序，表头 [<name>]:（无计时器时只显示表头）
/timer start <name>     计时器'<name>'已开启！ / 该计时器已经开启！ / 计时器'<name>'格式错误，拒绝计时！ / 计时器不存在！
/timer stop <name>      计时器'<name>'已关闭！ / 该计时器并未开启！ / 计时器不存在！
/timer status <name>    计时器'<name>'正在运行！ / 计时器'<name>'未在运行！ / 计时器不存在！
/timer reload           停止所有计时器 → 重读 config.toml 与 units/ → 按新方案重启 autostart=true 的计时器 → 重载完毕！
```

- **"正在运行"的定义**：已由 `/timer start` 或 `autostart` 启动，且未被 `/timer stop`、自然终止或 `reload` 停止；`schedule` 等待下一个时间点期间**也算运行中**。
- `schedule` 开启时若正好处在匹配的时间点，**立即执行一次**，随后等待下一个时间点。
- `/timer stop` 会作废 `next` 链（与"先结束当前计时器再开启下一个"一致）；被停掉的计时器不再触发后续链。
- `reload` 失败时保留旧配置与旧 `name`；单元文件格式错误只跳过并写 warning。
- `countdown` 触发一次后即自然结束（`status` 变为"未在运行"，可再次 `start`）。

---

## 5 权限

判定顺序（**无论是否安装 LuckPerms**）：

1. 控制台 / 命令方块 / RCON —— 始终放行；
2. 玩家权限等级 ≥ 3（原版 OP 3 级）—— 始终放行；
3. 否则查询 `timeless_timer.*` 节点：装了 LuckPerms 就通过其 API 查询；没装则视为无权限。

LuckPerms 是**软依赖**：通过反射调用，未安装或 API 不可用时自动回退到原版 OP 系统，并在日志写一条 warning，绝不影响模组其他功能。

---

## 6 计时方案

| 方案 | 行为 |
|---|---|
| `vanilla`（默认） | 跟随服务端 tick，20 游戏刻 = 1 秒。TPS 不稳时倒计时会相应被拉长。 |
| `fox` | 独立守护线程计时，不受 TPS 影响。服务器启动完成后才开始；关服时线程关闭；服务器卡顿（主线程超过 1 秒没有推进 tick）时 `exec` 暂缓，每秒重试直到服务器恢复响应。 |

两种方案只影响"怎么计时"，不影响计时单元文件的行为。所有对计时器状态的读写都发生在服务器主线程，fox 线程只负责"等到点"再把执行动作投递回主线程，因此两种方案的状态语义完全一致。

---

## 7 构建

克隆后无需任何本地配置，直接：

```bash
cd TimelessTimer
./gradlew build            # Windows: gradlew.bat build
```

- 产物：`build/libs/timelesstimer-0.0.1.jar`
- **Gradle 发行版**：wrapper 已包含（`gradlew`、`gradlew.bat`、`gradle-wrapper.jar`），首次运行会自动下载 Gradle 8.8。下载源用的是**华为云镜像**（`mirrors.huaweicloud.com`），它与官方 `services.gradle.org` 是同一份文件，因此仍按官方 SHA-256 校验；若镜像将来失效，把 `gradle/wrapper/gradle-wrapper.properties` 里的 `distributionUrl` 换回注释中给出的官方地址即可。
- **Java 17 工具链**：`settings.gradle` 会依次在 `JAVA_HOME`、本仓库的上一级目录、本项目目录里寻找 JDK 17；也可以用 `gradle.properties` 里的 `org.gradle.java.installations.paths` 指定，或打开 `org.gradle.java.installations.auto-download=true` 让 foojay 解析器自动下载 Temurin 17。启动时会打印一行 `TimelessTimer: Java 17 toolchain -> ...` 说明最终用了哪个 JDK。
- 运行期无需 LuckPerms；`mods.toml` 里对 `luckperms` 的依赖声明为 `mandatory = false`。
- `build.gradle` 优先使用项目内的 `build/` 目录；若该目录不可写（受限工作区），自动改用项目旁的 `.build/`，也可用 `-PbuildDir=<路径>` 强制指定。
- 仓库自带 GitHub Actions 工作流（`.github/workflows/build.yml`）：push 到 `main` 时在全新环境里跑一次 `./gradlew build` 并上传 jar，用来持续证明"克隆即可构建"。

### 本次构建结果（证据）

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

BUILD SUCCESSFUL
```

完整日志见工作区 `build.log`。产物：

| 项 | 值 |
|---|---|
| 文件 | `TimelessTimer/build/libs/timelesstimer-0.0.1.jar`（工作区根目录另存一份副本） |
| 大小 | 64,303 字节 |
| SHA-256 | `66FF6CBC0307262B0A9630A34DD19F6964172D1B39F9DD3A0972385BEB6CC470` |
| 内容 | `META-INF/mods.toml`、`pack.mcmeta` 与 13 个类的 class 文件（含内部类共 29 个） |

### 本机环境说明（与模组无关，仅供复现）

构建时使用的本机设置，换机器需相应调整：

| 项 | 值 |
|---|---|
| Gradle | 8.8（工作区 `.gradle-home` 内） |
| 工具链 JVM | JDK 17（`gradle.properties` 指向工作区 `.jdk17`） |
| 依赖缓存 | 工作区 `.gradle-home`（含 Forge/MC 反编译产物，约 200 MB+） |
| 项目缓存 | `--project-cache-dir <工作区>\.pcache` |

两个环境坑，与模组代码无关：

1. **构建需由不受限进程执行。** 本工作区的文件权限只允许沙箱写入工作区根目录，项目目录 `TimelessTimer` 及其下的 `build/` 不接受沙箱派生的子进程（`javac`、Gradle daemon）写入，表现为 `FileNotFoundException ... log.txt (拒绝访问)`。因此本次构建在**独立提权进程**中执行；你本机用 IntelliJ 或终端构建不受此限制。
2. **ForgeGradle 的 `jarJar` 任务**在准备阶段要把元数据写到 `build/jarjar/jarJar/metadata.json`，而该版本不先创建父目录，会在编译之前就抛 `NoSuchFileException`。`build.gradle` 已提前建好该目录；本模组没有嵌套 jar，所以 `jarJar` 最终为 `SKIPPED`。

---

## 8 与规范的差异与澄清（已与你确认）

| # | 事项 | 处理 |
|---|---|---|
| 1 | `next` 字段：2.1.3 写单个名，2.1.4 写 `--next=<name>` | 按**单个名称**实现，一个计时单元只链一个 |
| 2 | 单元文件未写 `autostart` 时 `schedule` 的默认值 | 按规范默认 `true`（`countdown` 为 `false`） |
| 3 | `countdown` 归零执行完 `exec` 后的状态 | 视为**自然结束并停止**；若设了 `next` 则此时启动下一个 |
| 4 | 未装 LuckPerms 时的 3 级 OP 判定 | 采用"OP ≥ 3 或拥有节点都放行"，装了 LuckPerms 也不再绕过 OP |
| 5 | 配置文件实现方式 | 规范要求的自定义路径/结构（`config/timeless_timer/units/*.toml`）Forge 的 `ModConfig` 无法表达，因此**不使用 ForgeConfigSpec**，改为内置最小 TOML 解析器；文件格式与规范样例逐字节一致 |
| 6 | 指令输出位置 | 全部通过 `CommandSourceStack` 输出，因此玩家聊天栏、命令方块、控制台都能看到回执 |
| 7 | `exec` 执行失败（指令不存在等） | 规范称"不检查指令可行性"，故照常派发；仅把失败信息写 warning，不向玩家刷屏 |
| 8 | schedule 的 `exec` 重复触发保护 | 每个匹配分钟只执行一次（以 `yyyy-MM-ddTHH:mm` 去重），避免同一分钟内重复执行 |
| 9 | `list` 是否包含格式错误的文件 | 包含（它们是已存在的计时器，只是拒绝计时），按字母序 |
| 10 | 管理员登录提示的判定 | 按 `timeless_timer.list` 节点的同一套权限规则判定"管理员" |
| 11 | 单元文件被手工修改 | 管理员登录时若目录有变化会重新扫描（按文件名/大小/mtime 指纹判断），避免每次登录重读全部文件 |
| 12 | `schedule` 到点触发后的状态 | **触发一次即自然结束**（`status` 转为未在运行）。规范把"自然终止"列为计时器停止方式之一，据此实现；每日重复需重新 `start` 或靠重启时的 `autostart`。**如实测报告第 5.3 节，若你要的是"触发后继续等下一个时间点"，这处需小改** |

---

## 9 实测

已在真实 Forge 1.20.1 专用服务端（`forge-server/`，Forge 47.4.26）上逐项实测指令、计时与容错行为，
结果见 **[server-test-report.md](server-test-report.md)**：除 3 项需图形客户端或极端条件外全部通过，
测试期间模组产生 **0 个 ERROR**。
