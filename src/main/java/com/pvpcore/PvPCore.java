package com.pvpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * PvPCore: wind charge resets, spear charge rules, shield stunning, and K/D stats with a ranked
 * action bar and /leaderboard dialog, for FFA/practice servers.
 */
public final class PvPCore extends JavaPlugin {

    private StatsManager stats;
    private KdActionBar kdActionBar;
    private WindChargeResetListener windChargeReset;
    private ShieldStunListener shieldStun;
    /** Null when the server has no spear API (anything older than 1.21.11). */
    private SpearListener spear;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        stats = new StatsManager(this);
        stats.load();
        getServer().getPluginManager().registerEvents(stats, this);
        kdActionBar = new KdActionBar(this, stats);

        windChargeReset = new WindChargeResetListener(this);
        shieldStun = new ShieldStunListener(this);
        getServer().getPluginManager().registerEvents(windChargeReset, this);
        getServer().getPluginManager().registerEvents(shieldStun, this);

        if (spearApiPresent()) {
            spear = new SpearListener(this);
            getServer().getPluginManager().registerEvents(spear, this);
        } else {
            getLogger().warning("This server has no spear API (needs 1.21.11 or newer). "
                    + "The spear module is disabled; everything else still works.");
        }

        PvPCoreCommand command = new PvPCoreCommand(this);
        PluginCommand pluginCommand = getCommand("pvpcore");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        PluginCommand leaderboardCommand = getCommand("leaderboard");
        if (leaderboardCommand != null) {
            leaderboardCommand.setExecutor(new LeaderboardCommand(this, stats));
        }

        applyConfig();
    }

    @Override
    public void onDisable() {
        if (stats != null) {
            stats.shutdown();
        }
    }

    /** Re-reads config.yml and pushes the values into every module. */
    public void reloadModules() {
        reloadConfig();
        applyConfig();
    }

    private void applyConfig() {
        stats.reload();
        kdActionBar.reload();
        windChargeReset.reload();
        shieldStun.reload();
        if (spear != null) {
            spear.reload();
        }
    }

    public StatsManager getStats() {
        return stats;
    }

    /**
     * Applies the configured spear rules to an item. Other plugins (for example a kit plugin)
     * can call this when they create a spear.
     *
     * @return true if the item was changed
     */
    public boolean applySpearRules(ItemStack stack) {
        return spear != null && spear.apply(stack);
    }

    public boolean isSpearModuleAvailable() {
        return spear != null;
    }

    public SpearListener getSpearModule() {
        return spear;
    }

    public boolean isDebug() {
        return getConfig().getBoolean("debug", false);
    }

    /** Debug message in chat (the action bar is reserved for K/D). Does nothing unless debug is on. */
    public void sendDebug(Player player, String message) {
        if (isDebug()) {
            player.sendMessage(Component.text(message, NamedTextColor.DARK_GRAY));
        }
    }

    private static boolean spearApiPresent() {
        try {
            Class.forName("io.papermc.paper.datacomponent.item.KineticWeapon");
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
