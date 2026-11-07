package com.pvpcore;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * K/D ranking built on Minecraft's own statistics.
 *
 * <p>Kills and deaths are {@link Statistic#PLAYER_KILLS} and {@link Statistic#DEATHS}, the numbers
 * in the in-game Statistics screen, so the server already stores them and this plugin keeps no data
 * of its own. This class only holds an in-memory copy to make ranking fast.
 *
 * <p>K/D is kills divided by deaths; a player with no deaths yet has a K/D equal to their kills.
 * Players are ranked by K/D, then by more kills, then by fewer deaths. Players with no kills and no
 * deaths are unranked.
 *
 * <p>Online players are re-read on a timer. Players who are offline are read once at startup, a few
 * per tick, so a server with many players does not stall. All methods must be called from the main
 * thread.
 */
public final class StatsManager implements Listener {

    /** One player's totals. */
    public static final class PlayerStats {
        private String name;
        private int kills;
        private int deaths;

        private PlayerStats(String name, int kills, int deaths) {
            this.name = name;
            this.kills = kills;
            this.deaths = deaths;
        }

        public String name() {
            return name;
        }

        public int kills() {
            return kills;
        }

        public int deaths() {
            return deaths;
        }

        public double kd() {
            return deaths == 0 ? kills : (double) kills / deaths;
        }

        private boolean hasPlayed() {
            return kills > 0 || deaths > 0;
        }
    }

    /** A ranked player at the moment the snapshot was taken. */
    public record Standing(int rank, UUID id, String name, int kills, int deaths, double kd) {
    }

    private static final Comparator<Map.Entry<UUID, PlayerStats>> ORDER = (a, b) -> {
        PlayerStats x = a.getValue();
        PlayerStats y = b.getValue();
        int byKd = Double.compare(y.kd(), x.kd());
        if (byKd != 0) {
            return byKd;
        }
        int byKills = Integer.compare(y.kills, x.kills);
        if (byKills != 0) {
            return byKills;
        }
        int byDeaths = Integer.compare(x.deaths, y.deaths);
        if (byDeaths != 0) {
            return byDeaths;
        }
        int byName = x.name.compareToIgnoreCase(y.name);
        if (byName != 0) {
            return byName;
        }
        return a.getKey().compareTo(b.getKey());
    };

    private final PvPCore plugin;
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    private List<UUID> ranking = List.of();
    private Map<UUID, Integer> rankIndex = Map.of();
    private boolean rankingDirty = true;

    private final ArrayDeque<OfflinePlayer> warmupQueue = new ArrayDeque<>();
    private int warmupTotal;
    private int warmupPerTick = 10;
    private BukkitTask warmupTask;
    private BukkitTask refreshTask;

    public StatsManager(PvPCore plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    /** Reads the settings and (re)starts the timer that re-reads online players. */
    void reload() {
        var config = plugin.getConfig();
        warmupPerTick = Math.max(1, config.getInt("stats.warmup-players-per-tick", 10));
        int interval = Math.max(1, config.getInt("stats.refresh-interval-ticks", 10));

        if (refreshTask != null) {
            refreshTask.cancel();
        }
        refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOnline, interval, interval);
    }

    /** Called once on startup: reads online players now and queues everyone else. */
    void start() {
        refreshOnline();

        warmupQueue.clear();
        for (OfflinePlayer player : Bukkit.getOfflinePlayers()) {
            if (player.getPlayer() == null) { // online players were just read
                warmupQueue.add(player);
            }
        }
        warmupTotal = warmupQueue.size();
        if (warmupTotal > 0) {
            warmupTask = Bukkit.getScheduler().runTaskTimer(plugin, this::warmupBatch, 1L, 1L);
        }
    }

    /** Reads the next few offline players; stops its own timer when the queue is empty. */
    void warmupBatch() {
        for (int i = 0; i < warmupPerTick && !warmupQueue.isEmpty(); i++) {
            refresh(warmupQueue.poll());
        }
        if (warmupQueue.isEmpty() && warmupTask != null) {
            warmupTask.cancel();
            warmupTask = null;
        }
    }

    /** True while offline players are still being read after a restart. */
    public boolean isLoading() {
        return !warmupQueue.isEmpty();
    }

    public int loadedPercent() {
        if (warmupTotal == 0) {
            return 100;
        }
        return (int) (100.0 * (warmupTotal - warmupQueue.size()) / warmupTotal);
    }

    // ------------------------------------------------------------------
    // Reading statistics
    // ------------------------------------------------------------------

    /** Re-reads every online player's statistics. Cheap: online statistics are held in memory. */
    public void refreshOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            refresh(player);
        }
    }

    /** Re-reads one player's kills and deaths from Minecraft's statistics. */
    public void refresh(OfflinePlayer player) {
        int kills;
        int deaths;
        try {
            kills = Math.max(0, player.getStatistic(Statistic.PLAYER_KILLS));
            deaths = Math.max(0, player.getStatistic(Statistic.DEATHS));
        } catch (RuntimeException ex) {
            return; // unreadable statistics: keep whatever we already have
        }

        UUID id = player.getUniqueId();
        PlayerStats current = stats.get(id);
        String name = player.getName();
        if (name == null) {
            name = current != null ? current.name : "Unknown";
        }

        if (current == null) {
            if (kills > 0 || deaths > 0) {
                stats.put(id, new PlayerStats(name, kills, deaths));
                rankingDirty = true;
            }
            return;
        }
        if (current.kills != kills || current.deaths != deaths || !current.name.equals(name)) {
            current.kills = kills;
            current.deaths = deaths;
            current.name = name;
            rankingDirty = true;
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        refresh(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        refresh(event.getPlayer()); // final numbers before they go offline
    }

    // ------------------------------------------------------------------
    // Ranking
    // ------------------------------------------------------------------

    /** The player's totals, or null if they have never killed or died. */
    public PlayerStats get(UUID id) {
        return stats.get(id);
    }

    /** 1-based rank by K/D, or 0 if the player is unranked. */
    public int rankOf(UUID id) {
        ensureRanking();
        Integer rank = rankIndex.get(id);
        return rank == null ? 0 : rank;
    }

    /** The player's rank, K/D and totals, or null if unranked. */
    public Standing standing(UUID id) {
        int rank = rankOf(id);
        if (rank == 0) {
            return null;
        }
        PlayerStats s = stats.get(id);
        return new Standing(rank, id, s.name, s.kills, s.deaths, s.kd());
    }

    /** The best {@code limit} players, best K/D first. */
    public List<Standing> top(int limit) {
        ensureRanking();
        int count = Math.min(limit, ranking.size());
        List<Standing> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            UUID id = ranking.get(i);
            PlayerStats s = stats.get(id);
            out.add(new Standing(i + 1, id, s.name, s.kills, s.deaths, s.kd()));
        }
        return out;
    }

    public int rankedCount() {
        ensureRanking();
        return ranking.size();
    }

    private void ensureRanking() {
        if (!rankingDirty) {
            return;
        }
        List<Map.Entry<UUID, PlayerStats>> ranked = new ArrayList<>();
        for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
            if (entry.getValue().hasPlayed()) {
                ranked.add(entry);
            }
        }
        ranked.sort(ORDER);

        List<UUID> order = new ArrayList<>(ranked.size());
        Map<UUID, Integer> index = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            UUID id = ranked.get(i).getKey();
            order.add(id);
            index.put(id, i + 1);
        }
        ranking = order;
        rankIndex = index;
        rankingDirty = false;
    }

    // ------------------------------------------------------------------
    // Formatting helpers
    // ------------------------------------------------------------------

    /** 1 -> "1st", 2 -> "2nd", 11 -> "11th", 23 -> "23rd". */
    public static String ordinal(int n) {
        int mod100 = n % 100;
        if (mod100 >= 11 && mod100 <= 13) {
            return n + "th";
        }
        return switch (n % 10) {
            case 1 -> n + "st";
            case 2 -> n + "nd";
            case 3 -> n + "rd";
            default -> n + "th";
        };
    }
}
