package com.pvpcore;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.UUID;

/**
 * Action bar that shows only the player's K/D and their rank, for example {@code K/D: 4.1  #4th}.
 */
public final class KdActionBar {

    private static final String DEFAULT_RANKED =
            "<gray>K/D:</gray> <white><kd></white>  <gold>#<ordinal></gold>";
    private static final String DEFAULT_UNRANKED =
            "<gray>K/D:</gray> <white><kd></white>  <dark_gray>unranked</dark_gray>";

    private final PvPCore plugin;
    private final StatsManager stats;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private BukkitTask task;
    private String rankedFormat = DEFAULT_RANKED;
    private String unrankedFormat = DEFAULT_UNRANKED;
    private int decimals = 1;

    public KdActionBar(PvPCore plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    void reload() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        var config = plugin.getConfig();
        rankedFormat = config.getString("actionbar.format", DEFAULT_RANKED);
        unrankedFormat = config.getString("actionbar.format-unranked", DEFAULT_UNRANKED);
        decimals = Math.max(0, Math.min(4, config.getInt("actionbar.kd-decimals", 1)));

        if (config.getBoolean("actionbar.enabled", true)) {
            int interval = Math.max(1, config.getInt("actionbar.update-interval-ticks", 10));
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, interval, interval);
        }
    }

    private void tick() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(build(player));
        }
    }

    private Component build(Player player) {
        UUID id = player.getUniqueId();
        StatsManager.PlayerStats s = stats.get(id);
        int kills = s == null ? 0 : s.kills();
        int deaths = s == null ? 0 : s.deaths();
        double kd = s == null ? 0.0 : s.kd();
        int rank = stats.rankOf(id);

        String format = rank > 0 ? rankedFormat : unrankedFormat;
        return miniMessage.deserialize(format,
                Placeholder.unparsed("kd", String.format(Locale.ROOT, "%." + decimals + "f", kd)),
                Placeholder.unparsed("rank", rank > 0 ? String.valueOf(rank) : "-"),
                Placeholder.unparsed("ordinal", rank > 0 ? StatsManager.ordinal(rank) : "-"),
                Placeholder.unparsed("kills", String.valueOf(kills)),
                Placeholder.unparsed("deaths", String.valueOf(deaths)));
    }
}
