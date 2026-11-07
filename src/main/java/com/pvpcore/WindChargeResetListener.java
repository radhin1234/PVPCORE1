package com.pvpcore;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.entity.WindCharge;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wind charge reset.
 *
 * <p>When a falling player throws a wind charge while looking down, a short moment later the
 * charge is exploded in mid-air, the player's vertical motion is replaced by a configured value
 * (default 0), their horizontal motion is kept and their fall distance is cleared.
 *
 * <p>Player velocity on the server is unreliable because the client drives player movement, so
 * the speed used here is measured from the last movement packets instead.
 */
public final class WindChargeResetListener implements Listener {

    /** Movement between two movement packets, in blocks per tick. */
    private record Motion(int tick, double dx, double dy, double dz) {
    }

    private static final int MAX_SAMPLE_AGE_TICKS = 2;

    private final PvPCore plugin;
    private final Map<UUID, Motion> motion = new HashMap<>();

    private boolean enabled;
    private int delayTicks;
    private double minFallSpeed;
    private double minPitch;
    private boolean allowGliding;
    private boolean explodeCharge;
    private double ownVerticalVelocity;
    private double horizontalMultiplier;
    private boolean resetFallDistance;

    public WindChargeResetListener(PvPCore plugin) {
        this.plugin = plugin;
    }

    void reload() {
        var config = plugin.getConfig();
        String base = "wind-charge-reset.";
        enabled = config.getBoolean(base + "enabled", true);
        delayTicks = Math.max(1, config.getInt(base + "delay-ticks", 1));
        minFallSpeed = config.getDouble(base + "min-fall-speed", 0.5);
        minPitch = config.getDouble(base + "min-pitch", 45.0);
        allowGliding = config.getBoolean(base + "allow-while-gliding", false);
        explodeCharge = config.getBoolean(base + "explode-charge", true);
        ownVerticalVelocity = config.getDouble(base + "own-vertical-velocity", 0.0);
        horizontalMultiplier = config.getDouble(base + "horizontal-multiplier", 1.0);
        resetFallDistance = config.getBoolean(base + "reset-fall-distance", true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        if (dx == 0.0 && dy == 0.0 && dz == 0.0) {
            return; // camera movement only, keep the previous sample
        }
        motion.put(event.getPlayer().getUniqueId(),
                new Motion(Bukkit.getCurrentTick(), dx, dy, dz));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        motion.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!enabled) {
            return;
        }
        if (!(event.getEntity() instanceof WindCharge charge)) {
            return;
        }
        if (!(charge.getShooter() instanceof Player player)) {
            return;
        }

        String rejection = rejectionReason(player);
        if (rejection != null) {
            debug(player, "No reset: " + rejection);
            return;
        }

        Motion sample = motion.get(player.getUniqueId());
        Vector fallback = new Vector(sample.dx() * horizontalMultiplier, 0.0, sample.dz() * horizontalMultiplier);
        Bukkit.getScheduler().runTaskLater(plugin, () -> perform(player, charge, fallback), delayTicks);
        debug(player, "Reset scheduled in " + delayTicks + " tick(s)");
    }

    /** Returns why a reset must not happen, or null when every condition is met. */
    private String rejectionReason(Player player) {
        if (player.isGliding() && !allowGliding) {
            return "gliding";
        }
        if (player.isOnGround()) {
            return "on the ground";
        }
        if (player.getLocation().getPitch() < minPitch) {
            return "not looking down far enough";
        }
        Motion sample = motion.get(player.getUniqueId());
        if (sample == null || Bukkit.getCurrentTick() - sample.tick() > MAX_SAMPLE_AGE_TICKS) {
            return "no recent movement data";
        }
        double fallSpeed = -sample.dy();
        if (fallSpeed < minFallSpeed) {
            return "falling too slowly (" + String.format("%.2f", fallSpeed) + " b/t)";
        }
        return null;
    }

    private void perform(Player player, WindCharge charge, Vector fallbackHorizontal) {
        if (!player.isOnline() || player.isDead()) {
            return;
        }

        if (charge.isValid()) {
            if (explodeCharge) {
                charge.explode();
            } else {
                charge.remove();
            }
        }

        // The explosion may have pushed the player, so the velocity is set after it.
        double vx = fallbackHorizontal.getX();
        double vz = fallbackHorizontal.getZ();
        Motion latest = motion.get(player.getUniqueId());
        if (latest != null && Bukkit.getCurrentTick() - latest.tick() <= MAX_SAMPLE_AGE_TICKS + 1) {
            vx = latest.dx() * horizontalMultiplier;
            vz = latest.dz() * horizontalMultiplier;
        }
        player.setVelocity(new Vector(vx, ownVerticalVelocity, vz));

        if (resetFallDistance) {
            player.setFallDistance(0.0f);
        }
        debug(player, "Reset done");
    }

    private void debug(Player player, String message) {
        plugin.sendDebug(player, "[wind charge] " + message);
    }
}
