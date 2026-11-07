package com.pvpcore;

import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Kill / death tracking and K/D ranking.
 *
 * <p>K/D is kills divided by deaths; a player with no deaths yet has a K/D equal to their kills.
 * Players are ranked by K/D, then by more kills, then by fewer deaths. Players with no kills and no
 * deaths are unranked. Totals live in memory and are written to plugins/PvPCore/stats.yml on a
 * timer and on shutdown. All methods must be called from the main thread.
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
    private final File file;
    private final Object fileLock = new Object();
    private final Map<UUID, PlayerStats> stats = new HashMap<>();

    private List<UUID> ranking = List.of();
    private Map<UUID, Integer> rankIndex = Map.of();
    private boolean rankingDirty = true;

    private volatile boolean saveNeeded;
    private long saveVersion;
    private long lastWrittenVersion; // guarded by fileLock
    private BukkitTask saveTask;

    public StatsManager(PvPCore plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "stats.yml");
    }

    // ------------------------------------------------------------------
    // Loading, saving
    // ------------------------------------------------------------------

    /** Reads stats.yml. Called once on startup. */
    void load() {
        stats.clear();
        rankingDirty = true;
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file);
        } catch (IOException | InvalidConfigurationException ex) {
            plugin.getLogger().log(Level.SEVERE,
                    "Could not read stats.yml. A copy is kept next to it and stats start empty.", ex);
            keepBrokenCopy();
            return;
        }
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Skipping stats entry with a bad UUID: " + key);
                continue;
            }
            ConfigurationSection section = players.getConfigurationSection(key);
            if (section == null) {
                continue;
            }
            stats.put(id, new PlayerStats(
                    section.getString("name", "Unknown"),
                    Math.max(0, section.getInt("kills")),
                    Math.max(0, section.getInt("deaths"))));
        }
    }

    private void keepBrokenCopy() {
        try {
            Path broken = file.toPath().resolveSibling("stats.broken-" + System.currentTimeMillis() + ".yml");
            Files.copy(file.toPath(), broken);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not copy the unreadable stats.yml", ex);
        }
    }

    /** (Re)starts the periodic save from config. Does not reload the file. */
    void reload() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        long seconds = Math.max(10L, plugin.getConfig().getLong("stats.save-interval-seconds", 300L));
        long ticks = seconds * 20L;
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveAsyncIfNeeded, ticks, ticks);
    }

    /** Writes everything immediately. Called when the plugin is disabled. */
    void shutdown() {
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (saveNeeded) {
            saveNeeded = false;
            write(serialise(), ++saveVersion);
        }
    }

    private void saveAsyncIfNeeded() {
        if (!saveNeeded) {
            return;
        }
        saveNeeded = false;
        long version = ++saveVersion;
        String data = serialise(); // built here on the main thread, written on another one
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> write(data, version));
    }

    private String serialise() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
            PlayerStats s = entry.getValue();
            if (!s.hasPlayed()) {
                continue;
            }
            String base = "players." + entry.getKey();
            yaml.set(base + ".name", s.name);
            yaml.set(base + ".kills", s.kills);
            yaml.set(base + ".deaths", s.deaths);
        }
        return yaml.saveToString();
    }

    private void write(String data, long version) {
        synchronized (fileLock) {
            if (version < lastWrittenVersion) {
                return; // a newer snapshot has already been written
            }
            try {
                Files.createDirectories(file.getParentFile().toPath());
                Path temp = file.toPath().resolveSibling(file.getName() + ".tmp");
                Files.writeString(temp, data, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file.toPath(),
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(temp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                lastWrittenVersion = version;
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Could not save stats.yml, will retry", ex);
                saveNeeded = true;
            }
        }
    }

    // ------------------------------------------------------------------
    // Counting
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        touch(victim).deaths++;
        if (killer != null && !killer.equals(victim)) {
            touch(killer).kills++;
        }
        markChanged();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        PlayerStats existing = stats.get(event.getPlayer().getUniqueId());
        if (existing != null && !existing.name.equals(event.getPlayer().getName())) {
            existing.name = event.getPlayer().getName();
            markChanged();
        }
    }

    private PlayerStats touch(Player player) {
        PlayerStats s = stats.computeIfAbsent(player.getUniqueId(), id -> new PlayerStats(player.getName(), 0, 0));
        s.name = player.getName();
        return s;
    }

    private void markChanged() {
        rankingDirty = true;
        saveNeeded = true;
    }

    // ------------------------------------------------------------------
    // Reading
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
    // Admin
    // ------------------------------------------------------------------

    public UUID findByName(String name) {
        for (Map.Entry<UUID, PlayerStats> entry : stats.entrySet()) {
            if (entry.getValue().name.equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public boolean reset(UUID id) {
        if (stats.remove(id) == null) {
            return false;
        }
        markChanged();
        return true;
    }

    /** @return how many players were wiped */
    public int resetAll() {
        int count = stats.size();
        stats.clear();
        markChanged();
        return count;
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
