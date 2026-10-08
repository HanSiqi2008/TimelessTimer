package com.hsq08.timelesstimer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The directory layout and the file formats of {@code config/timeless_timer/}.
 *
 * <pre>
 * config/timeless_timer/
 *   config.toml
 *   units/hello_countdown.toml
 * </pre>
 */
final class UnitFiles {

    /** Timer names are also file names, so they may not escape the units directory. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_]+");
    private static final String EXTENSION = ".toml";

    private UnitFiles() {
    }

    static boolean isValidName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 96 && NAME.matcher(name).matches();
    }

    static Path baseDirectory(Path gameDirectory) {
        return gameDirectory.resolve("config").resolve("timeless_timer");
    }

    static Path unitsDirectory(Path gameDirectory) {
        return baseDirectory(gameDirectory).resolve("units");
    }

    static Path configFile(Path gameDirectory) {
        return baseDirectory(gameDirectory).resolve("config.toml");
    }

    static Path unitFile(Path gameDirectory, String name) {
        return unitsDirectory(gameDirectory).resolve(name + EXTENSION);
    }

    /**
     * Resolves a unit file and proves that the result really lives inside the units directory, so
     * that a hostile name can never traverse out of it.
     */
    static Path resolveUnitFile(Path gameDirectory, String name) throws IOException {
        if (!isValidName(name)) {
            throw new IOException("非法的计时器名称：\"" + name + "\"（只允许大小写英文、数字、下划线）");
        }
        Path units = unitsDirectory(gameDirectory).toAbsolutePath().normalize();
        Path file = units.resolve(name + EXTENSION).normalize();
        if (!file.startsWith(units)) {
            throw new IOException("非法的计时器名称：\"" + name + "\"（检测到路径穿越）");
        }
        return file;
    }

    /** A timer unit file together with the fields that were actually present in it. */
    record UnitData(String description, String type, String exec, Boolean autostart, String next, String scheduler) {

        boolean hasDescription() {
            return description != null;
        }

        boolean hasAutostart() {
            return autostart != null;
        }

        boolean hasNext() {
            return next != null;
        }
    }

    /** A successfully parsed unit: its file name, its parsed specification and the raw data. */
    record LoadedUnit(String name, UnitSpec spec, UnitData data) {
    }

    /** A unit file that failed to load: kept around so that admins can be told on login. */
    record FailedUnit(String name, String message) {
    }

    record LoadResult(Map<String, LoadedUnit> units, List<FailedUnit> failures) {
    }

    /** Reads {@code config.toml}. */
    static Config loadConfig(Path configFile) throws IOException, ParseException {
        return Config.parse(Files.readString(configFile, StandardCharsets.UTF_8));
    }

    static void writeConfig(Path configFile, Config config) throws IOException {
        Files.createDirectories(configFile.getParent());
        Files.writeString(configFile, config.toFileContent(), StandardCharsets.UTF_8);
    }

    /** Creates the documented example unit, but never overwrites an existing file. */
    static void writeExampleUnit(Path gameDirectory) throws IOException {
        Path file = unitFile(gameDirectory, "hello_countdown");
        if (Files.exists(file)) {
            return;
        }
        Files.createDirectories(file.getParent());
        UnitData data = new UnitData("这是一个样例配置文件", TimerSpec.TYPE_COUNTDOWN, "say hello_world!", false, "",
                "0 8 1 6 *");
        Files.writeString(file, serialize(data), StandardCharsets.UTF_8);
    }

    /**
     * Loads every valid unit file from {@code units/}.
     *
     * <p>Files whose name does not match the naming rules are ignored, files that fail to parse are
     * collected as failures so that the caller can log them at warning level and notify an
     * administrator who logs in.</p>
     */
    static LoadResult loadUnits(Path gameDirectory) {
        Map<String, LoadedUnit> units = new LinkedHashMap<>();
        List<FailedUnit> failures = new ArrayList<>();
        Path unitsDirectory = unitsDirectory(gameDirectory);
        if (!Files.isDirectory(unitsDirectory)) {
            return new LoadResult(units, failures);
        }

        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(unitsDirectory)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            failures.add(new FailedUnit(unitsDirectory.toString(), "无法读取计时单元目录：" + e.getMessage()));
            return new LoadResult(units, failures);
        }
        files.sort(Comparator.comparing(path -> path.getFileName().toString()));

        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!fileName.toLowerCase(Locale.ROOT).endsWith(EXTENSION)) {
                continue;
            }
            String name = fileName.substring(0, fileName.length() - EXTENSION.length());
            if (!isValidName(name)) {
                // Such names are forbidden outright, so they are not timer units at all.
                continue;
            }
            try {
                UnitData data = parseUnit(Files.readString(file, StandardCharsets.UTF_8));
                units.put(name, new LoadedUnit(name, UnitSpec.from(data), data));
            } catch (ParseException e) {
                failures.add(new FailedUnit(name, e.getMessage()));
            } catch (IOException e) {
                failures.add(new FailedUnit(name, "无法读取文件：" + e.getMessage()));
            }
        }
        return new LoadResult(units, failures);
    }

    /** Parses one unit file's content into the raw field values. */
    static UnitData parseUnit(String source) throws ParseException {
        // Unit files are pure tables: nothing may appear before [unit].
        Toml.Table table = Toml.parse(source, Set.of("unit", "settings"), Set.of());

        String type = table.get("unit", "type");
        type = type == null ? TimerSpec.TYPE_COUNTDOWN : TimerSpec.normaliseType(type);

        String exec = table.get("settings", "exec");
        if (exec == null) {
            throw new ParseException("[settings] 缺少必填项 exec");
        }
        if (exec.isBlank()) {
            throw new ParseException("[settings] 的 exec 不能为空");
        }

        String scheduler = table.get("settings", "scheduler");
        if (scheduler == null) {
            throw new ParseException("[settings] 缺少必填项 scheduler");
        }
        // Validating here means a malformed unit never reaches the engine.
        TimerSpec.parse(type, scheduler);

        String description = table.get("unit", "description");
        Boolean autostart = readBoolean(table.get("settings", "autostart"), "autostart");
        String next = table.get("settings", "next");
        if (next != null && !next.isEmpty() && !isValidName(next)) {
            throw new ParseException("[settings] 的 next 必须是空字符串或合法的计时器名称，当前为 \"" + next + "\"");
        }

        return new UnitData(description, type, exec, autostart, next, scheduler);
    }

    private static Boolean readBoolean(String raw, String key) throws ParseException {
        if (raw == null) {
            return null;
        }
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        if ("false".equals(raw)) {
            return Boolean.FALSE;
        }
        throw new ParseException("[settings] 的 " + key + " 只能是 true 或 false，当前为 \"" + raw + "\"");
    }

    /**
     * Serialises a unit exactly as documented: only the fields that were supplied are written, so
     * {@code --next=""} produces {@code next = ""} while omitting {@code --next} produces no
     * {@code next} line at all.
     */
    static String serialize(UnitData data) {
        StringBuilder out = new StringBuilder();
        out.append("[unit]\n");
        if (data.hasDescription()) {
            out.append("# 描述，非必要。\n");
            out.append("description = \"").append(Toml.escape(data.description())).append("\"\n");
        }
        out.append("\n");
        out.append("# 类型：\"countdown\" 倒计时，\"schedule\" 定时任务，默认 countdown。\n");
        out.append("type = \"").append(Toml.escape(data.type())).append("\"\n");
        out.append("\n");
        out.append("[settings]\n");
        out.append("\n");
        out.append("# 计时完毕后运行的 mc 命令，以控制台身份运行。\n");
        out.append("# 如需以玩家身份运行，填写 \"execute as <player> run <command>\"。\n");
        out.append("# 不需要带斜杠。模组不检查指令可行性。\n");
        out.append("exec = \"").append(Toml.escape(data.exec())).append("\"\n");
        if (data.hasAutostart()) {
            out.append("\n");
            out.append("# 是否在服务器开启后自动运行（单人游戏等价于内置服务端加载）。\n");
            out.append("# 监听 ServerStartedEvent，按文件名字母序依次启动；加载失败则跳过并写 warning。\n");
            out.append("# countdown 默认 false，schedule 默认 true。\n");
            out.append("autostart = ").append(data.autostart()).append("\n");
        }
        if (data.hasNext()) {
            out.append("\n");
            out.append("# 计时器自然结束后即刻执行的下一个计时器。\n");
            out.append("# 等价于结束后额外运行 `/timer start <next>`，遵循该命令处理方式：\n");
            out.append("# 下一个计时器已开启或不存在则跳过，写 logs/latest.log warning，不广播聊天栏。\n");
            out.append("# 支持 A->A 自指或 A->B->A 循环链，会先结束当前计时器再开启下一个。\n");
            out.append("# 非必要，可留空为 \"\"。\n");
            out.append("next = \"").append(Toml.escape(data.next())).append("\"\n");
        }
        out.append("\n");
        out.append("# 决定何时执行命令。\n");
        out.append("# countdown 模式：表示倒计时时长，前四个字段依次为\n");
        out.append("#   秒(0~59)、分(0~59)、时(0~23)、天(0~3650)，第五字段必须为 *。\n");
        out.append("#   不能使用逗号，无其他符号。数字超限或第五字段非 * 视为文件格式错误。\n");
        out.append("# schedule 模式：表示具体时间点，语法与 Jenkins 大体一致，五字段依次为\n");
        out.append("#   分钟(0~59)、小时(0~23)、一月第几天(1~31)、月份(1~12)、\n");
        out.append("#   一周第几天(0~7，0 和 7 为周日，1~6 为周一到周六)。\n");
        out.append("#   允许逗号分隔多个时间点，不支持 H 等标识。时区为服务器本地时间。\n");
        out.append("#   服务器关闭错过时间点不补运行。\n");
        out.append("scheduler = \"").append(Toml.escape(data.scheduler())).append("\"\n");
        return out.toString();
    }

    /**
     * Reads an old-style Forge configuration file (the format used while the mod still relied on
     * {@code ForgeConfigSpec}) and returns the equivalent new {@link Config}. This is a migration
     * helper only: it is consulted when the mod's own config file does not exist yet.
     */
    static Config migrateLegacyConfig(Path gameDirectory) {
        try {
            Path legacy = gameDirectory.resolve("config").resolve(TimelessTimer.MODID + "-common.toml");
            if (!Files.isRegularFile(legacy)) {
                legacy = gameDirectory.resolve("config").resolve(TimelessTimer.MODID + ".toml");
            }
            if (!Files.isRegularFile(legacy)) {
                return null;
            }
            String name = null;
            String timer = null;
            String version = null;
            for (String line : Files.readAllLines(legacy, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                int equals = trimmed.indexOf('=');
                if (equals < 0 || trimmed.startsWith("#")) {
                    continue;
                }
                String key = trimmed.substring(0, equals).trim();
                String value = trimmed.substring(equals + 1).trim();
                if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                switch (key) {
                    case "name" -> name = value;
                    case "timer" -> timer = value;
                    case "version" -> version = value;
                    default -> {
                    }
                }
            }
            if ((name == null || name.isBlank()) && (timer == null || timer.isBlank())) {
                return null;
            }
            return new Config(version == null ? Config.SUPPORTED_VERSION : version,
                    name == null || name.isBlank() ? Config.FALLBACK.name() : name,
                    Config.SCHEME_FOX.equalsIgnoreCase(timer) ? Config.SCHEME_FOX : Config.SCHEME_VANILLA);
        } catch (IOException e) {
            return null;
        }
    }
}
