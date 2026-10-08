package com.hsq08.timelesstimer;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * The {@code /timer} command tree.
 *
 * <pre>
 * /timer create &lt;name&gt; &lt;type&gt; "&lt;exec_command&gt;" "&lt;scheduler&gt;" [--description=""] [--autostart=] [--next=""]
 * /timer remove &lt;name&gt;
 * /timer list
 * /timer start &lt;name&gt;
 * /timer stop &lt;name&gt;
 * /timer status &lt;name&gt;
 * /timer reload
 * </pre>
 */
final class TimerCommands {

    private final TimelessTimer mod;

    TimerCommands(TimelessTimer mod) {
        this.mod = mod;
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        register(event.getDispatcher(), mod);
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher, TimelessTimer mod) {
        dispatcher.register(Commands.literal("timer")
                .then(Commands.literal("create")
                        .requires(source -> Permissions.allowed(source, Permissions.NODE_CREATE))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .then(Commands.argument("type", StringArgumentType.string())
                                        .then(Commands.argument("exec_command", StringArgumentType.string())
                                                .then(Commands.argument("scheduler", StringArgumentType.string())
                                                        .executes(context -> create(context, mod, ""))
                                                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                                                .executes(context -> create(context, mod,
                                                                        StringArgumentType.getString(context, "options")))))))))
                .then(Commands.literal("remove")
                        .requires(source -> Permissions.allowed(source, Permissions.NODE_REMOVE))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> remove(context, mod))))
                .then(Commands.literal("list")
                        .requires(source -> Permissions.allowed(source, Permissions.NODE_LIST))
                        .executes(context -> list(context, mod)))
                .then(Commands.literal("start")
                        .requires(source -> Permissions.allowed(source, Permissions.NODE_START))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> start(context, mod))))
                .then(Commands.literal("stop")
                        .requires(source -> Permissions.allowed(source, Permissions.NODE_STOP))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> stop(context, mod))))
                .then(Commands.literal("status")
                        .requires(source -> Permissions.allowed(source, Permissions.NODE_STATUS))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> status(context, mod))))
                .then(Commands.literal("reload")
                        .requires(source -> Permissions.allowed(source, Permissions.NODE_RELOAD))
                        .executes(context -> reload(context, mod))));
    }

    // ------------------------------------------------------------------
    // create
    // ------------------------------------------------------------------

    private static int create(CommandContext<CommandSourceStack> context, TimelessTimer mod, String optionText) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        String rawType = StringArgumentType.getString(context, "type");
        String exec = StringArgumentType.getString(context, "exec_command");
        String scheduler = StringArgumentType.getString(context, "scheduler");

        // 1. name rules, including path traversal.
        if (!UnitFiles.isValidName(name)) {
            mod.messages().sendError(source, "计时器创建失败，命令格式不符！");
            return 0;
        }

        // 2. type and scheduler format, before anything is written to disk.
        String type;
        try {
            type = TimerSpec.normaliseType(rawType);
            TimerSpec.parse(type, scheduler);
        } catch (ParseException e) {
            mod.messages().sendError(source, "计时器创建失败，命令格式不符！");
            return 0;
        }
        if (exec.isBlank()) {
            mod.messages().sendError(source, "计时器创建失败，命令格式不符！");
            return 0;
        }

        // 3. optional trailing options.
        Options options = new Options(mod, source);
        if (!options.parse(optionText)) {
            return 0;
        }

        try {
            Path file = UnitFiles.resolveUnitFile(mod.gameDirectory(), name);
            if (Files.exists(file)) {
                mod.messages().sendError(source, "已有该计时器！");
                return 0;
            }
            UnitFiles.UnitData data = new UnitFiles.UnitData(options.description, type, exec, options.autostart,
                    options.next, scheduler);
            Files.createDirectories(file.getParent());
            Files.writeString(file, UnitFiles.serialize(data), StandardCharsets.UTF_8);
            // No reload needed: the unit becomes usable immediately.
            mod.scanUnits(false);
            mod.messages().send(source, "计时器'" + name + "'已被创建！");
            TimelessTimer.LOGGER.info("已创建计时单元 {}。", file);
            return 1;
        } catch (IOException e) {
            mod.messages().sendError(source, "计时器创建失败，命令格式不符！");
            TimelessTimer.LOGGER.error("创建计时单元 '{}' 失败。", name, e);
            return 0;
        }
    }

    /** The {@code --description=}, {@code --autostart=} and {@code --next=} tail of {@code create}. */
    private static final class Options {

        private final TimelessTimer mod;
        private final CommandSourceStack source;

        String description;
        Boolean autostart;
        String next;

        Options(TimelessTimer mod, CommandSourceStack source) {
            this.mod = mod;
            this.source = source;
        }

        /** @return whether the text consisted of valid options only */
        boolean parse(String text) {
            List<String> tokens = Tokenizer.split(text);
            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                if (!token.startsWith("--")) {
                    return fail();
                }
                int equals = token.indexOf('=');
                String key = (equals < 0 ? token : token.substring(0, equals)).toLowerCase(Locale.ROOT);
                if (!"--description".equals(key) && !"--autostart".equals(key) && !"--next".equals(key)) {
                    return fail();
                }
                String value;
                if (equals >= 0) {
                    value = collect(tokens, i, token.substring(equals + 1));
                    i = consumedIndex;
                } else {
                    value = "";
                }
                if (!accept(key, value)) {
                    return false;
                }
            }
            return true;
        }

        private int consumedIndex;

        /**
         * Join the inline value with the following tokens up to the next {@code --} flag, so that an
         * unquoted multi word description still works; quoted values are already a single token.
         */
        private String collect(List<String> tokens, int index, String inline) {
            StringBuilder value = new StringBuilder(inline);
            int cursor = index + 1;
            while (cursor < tokens.size() && !tokens.get(cursor).startsWith("--")) {
                if (value.length() > 0) {
                    value.append(' ');
                }
                value.append(tokens.get(cursor));
                cursor++;
            }
            consumedIndex = cursor - 1;
            return value.toString();
        }

        private boolean accept(String key, String value) {
            switch (key) {
                case "--description" -> description = stripQuotes(value);
                case "--next" -> {
                    String target = stripQuotes(value);
                    if (!target.isEmpty() && !UnitFiles.isValidName(target)) {
                        return fail();
                    }
                    next = target;
                }
                case "--autostart" -> {
                    String flag = stripQuotes(value).trim().toLowerCase(Locale.ROOT);
                    if (flag.isEmpty()) {
                        // A bare --autostart means "true".
                        autostart = Boolean.TRUE;
                    } else if ("true".equals(flag)) {
                        autostart = Boolean.TRUE;
                    } else if ("false".equals(flag)) {
                        autostart = Boolean.FALSE;
                    } else {
                        return fail();
                    }
                }
                default -> throw new IllegalStateException("unreachable option " + key);
            }
            return true;
        }

        private boolean fail() {
            mod.messages().sendError(source, "计时器创建失败，命令格式不符！");
            return false;
        }

        private static String stripQuotes(String value) {
            String trimmed = value.trim();
            if (trimmed.length() >= 2) {
                char first = trimmed.charAt(0);
                char last = trimmed.charAt(trimmed.length() - 1);
                if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                    return trimmed.substring(1, trimmed.length() - 1);
                }
            }
            return trimmed;
        }
    }

    /** Splits an option string on spaces while keeping quoted runs (and their spaces) together. */
    private static final class Tokenizer {

        static List<String> split(String text) {
            List<String> tokens = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();
            char quote = 0;
            boolean escaped = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (escaped) {
                    current.append(c);
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    current.append(c);
                    continue;
                }
                if (quote != 0) {
                    current.append(c);
                    if (c == quote) {
                        quote = 0;
                    }
                    continue;
                }
                if (c == '"' || c == '\'') {
                    quote = c;
                    current.append(c);
                    continue;
                }
                if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    continue;
                }
                current.append(c);
            }
            if (current.length() > 0) {
                tokens.add(current.toString());
            }
            return tokens;
        }
    }

    // ------------------------------------------------------------------
    // remove
    // ------------------------------------------------------------------

    private static int remove(CommandContext<CommandSourceStack> context, TimelessTimer mod) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        Path file;
        try {
            file = UnitFiles.resolveUnitFile(mod.gameDirectory(), name);
        } catch (IOException e) {
            mod.messages().sendError(source, "计时器不存在！");
            return 0;
        }
        if (!Files.isRegularFile(file)) {
            mod.messages().sendError(source, "计时器不存在！");
            return 0;
        }
        if (mod.engine().isRunning(name)) {
            mod.messages().sendError(source, "计时器仍在运行，请先关闭！");
            return 0;
        }
        try {
            Files.delete(file);
            // No reload needed: the timer disappears from the list right away.
            mod.scanUnits(false);
            mod.messages().send(source, "计时器'" + name + "'已被删除！");
            TimelessTimer.LOGGER.info("已删除计时单元 {}。", file);
            return 1;
        } catch (IOException e) {
            TimelessTimer.LOGGER.error("删除计时单元 '{}' 失败。", name, e);
            mod.messages().sendError(source, "计时器不存在！");
            return 0;
        }
    }

    // ------------------------------------------------------------------
    // list / start / stop / status / reload
    // ------------------------------------------------------------------

    private static int list(CommandContext<CommandSourceStack> context, TimelessTimer mod) {
        CommandSourceStack source = context.getSource();
        // Header plus one line per timer, in alphabetical order. With no timers the header is all
        // that is printed, which is exactly what the specification shows.
        source.sendSuccess(() -> mod.messages().headerComponent(), false);
        for (String name : mod.names()) {
            source.sendSuccess(() -> mod.messages().plain("  " + name), false);
        }
        return mod.names().size();
    }

    private static int start(CommandContext<CommandSourceStack> context, TimelessTimer mod) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        if (mod.server() == null) {
            mod.messages().sendError(source, "计时器不存在！");
            return 0;
        }
        switch (mod.engine().start(name, mod.server())) {
            case STARTED -> {
                mod.messages().send(source, "计时器'" + name + "'已开启！");
                return 1;
            }
            case ALREADY_RUNNING -> {
                mod.messages().sendError(source, "该计时器已经开启！");
                return 0;
            }
            case MALFORMED -> {
                mod.messages().sendError(source, "计时器'" + name + "'格式错误，拒绝计时！");
                return 0;
            }
            default -> {
                mod.messages().sendError(source, "计时器不存在！");
                return 0;
            }
        }
    }

    private static int stop(CommandContext<CommandSourceStack> context, TimelessTimer mod) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        if (mod.findUnit(name) == null) {
            mod.messages().sendError(source, "计时器不存在！");
            return 0;
        }
        if (!mod.engine().stop(name)) {
            mod.messages().sendError(source, "该计时器并未开启！");
            return 0;
        }
        mod.messages().send(source, "计时器'" + name + "'已关闭！");
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> context, TimelessTimer mod) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "name");
        if (mod.findUnit(name) == null) {
            mod.messages().sendError(source, "计时器不存在！");
            return 0;
        }
        // A schedule timer waiting for its next time point counts as running.
        if (mod.engine().isRunning(name)) {
            mod.messages().send(source, "计时器'" + name + "'正在运行！");
            return 1;
        }
        mod.messages().send(source, "计时器'" + name + "'未在运行！");
        return 0;
    }

    private static int reload(CommandContext<CommandSourceStack> context, TimelessTimer mod) {
        CommandSourceStack source = context.getSource();
        boolean ok = mod.reload();
        if (!ok) {
            mod.messages().sendError(source, "重载失败，配置文件格式错误，已保留原配置。");
            return 0;
        }
        mod.messages().send(source, "重载完毕！");
        return 1;
    }
}
