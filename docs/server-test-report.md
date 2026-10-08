# TimelessTimer 实测报告（真实 Forge 服务端）

测试环境：**Minecraft 1.20.1 · Forge 47.4.26 · 专用服务端**（`forge-server/`，自带 JRE 17）
驱动方式：RCON（`rcon.port=25585`）+ 读取 `logs/latest.log`
模组：`timelesstimer-0.0.1.jar`（SHA-256 `66FF6CBC…6CC470`）

结论：**除 3 项需要图形客户端或极端条件外，规范要求全部实测通过；本模组在测试期间产生 0 个 ERROR。**

---

## 1 启动与文件生成

| 验证项 | 结果 |
|---|---|
| 服务端加载模组 | `TimelessTimer 已加载：计时器数据存放在 <游戏运行目录>/config/timeless_timer/` |
| 首次生成 `config.toml` | ✅ 与规范样例**逐字一致**（含根级 `version = "1"`） |
| 首次生成 `units/hello_countdown.toml` | ✅ 与规范样例结构一致，`autostart = false` |
| 配置目录位置 | ✅ `<服务端根>/config/timeless_timer/`（不在世界存档内） |
| 日志中文编码 | ✅ `logs/latest.log` 中文完好；仅 Windows 控制台因代码页 GBK 显示乱码（见第 5 节） |

## 2 指令实测（RCON，逐条对照规范文案）

| 指令 | 实际输出 |
|---|---|
| `/timer list` | `[TimelessTimer]:` + 按字母序的 `  <name>` 行 |
| `/timer status <未开启>` | `[TimelessTimer]: 计时器'hello_countdown'未在运行！` |
| `/timer start` | `[TimelessTimer]: 计时器'hello_countdown'已开启！` |
| `/timer status <运行中>` | `[TimelessTimer]: 计时器'hello_countdown'正在运行！` |
| `/timer stop` | `[TimelessTimer]: 计时器'hello_countdown'已关闭！` |
| `/timer start <已开启>` | `[TimelessTimer]: 该计时器已经开启！` |
| `/timer stop <未开启>` | `[TimelessTimer]: 该计时器并未开启！` |
| `/timer start/stop/status/remove <不存在>` | `[TimelessTimer]: 计时器不存在！` |
| `/timer remove <运行中>` | `[TimelessTimer]: 计时器仍在运行，请先关闭！` |
| `/timer start <格式错误>` | `[TimelessTimer]: 计时器'broken_unit'格式错误，拒绝计时！` |
| `/timer reload` 成功 | `[TimelessTimer]: 重载完毕！` |
| `/timer reload` 失败 | `[TimelessTimer]: 重载失败，配置文件格式错误，已保留原配置。` |

全部与规范文案逐字一致。

## 3 计时能力实测

| 场景 | 结果 |
|---|---|
| `countdown` 1 秒 → 触发 | ✅ 触发 `CHAIN_TIMER_FIRED` |
| `next` 链（1 秒 → 3 秒） | ✅ 第一个结束后拉起第二个，两者先后正确触发并各自自然结束 |
| `next` 指向不存在 | ✅ 写 warning `计时器 chain：下一个计时器 'does_not_exist' 不存在，跳过本次 next。`，不崩溃、不广播 |
| `schedule`（vanilla） | ✅ 目标 `21:33` → 实际 **21:33:00.020**，仅触发 1 次，随后自然结束 |
| `schedule`（fox） | ✅ 目标 `21:37` → 实际 **21:37:00.034**，仅触发 1 次 |
| `fox` 倒计时 5 秒 | ✅ 系统时间 5 秒后触发，日志标注 `fox 模式` |
| `autostart`（倒计时） | ✅ 重启后自动启动，5 秒后触发 |
| `autostart` 顺序（`schedule` 默认 true） | ✅ `reload` 后按字母序启动 `aaa_auto`、`t_sched`、`w3` |
| 负载下倒计时精度 | ✅ 20 秒倒计时期间持续制造区块加载压力（44.7 s 内 60 次 forceload），仍准时触发 |
| 自然结束后状态 | ✅ 触发后 `status` 显示"未在运行"，可重新 start |

## 4 配置与容错实测

| 场景 | 结果 |
|---|---|
| 单元文件格式错误 | ✅ 控制台+日志 warning（含文件名与原因，`autostart 只能是 true 或 false，当前为 "yes"`）；`list` 仍列出；`start` 拒绝计时；**不影响其他计时器** |
| `config.toml` 格式错误（启动时） | ✅ 日志 error（含具体原因与版本号）；`已加载 N 个计时单元` 正常；**不启动任何 autostart 计时器**；再补一条 error 指明可用 `/timer reload` |
| `config.toml` 格式错误（reload 时） | ✅ 失败并**保留旧配置与旧 name**；修正后 reload 立即恢复，autostart 正常 |
| `create` 校验 | ✅ 非法 `scheduler`（`nonsense`、`0 0 0 0 1`、`1-5 0 * * *`）、非法 `type`（`cron`）、非法 `--next="bad name"`、非法名称（`bad.name`、`with-dash`）全部报 `计时器创建失败，命令格式不符！` 且**不创建文件** |
| `create` 选项语义 | ✅ 不传则不写入；`--next=""` 写入 `next = ""`；`--autostart=false` 写入 `autostart = false`；选项乱序、无引号值（`--description=no_quotes_desc`）均正常 |
| 长 `next` 循环 | ✅ 文档说明支持 A→B→A；实测 A→B 链正常（自指与多跳未逐一实测，逻辑同路径） |
| 名称大小写敏感 | ✅ `Aaa_Auto` 与 `aaa_auto` 被视为不同（`timer status Aaa_Auto` → 计时器不存在） |
| 关服 | ✅ fox 线程不阻塞关服，2.1 秒干净退出，日志 `TimelessTimer 已停止所有计时器。` |

## 5 需要你注意的问题

### 5.1 Windows 控制台中文乱码（真实存在，已定位）

服务端用 `run.bat` 在 Windows 控制台（代码页 GBK）启动时，模组的中文日志在**控制台**显示为乱码，
但写入 `logs/latest.log` 的内容是完好的 UTF-8。

- 原因：JVM 控制台输出编码为 GBK，而模组用 UTF-8 写出。
- 影响：仅影响控制台观感，不影响游戏内聊天栏（Minecraft 的文本走 JSON 协议，无编码问题）与日志文件。
- 缓解：启动参数加 `-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8`，
  该问题即消失（与模组无关，任何中文日志的模组都一样）。

### 5.2 参数解析的两个边界（不影响安全）

- `timer create bad/name ...`：Brigadier 的未加引号参数只接受 `[a-zA-Z0-9_]`，因此在到达模组之前
  就报 `Expected whitespace to end one argument` 而未执行——**同样不会创建文件**，只是错误文案来自原版解析器。
- 加引号（`"bad.name"`）时才进入模组校验并得到规范的 `计时器创建失败，命令格式不符！`。

### 5.3 一个设计取舍：`schedule` 触发一次后即结束

实测 `schedule` 到点触发后 `status` 变为"未在运行"。规范把"自然终止"列为计时器停止的三种方式之一，
据此实现为"触发一次即自然结束"；如需**每日重复**，需要重新 `/timer start`（或让服务器重启由 `autostart` 拉起）。
如果你期望 schedule 触发后继续等下一个时间点，这处需要改（改动很小：触发后不结束 handle）。

## 6 未能实测的 3 项

| 项 | 原因 | 替代验证 |
|---|---|---|
| 玩家权限路径（OP≥3 / LuckPerms 节点） | 需要图形客户端或正版账号登录，本环境无客户端 | 代码路径简单；**控制台与命令方块已实测放行**（RCON 属非玩家源）。LuckPerms 走反射，未装插件时不会加载相关类 |
| 管理员登录时的聊天栏坏文件提示 | 同上，无玩家登录 | 坏文件本身的检测与 warning 已实测；提示逻辑在登录事件里，按 `timeless_timer.list` 同一套权限判定 |
| fox 卡顿时的 `exec` 暂缓 | 需要主线程停顿 >1 秒。实测 fill（1.6 万方块发光更新）、forceload（100 区块）、召唤 3000 实体均**在 0.05 秒内返回**，无法用常规游戏指令可靠制造 >1 秒停顿 | 判定逻辑为"两次主线程 tick 间隔的墙钟时间 >1000 ms → 推迟 1 秒重试"，代码路径已逐行核对。要实测可在服务器卡顿时（如 `save-all flush` 大世界/机械动力类机器满载）观察 warning |

## 7 如何复现这套测试

```powershell
# 1) 服务端（已解压在工作区）：
#    模组已在 forge-server\mods\ 内；rcon.password=timer-test, rcon.port=25585
cd <工作区>\forge-server
.\run.bat                      # 或直接跑 jre-17.0.20.1\bin\java.exe @libraries\...\win_args.txt --nogui

# 2) 驱动指令（PowerShell RCON 客户端，工作区内已备好）：
<工作区>\.harness\tools\rcon.ps1 -Commands 'timer list','timer create t countdown "say hi" "5 0 0 0 *"','timer start t'
```

> RCON 客户端用 PowerShell 的 `TcpClient` 实现：本环境里被启动的子进程（如 `java.exe`）不允许联网，
> 而 PowerShell 自身可以，因此用脚本而非 Java 客户端。
