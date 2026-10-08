package com.hsq08.timelesstimer;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.lang.reflect.Method;

/**
 * Permission checks for the {@code /timer} command tree.
 *
 * <p>Rules, in order:</p>
 * <ol>
 *   <li>the console and command blocks are always allowed - the specification explicitly says the
 *       commands may be run from a command block;</li>
 *   <li>a player with permission level 3 (vanilla operator level 3) is always allowed,
 *       independently of whether LuckPerms is installed;</li>
 *   <li>otherwise the {@code timeless_timer.*} node is consulted through LuckPerms when it is
 *       loaded. LuckPerms is reached by reflection, so the mod has no hard dependency on it.</li>
 * </ol>
 */
final class Permissions {

    static final String NODE_LIST = "timeless_timer.list";
    static final String NODE_CREATE = "timeless_timer.create";
    static final String NODE_REMOVE = "timeless_timer.remove";
    static final String NODE_START = "timeless_timer.start";
    static final String NODE_STOP = "timeless_timer.stop";
    static final String NODE_STATUS = "timeless_timer.status";
    static final String NODE_RELOAD = "timeless_timer.reload";

    /** Vanilla operator level required when no permission plugin grants the node. */
    static final int REQUIRED_OP_LEVEL = 3;

    private static Boolean luckPermsPresent;
    private static Method luckPermsGetPlayer;
    private static Method permissionDataGet;
    private static Method cachedDataSet;
    private static Method cachedDataGetPermissionData;
    private static Method permissionDataCheckPermission;
    private static Method permissionDataCheckPermissionNoCache;
    private static Method queryOptionsContext;
    private static boolean reflectionBroken;

    private Permissions() {
    }

    static boolean allowed(CommandSourceStack source, String node) {
        if (source == null) {
            return false;
        }
        // Console, RCON and command blocks have no player behind them.
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return true;
        }
        if (source.hasPermission(REQUIRED_OP_LEVEL)) {
            return true;
        }
        return hasNode(player, node);
    }

    /** @return whether LuckPerms reports {@code node} for this player */
    static boolean hasNode(ServerPlayer player, String node) {
        if (!ensureLuckPerms()) {
            return false;
        }
        try {
            Object user = luckPermsGetPlayer.invoke(null, player.getUUID());
            if (user == null) {
                return false;
            }
            Object cachedData = permissionDataGet.invoke(user);
            if (cachedData == null) {
                return false;
            }
            Object permissionData = cachedDataGetPermissionData.invoke(cachedData);
            if (permissionData == null) {
                return false;
            }
            Object queryOptions = queryOptionsContext.invoke(null);
            Object result = permissionDataCheckPermission.invoke(permissionData, node, queryOptions);
            if (result instanceof Boolean value) {
                return value;
            }
            // Older API shape: checkPermission(permission) without query options.
            result = permissionDataCheckPermissionNoCache.invoke(permissionData, node);
            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Never let a permission plugin break the timer commands: fall back to "no node".
            reflectionBroken = true;
            TimelessTimer.LOGGER.warn("查询 LuckPerms 权限节点 {} 失败，将按无该权限处理：{}", node, e.toString());
            return false;
        }
    }

    private static boolean ensureLuckPerms() {
        if (reflectionBroken || !TimelessTimer.isLuckPermsLoaded()) {
            return false;
        }
        if (luckPermsPresent != null) {
            return luckPermsPresent;
        }
        synchronized (Permissions.class) {
            if (luckPermsPresent != null) {
                return luckPermsPresent;
            }
            try {
                Class<?> provider = Class.forName("net.luckperms.api.LuckPermsProvider");
                luckPermsGetPlayer = provider.getMethod("getPlayer", java.util.UUID.class);
                // Loading the API class proves the plugin really ships the API we expect.
                Class.forName("net.luckperms.api.LuckPerms");
                Class<?> userClass = Class.forName("net.luckperms.api.model.user.User");
                permissionDataGet = userClass.getMethod("getCachedData");
                Class<?> cachedDataClass = Class.forName("net.luckperms.api.cacheddata.CachedDataManager");
                cachedDataGetPermissionData = cachedDataClass.getMethod("getPermissionData");
                Class<?> permissionDataClass = Class.forName("net.luckperms.api.cacheddata.CachedPermissionData");
                Class<?> queryOptionsClass = Class.forName("net.luckperms.api.query.QueryOptions");
                queryOptionsContext = queryOptionsClass.getMethod("nonContextual");
                permissionDataCheckPermission = permissionDataClass.getMethod("checkPermission", String.class,
                        queryOptionsClass);
                permissionDataCheckPermissionNoCache = permissionDataClass.getMethod("checkPermission", String.class);
                // Touch the remaining API class so a broken installation is detected here rather than later.
                Class.forName("net.luckperms.api.context.ImmutableContextSet");
                luckPermsPresent = Boolean.TRUE;
                TimelessTimer.LOGGER.info("已检测到 LuckPerms，将使用 timeless_timer.* 权限节点。");
            } catch (ReflectiveOperationException | RuntimeException e) {
                reflectionBroken = true;
                luckPermsPresent = Boolean.FALSE;
                TimelessTimer.LOGGER.warn("检测到 LuckPerms 但无法使用其 API，权限回退为原版 OP 系统：{}", e.toString());
            }
            return luckPermsPresent;
        }
    }

    /** @return the player's effective vanilla permission level, for diagnostics */
    static int vanillaLevel(ServerPlayer player) {
        var server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return 0;
        }
        return server.getProfilePermissions(player.getGameProfile());
    }
}
