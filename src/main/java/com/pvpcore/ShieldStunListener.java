package com.pvpcore;

import io.papermc.paper.event.player.PlayerShieldDisableEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Shield stunning.
 *
 * <p>Since 1.20.5 the invulnerability frames a player gets from a blocked hit swallow the next
 * hit, which breaks the classic "axe the shield, then hit immediately" stun. When a shield is
 * disabled this module records a short stun window for the victim, and while that window is open
 * the victim's invulnerability frames are cleared right before the follow-up attack is processed.
 */
public final class ShieldStunListener implements Listener {

    private record Stun(UUID attacker, int expiresAtTick) {
    }

    private final PvPCore plugin;
    private final Map<UUID, Stun> stuns = new HashMap<>();

    private boolean enabled;
    private int windowTicks;
    private boolean sameAttackerOnly;
    private boolean consumeOnHit;
    private boolean playersOnly;
    private int disableCooldownTicks;

    public ShieldStunListener(PvPCore plugin) {
        this.plugin = plugin;
    }

    void reload() {
        var config = plugin.getConfig();
        String base = "shield-stun.";
        enabled = config.getBoolean(base + "enabled", true);
        windowTicks = Math.max(1, config.getInt(base + "window-ticks", 10));
        sameAttackerOnly = config.getBoolean(base + "same-attacker-only", false);
        consumeOnHit = config.getBoolean(base + "consume-on-hit", true);
        playersOnly = config.getBoolean(base + "players-only", true);
        disableCooldownTicks = config.getInt(base + "disable-cooldown-ticks", -1);
        if (!enabled) {
            stuns.clear();
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldDisable(PlayerShieldDisableEvent event) {
        if (!enabled) {
            return;
        }
        Entity damager = event.getDamager();
        if (playersOnly && !(damager instanceof Player)) {
            return;
        }

        if (disableCooldownTicks > 0) {
            event.setCooldown(disableCooldownTicks);
        }

        Player victim = event.getPlayer();
        stuns.put(victim.getUniqueId(),
                new Stun(damager.getUniqueId(), Bukkit.getCurrentTick() + windowTicks));

        // Clear now, and again next tick in case the damage code sets frames after this event.
        victim.setNoDamageTicks(0);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (victim.isOnline()) {
                victim.setNoDamageTicks(0);
            }
        });
        debug(damager, "Shield stun on " + victim.getName());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPreAttack(PrePlayerAttackEntityEvent event) {
        if (!enabled || !(event.getAttacked() instanceof Player victim)) {
            return;
        }
        Stun stun = stuns.get(victim.getUniqueId());
        if (stun == null) {
            return;
        }
        if (Bukkit.getCurrentTick() > stun.expiresAtTick()) {
            stuns.remove(victim.getUniqueId());
            return;
        }
        Player attacker = event.getPlayer();
        if (sameAttackerOnly && !stun.attacker().equals(attacker.getUniqueId())) {
            return;
        }

        victim.setNoDamageTicks(0);
        if (consumeOnHit) {
            stuns.remove(victim.getUniqueId());
        }
        debug(attacker, "Follow-up hit on " + victim.getName() + " lands instantly");
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stuns.remove(event.getPlayer().getUniqueId());
    }

    private void debug(Entity target, String message) {
        if (target instanceof Player player) {
            plugin.sendDebug(player, "[shield stun] " + message);
        }
    }
}
