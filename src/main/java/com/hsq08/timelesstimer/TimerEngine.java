package com.hsq08.timelesstimer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs every timer.
 *
 * <p>Two timing schemes are implemented:</p>
 * <ul>
 *   <li>{@code vanilla} - one tick of the server counts as one twentieth of a second, so the timer
 *       follows the server's tick rate;</li>
 *   <li>{@code fox} - an independent daemon thread does the waiting, so an unstable TPS cannot
 *       stretch a countdown. The thread starts only once the server has finished starting, stops
 *       with the server, and defers a command while the server main thread is more than a second
 *       behind, until the server responds again.</li>
 * </ul>
 *
 * <p>All state is only ever touched from the server thread; the fox thread merely posts work back
 * onto it, which keeps the two schemes consistent.</p>
 */
final class TimerEngine {

    static final int TICKS_PER_SECOND = 20;

    private final TimelessTimer mod;
    private final Map<String, TimerHandle> running = new LinkedHashMap<>();
    private final FoxRunner fox;
    /** Monotonic counter of server ticks, used by the fox thread to notice a stalled server. */
    private final AtomicLong serverTick = new AtomicLong();
    private long tickCounter;

    TimerEngine(TimelessTimer mod) {
        this.mod = mod;
        this.fox = new FoxRunner(this);
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    boolean isRunning(String name) {
        return running.containsKey(name);
    }

    int runningCount() {
        return running.size();
    }

    /** Stops every timer and shuts the fox thread down. */
    void stopAll() {
        List<TimerHandle> handles = new ArrayList<>(running.values());
        running.clear();
        for (TimerHandle handle : handles) {
            handle.running = false;
            handle.spec = null;
            handle.cancelFoxTask();
        }
        fox.shutdown();
    }

    void onServerTick(MinecraftServer server) {
        serverTick.incrementAndGet();
        tickCounter++;
        if (running.isEmpty()) {
            return;
        }
        // Copy: a timer may stop itself, chain to another timer, or be stopped by an exec command.
        for (TimerHandle handle : new ArrayList<>(running.values())) {
            if (!handle.running || handle.spec == null) {
                continue;
            }
            if (handle.spec.isCountdown()) {
                tickCountdown(server, handle);
            } else {
                tickSchedule(server, handle);
            }
        }
    }

    private void tickCountdown(MinecraftServer server, TimerHandle handle) {
        if (handle.ticksRemaining > 0) {
            handle.ticksRemaining--;
        }
        if (handle.ticksRemaining <= 0) {
            executeAndMaybeChain(server, handle);
        }
    }

    private void tickSchedule(MinecraftServer server, TimerHandle handle) {
        java.time.LocalDateTime now = java.time.LocalDateTime.now();
        if (!handle.spec.timing().matches(now)) {
            return;
        }
        String minute = now.toLocalDate() + "T" + String.format("%02d:%02d", now.getHour(), now.getMinute());
        if (minute.equals(handle.lastScheduleMinute)) {
            return;
        }
        handle.lastScheduleMinute = minute;
        executeAndMaybeChain(server, handle);
    }

    // ------------------------------------------------------------------
    // Start / stop
    // ------------------------------------------------------------------

    enum StartResult {
        STARTED,
        NOT_FOUND,
        ALREADY_RUNNING,
        MALFORMED
    }

    /** Starts a timer by name; the caller turns the result into the documented chat feedback. */
    StartResult start(String name, MinecraftServer server) {
        UnitEntry entry = mod.findUnit(name);
        if (entry == null) {
            return StartResult.NOT_FOUND;
        }
        if (entry.spec() == null) {
            return StartResult.MALFORMED;
        }
        if (running.containsKey(name)) {
            return StartResult.ALREADY_RUNNING;
        }

        UnitSpec spec = entry.spec();
        TimerHandle handle = new TimerHandle(name, spec);
        handle.running = true;
        if (spec.isCountdown()) {
            handle.ticksRemaining = spec.timing().durationSeconds() * TICKS_PER_SECOND;
            if (mod.config().isFox()) {
                fox.scheduleCountdown(handle);
            }
        } else {
            // A schedule timer that is started exactly on a matching minute fires immediately;
            // tickSchedule() handles that case on the very next tick, so nothing to set up here.
            handle.lastScheduleMinute = null;
        }
        running.put(name, handle);
        TimelessTimer.LOGGER.info("计时器 '{}' 已开启（{} 模式，{}）。", name, mod.config().timer(),
                spec.isCountdown() ? "倒计时 " + spec.scheduler() : "定时 " + spec.scheduler());
        return StartResult.STARTED;
    }

    /**
     * Starts a timer that a finished timer points at through {@code next}.
     *
     * <p>Mirrors {@code /timer start <name>} but never talks to chat: a missing target writes a
     * warning to {@code logs/latest.log} and an already running target is skipped silently.</p>
     */
    boolean startFromChain(String name, MinecraftServer server) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        StartResult result = start(name, server);
        switch (result) {
            case STARTED -> {
                TimelessTimer.LOGGER.info("计时器 chain：已按 next 启动 '{}'。", name);
                return true;
            }
            case ALREADY_RUNNING -> TimelessTimer.LOGGER.warn(
                    "计时器 chain：下一个计时器 '{}' 已经开启，跳过本次 next。", name);
            case MALFORMED -> TimelessTimer.LOGGER.warn(
                    "计时器 chain：下一个计时器 '{}' 格式错误，跳过本次 next。", name);
            case NOT_FOUND -> TimelessTimer.LOGGER.warn(
                    "计时器 chain：下一个计时器 '{}' 不存在，跳过本次 next。", name);
        }
        return false;
    }

    /** @return {@code true} when the timer was running and has now been stopped */
    boolean stop(String name) {
        TimerHandle handle = running.remove(name);
        if (handle == null) {
            return false;
        }
        handle.running = false;
        handle.spec = null;
        handle.cancelFoxTask();
        return true;
    }

    // ------------------------------------------------------------------
    // Firing
    // ------------------------------------------------------------------

    /** Called by the fox thread once its countdown reached zero and the server is responsive. */
    void onFoxCountdownReached(TimerHandle handle, MinecraftServer server) {
        if (handle.running) {
            executeAndMaybeChain(server, handle);
        }
    }

    private void executeAndMaybeChain(MinecraftServer server, TimerHandle handle) {
        UnitSpec spec = handle.spec;
        if (!handle.running || spec == null) {
            return;
        }
        dispatch(server, spec.exec());

        // The command may have stopped or reloaded this very timer.
        if (!handle.running) {
            running.remove(handle.name, handle);
            return;
        }

        handle.cancelFoxTask();
        handle.running = false;
        handle.spec = null;
        running.remove(handle.name, handle);
        TimelessTimer.LOGGER.info("计时器 '{}' 已执行完毕并结束。", handle.name);

        if (!spec.next().isEmpty()) {
            startFromChain(spec.next(), server);
        }
    }

    private void dispatch(MinecraftServer server, String command) {
        // The unit file may or may not start the command with a slash; only one leading command sign
        // is added back, because the specified exec value is a plain command string.
        String plain = command.startsWith("/") ? command.substring(1) : command;
        try {
            CommandSourceStack source = server.createCommandSourceStack();
            // The specification says command feasibility is not checked, so the command is always
            // dispatched. A command that cannot even be parsed is only reported in logs/latest.log.
            if (server.getCommands().performPrefixedCommand(source, plain) == 0) {
                TimelessTimer.LOGGER.warn("计时器 exec 指令未能执行（指令不存在或参数有误）：{}", plain);
            }
        } catch (RuntimeException e) {
            TimelessTimer.LOGGER.error("计时器 exec 指令执行时发生异常（指令：{}）", plain, e);
        }
    }

    AtomicLong serverTick() {
        return serverTick;
    }

    // ------------------------------------------------------------------
    // The fox scheme: an independent daemon thread
    // ------------------------------------------------------------------

    /** Waits off-thread for one timer, then posts the firing back onto the server thread. */
    private static final class FoxRunner {

        private final TimerEngine engine;
        private java.util.concurrent.ScheduledExecutorService executor;
        private final Object lock = new Object();

        FoxRunner(TimerEngine engine) {
            this.engine = engine;
        }

        private java.util.concurrent.ScheduledExecutorService executor() {
            synchronized (lock) {
                if (executor == null || executor.isShutdown()) {
                    executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "TimelessTimer-Fox");
                        thread.setDaemon(true);
                        return thread;
                    });
                }
                return executor;
            }
        }

        void shutdown() {
            synchronized (lock) {
                if (executor != null) {
                    executor.shutdownNow();
                    executor = null;
                }
            }
        }

        void scheduleCountdown(TimerHandle handle) {
            long target = System.currentTimeMillis() + handle.spec.timing().durationSeconds() * 1000L;
            handle.fireAtMillis = target;
            schedule(handle, System.currentTimeMillis() + 50L);
        }

        private void schedule(TimerHandle handle, long delayMillis) {
            if (!handle.running) {
                return;
            }
            try {
                var task = executor().schedule(() -> tickCountdown(handle),
                        Math.max(0L, delayMillis), java.util.concurrent.TimeUnit.MILLISECONDS);
                handle.replaceFoxTask(task);
            } catch (java.util.concurrent.RejectedExecutionException ignored) {
                // The server is going down; nothing left to do.
            }
        }

        private void tickCountdown(TimerHandle handle) {
            MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (!handle.running || server == null) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now < handle.fireAtMillis) {
                schedule(handle, handle.fireAtMillis - now);
                return;
            }

            // Lag detection: the server thread counts ticks, so a gap between two of its ticks
            // means it is busy decompiling chunks, saving, or otherwise stalled. Defer until it
            // responds again instead of piling commands onto a frozen server.
            long observed = engine.serverTick().get();
            if (engine.lastObservedTick > 0 && observed > engine.lastObservedTick) {
                engine.lastObservedTick = observed;
                engine.lastTickWallClock = now;
            } else if (engine.lastTickWallClock > 0 && now - engine.lastTickWallClock > 1000L) {
                TimelessTimer.LOGGER.warn("检测到服务器卡顿，计时器 '{}' 的 exec 暂缓执行。", handle.name);
                schedule(handle, 1000L);
                return;
            }

            try {
                server.execute(() -> engine.onFoxCountdownReached(handle, server));
            } catch (RuntimeException e) {
                TimelessTimer.LOGGER.error("无法把计时器 '{}' 的 exec 投递到服务器线程。", handle.name, e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Per-timer state
    // ------------------------------------------------------------------

    static final class TimerHandle {

        final String name;
        volatile UnitSpec spec;
        volatile boolean running;
        /** vanilla scheme: ticks left before the countdown fires. */
        volatile long ticksRemaining;
        /** fox scheme: absolute wall clock time at which the countdown fires. */
        volatile long fireAtMillis;
        /** schedule scheme: the last minute that already fired, as {@code yyyy-MM-ddTHH:mm}. */
        volatile String lastScheduleMinute;
        private volatile java.util.concurrent.ScheduledFuture<?> foxTask;

        TimerHandle(String name, UnitSpec spec) {
            this.name = name;
            this.spec = spec;
        }

        synchronized void replaceFoxTask(java.util.concurrent.ScheduledFuture<?> task) {
            java.util.concurrent.ScheduledFuture<?> previous = this.foxTask;
            this.foxTask = task;
            if (previous != null && previous != task) {
                previous.cancel(false);
            }
        }

        synchronized void cancelFoxTask() {
            if (foxTask != null) {
                foxTask.cancel(false);
                foxTask = null;
            }
        }
    }

    /** The server thread's last known tick counter and the wall clock time it was seen at. */
    private volatile long lastObservedTick;
    private volatile long lastTickWallClock;
}