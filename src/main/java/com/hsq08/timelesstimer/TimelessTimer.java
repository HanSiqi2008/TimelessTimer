package com.hsq08.timelesstimer;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TimelessTimer - a server side timer mod.
 *
 * <p>Timer units live in {@code config/timeless_timer/units/*.toml} and are executed according to
 * {@code config/timeless_timer/config.toml}. The mod does nothing on a client, which is why it
 * declares {@code IGNORE_SERVER_VERSION} and registers no client behaviour at all: a client without
 * the mod can still join a server that runs it.</p>
 */
@Mod(TimelessTimer.MODID)
public class TimelessTimer {

    public static final String MODID = "timelesstimer";
    static final Logger LOGGER = LogUtils.getLogger();

    /** Every known timer name, valid or not: {@code spec == null} marks a malformed unit file. */
    private final Map<String, UnitEntry> entries = new ConcurrentHashMap<>();
    /** Unit files that failed to load, reported to an administrator who logs in. */
    private final List<UnitFiles.FailedUnit> failures = new ArrayList<>();

    private final TimerEngine engine = new TimerEngine(this);

    private volatile Config config = Config.FALLBACK;
    private volatile Messages messages = new Messages(config.name());
    private volatile boolean active;
    private volatile Path gameDirectory;
    private volatile MinecraftServer server;

    /** Directory fingerprint of the last scan, so a login does not re-read every unit file. */
    private volatile String scanFingerprint = "";
    private volatile boolean notifyOnLogin;

    public TimelessTimer() {
        // The mod-bus instance is obtained through FMLJavaModLoadingContext for compatibility with
        // the 1.20.1 Forge template this project started from.
        @SuppressWarnings("removal")
        var modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        // Game events (server lifecycle, ticks, logins) and /timer all arrive on the Forge bus.
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(new TimerCommands(this));
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("TimelessTimer 已加载：计时器数据存放在 <游戏运行目录>/config/timeless_timer/。");
    }

    // ------------------------------------------------------------------
    // Accessors used by the engine and the command tree
    // ------------------------------------------------------------------

    Config config() {
        return config;
    }

    Messages messages() {
        return messages;
    }

    Path gameDirectory() {
        Path directory = gameDirectory;
        return directory == null ? Path.of(".") : directory;
    }

    MinecraftServer server() {
        return server;
    }

    boolean isActive() {
        return active;
    }

    UnitEntry findUnit(String name) {
        return name == null ? null : entries.get(name);
    }

    /** Timer names in alphabetical order, exactly as {@code /timer list} prints them. */
    List<String> names() {
        List<String> names = new ArrayList<>(entries.keySet());
        names.sort(Comparator.naturalOrder());
        return names;
    }

    static boolean isLuckPermsLoaded() {
        try {
            return ModList.get() != null && ModList.get().isLoaded("luckperms");
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Server lifecycle
    // ------------------------------------------------------------------

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        this.server = event.getServer();
        this.gameDirectory = resolveGameDirectory(event.getServer());
        loadConfig(false);
        scanUnits(true);
    }

    /**
     * The directory that holds {@code config/} and {@code mods/}.
     *
     * <p>{@code MinecraftServer#getServerDirectory()} returns the world folder, so the level name is
     * stripped to reach the game's running directory - which is what the specification means by
     * "游戏运行目录". The server's own path helper is used for that so that a custom level name and a
     * custom level root are both handled correctly.</p>
     */
    private static Path resolveGameDirectory(MinecraftServer server) {
        try {
            Path workspace = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .toAbsolutePath().normalize();
            Path parent = workspace.getParent();
            return parent == null ? workspace : parent;
        } catch (RuntimeException e) {
            // Fall back to the world folder's parent, then to the server directory itself.
            Path serverDirectory = server.getServerDirectory().toPath().toAbsolutePath().normalize();
            Path parent = serverDirectory.getParent();
            return parent == null ? serverDirectory : parent;
        }
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        if (!active) {
            LOGGER.error("配置无效，计时器功能不生效；修正 {} 后可用 /timer reload 重新加载。",
                    UnitFiles.configFile(gameDirectory()));
            return;
        }
        startAutostartTimers(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        engine.stopAll();
        server = null;
        LOGGER.info("TimelessTimer 已停止所有计时器。");
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || server == null) {
            return;
        }
        engine.onServerTick(server);
    }

    /**
     * The specification wants a server administrator to be told, in chat, that a timer unit file is
     * malformed. The units are therefore re-scanned on login, but only when the directory actually
     * changed, so a busy server does not re-read every file for every join.
     */
    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !Permissions.allowed(player.createCommandSourceStack(), Permissions.NODE_LIST)) {
            return;
        }
        String fingerprint = fingerprintUnitsDirectory();
        if (!fingerprint.equals(scanFingerprint)) {
            scanUnits(false);
            scanFingerprint = fingerprint;
        }
        if (notifyOnLogin && !failures.isEmpty()) {
            player.sendSystemMessage(messages.prefixed("检测到 " + failures.size() + " 个计时单元文件格式错误，已跳过："));
            for (UnitFiles.FailedUnit failure : failures) {
                player.sendSystemMessage(messages.plain("  " + failure.name() + ".toml：" + failure.message()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    private String fingerprintUnitsDirectory() {
        Path units = UnitFiles.unitsDirectory(gameDirectory());
        if (!Files.isDirectory(units)) {
            return "absent";
        }
        StringBuilder builder = new StringBuilder();
        try (var stream = Files.list(units)) {
            stream.filter(Files::isRegularFile).sorted().forEach(path -> {
                try {
                    builder.append(path.getFileName()).append(':').append(Files.size(path)).append(':')
                            .append(Files.getLastModifiedTime(path).toMillis()).append(';');
                } catch (IOException e) {
                    builder.append(path.getFileName()).append(":?;");
                }
            });
        } catch (IOException e) {
            return "error:" + e.getMessage();
        }
        return builder.toString();
    }

    /** Creates the documented directory layout on first use. */
    private void ensureFiles() {
        Path directory = gameDirectory();
        try {
            Files.createDirectories(UnitFiles.unitsDirectory(directory));
            Path configFile = UnitFiles.configFile(directory);
            if (!Files.isRegularFile(configFile)) {
                Config migrated = UnitFiles.migrateLegacyConfig(directory);
                Config initial = migrated == null ? Config.FALLBACK : migrated;
                UnitFiles.writeConfig(configFile, initial);
                LOGGER.info("已生成默认配置文件 {}。", configFile);
            }
            UnitFiles.writeExampleUnit(directory);
        } catch (IOException e) {
            LOGGER.error("无法创建 config/timeless_timer/ 目录结构。", e);
        }
    }

    /**
     * Reads {@code config.toml}.
     *
     * @param keepOldOnFailure {@code true} for {@code /timer reload}: a malformed file must keep the
     *                         previous configuration, including the chat name
     * @return whether the configuration is usable
     */
    boolean loadConfig(boolean keepOldOnFailure) {
        ensureFiles();
        Path configFile = UnitFiles.configFile(gameDirectory());
        try {
            Config loaded = UnitFiles.loadConfig(configFile);
            this.config = loaded;
            this.messages = new Messages(loaded.name());
            this.active = true;
            LOGGER.info("已加载配置文件 {}（name = \"{}\"，timer = \"{}\"）。", configFile, loaded.name(),
                    loaded.timer());
            return true;
        } catch (ParseException e) {
            // Level error, on the console and in logs/latest.log, as the specification requires.
            LOGGER.error("配置文件 {} 格式错误：{}", configFile, e.getMessage());
            if (!keepOldOnFailure) {
                this.active = false;
            }
            return false;
        } catch (IOException e) {
            LOGGER.error("无法读取配置文件 {}：{}", configFile, e.getMessage());
            if (!keepOldOnFailure) {
                this.active = false;
            }
            return false;
        }
    }

    /** Re-reads every unit file, logging each broken one at warning level. */
    void scanUnits(boolean logProblems) {
        UnitFiles.LoadResult result = UnitFiles.loadUnits(gameDirectory());
        entries.clear();
        for (UnitFiles.LoadedUnit unit : result.units().values()) {
            entries.put(unit.name(), new UnitEntry(unit.name(), unit.spec()));
        }
        for (UnitFiles.FailedUnit failure : result.failures()) {
            entries.put(failure.name(), new UnitEntry(failure.name(), null));
        }
        List<UnitFiles.FailedUnit> problemUnits = new ArrayList<>(result.failures());
        int failureCount = problemUnits.size();
        synchronized (failures) {
            failures.clear();
            failures.addAll(problemUnits);
        }
        this.notifyOnLogin = failureCount > 0;
        this.scanFingerprint = fingerprintUnitsDirectory();

        if (logProblems) {
            for (UnitFiles.FailedUnit failure : problemUnits) {
                // A malformed unit is skipped, not fatal, so this is a warning rather than an error.
                LOGGER.warn("计时单元 {} 格式错误，已跳过：{}", failure.name() + ".toml", failure.message());
            }
        }
        LOGGER.info("已加载 {} 个计时单元，{} 个格式错误。", entries.size() - failureCount, failureCount);
    }

    // ------------------------------------------------------------------
    // Timer control
    // ------------------------------------------------------------------

    TimerEngine engine() {
        return engine;
    }

    /** Starts every {@code autostart = true} timer in file name order. */
    void startAutostartTimers(MinecraftServer server) {
        for (String name : names()) {
            UnitEntry entry = entries.get(name);
            if (entry == null || entry.spec() == null || !entry.spec().autostart()) {
                continue;
            }
            TimerEngine.StartResult result = engine.start(name, server);
            if (result != TimerEngine.StartResult.STARTED) {
                // "加载失败则跳过并写 warning" - no chat broadcast during startup.
                LOGGER.warn("自动启动计时器 '{}' 失败（{}），已跳过。", name, result);
            }
        }
    }

    /** {@code /timer reload}: stop everything, re-read everything, restart the autostart timers. */
    boolean reload() {
        engine.stopAll();
        boolean configOk = loadConfig(true);
        scanUnits(true);
        if (!configOk) {
            return false;
        }
        if (server != null) {
            startAutostartTimers(server);
        }
        return true;
    }
}
