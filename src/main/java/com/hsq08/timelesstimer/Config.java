package com.hsq08.timelesstimer;

import java.util.Locale;
import java.util.Set;

/**
 * The parsed {@code config/timeless_timer/config.toml}.
 *
 * @param version the configuration version; a different value means the file is not compatible
 * @param name    the chat name, i.e. the {@code TimelessTimer} part of {@code [TimelessTimer]: }
 * @param timer   the timing scheme, {@code "vanilla"} (20 game ticks = 1 second, the default) or
 *                {@code "fox"} (a dedicated thread that tolerates an unstable TPS)
 */
record Config(String version, String name, String timer) {

    static final String SUPPORTED_VERSION = "1";
    static final String SCHEME_VANILLA = "vanilla";
    static final String SCHEME_FOX = "fox";

    static final Config FALLBACK = new Config(SUPPORTED_VERSION, "TimelessTimer", SCHEME_VANILLA);

    boolean isFox() {
        return SCHEME_FOX.equalsIgnoreCase(timer);
    }

    static Config parse(String source) throws ParseException {
        // version sits at the top of the file, before any table header, exactly as documented.
        Toml.Table table = Toml.parse(source, Set.of("main"), Set.of("version"));

        String version = table.get(Toml.Table.ROOT, "version");
        if (version == null) {
            throw new ParseException("缺少必填项 version");
        }
        if (!SUPPORTED_VERSION.equals(version)) {
            throw new ParseException("配置文件版本为 \"" + version + "\"，本模组只支持 version = \""
                    + SUPPORTED_VERSION + "\"（不同版本无法互通）");
        }

        String name = table.get("main", "name");
        if (name == null || name.isBlank()) {
            throw new ParseException("[main] 的 name 不能为空");
        }

        String timer = table.get("main", "timer");
        if (timer == null || timer.isBlank()) {
            timer = SCHEME_VANILLA;
        }
        if (!SCHEME_VANILLA.equalsIgnoreCase(timer) && !SCHEME_FOX.equalsIgnoreCase(timer)) {
            throw new ParseException("[main] 的 timer 只能是 \"vanilla\" 或 \"fox\"，当前为 \"" + timer + "\"");
        }

        return new Config(version, name, timer.toLowerCase(Locale.ROOT));
    }

    /**
     * Serialises this configuration using the documented file layout. The generated text is
     * byte-for-byte the sample from the specification, so anyone comparing the file with the
     * documentation sees the same thing.
     */
    String toFileContent() {
        StringBuilder out = new StringBuilder();
        out.append("# 配置文件版本，破坏性更新时数字变更，不同版本无法互通。\n");
        out.append("version = \"").append(Toml.escape(version)).append("\"\n\n");
        out.append("[main]\n\n");
        out.append("# 聊天栏中显示的名字，即 [TimelessTimer]: 中的 TimelessTimer 字段。\n");
        out.append("name = \"").append(Toml.escape(name)).append("\"\n\n");
        out.append("# 计时器方案：\"vanilla\" 或 \"fox\"。\n");
        out.append("# vanilla：原版 20 游戏刻 = 1 秒。\n");
        out.append("# fox：内建独立线程计时，避免 TPS 不稳；需等服务器启动完成才开始，\n");
        out.append("#      服务器关闭时线程关闭，卡顿时 exec 暂缓至服务器恢复响应。\n");
        out.append("# 两种方案不影响计时单元文件行为，只影响计时方法。默认 vanilla。\n");
        out.append("timer = \"").append(Toml.escape(timer)).append("\"\n");
        return out.toString();
    }
}
