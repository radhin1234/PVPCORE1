package com.pvpcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.KineticWeapon;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.util.logging.Level;

/**
 * Spear charge rules.
 *
 * <p>The spear's charge attack is driven by the {@code kinetic_weapon} item component: a wind-up
 * delay, plus three time windows (damage, knockback, dismount) that each expire after a number of
 * ticks and need a minimum speed. This module rewrites that component on every spear a player
 * holds, so the charge hits immediately and the windows do not run out. Damage still scales with
 * speed, which keeps the elytra dive as the strongest hit.
 */
public final class SpearListener implements Listener {

    /** One of the three effect windows as it appears in config.yml. */
    private record Spec(boolean override, int maxDurationTicks, float minSpeed, float minRelativeSpeed) {
        KineticWeapon.Condition toCondition() {
            return KineticWeapon.condition(maxDurationTicks, minSpeed, minRelativeSpeed);
        }
    }

    private final PvPCore plugin;

    private boolean enabled;
    private int delayTicks;
    private int contactCooldownTicks;
    private float damageMultiplier;
    private Spec damage;
    private Spec knockback;
    private Spec dismount;
    private BukkitTask scanTask;
    private boolean broken;

    public SpearListener(PvPCore plugin) {
        this.plugin = plugin;
    }

    void reload() {
        var config = plugin.getConfig();
        enabled = config.getBoolean("spear.enabled", true);
        delayTicks = Math.max(0, config.getInt("spear.charge.delay-ticks", 0));
        contactCooldownTicks = config.getInt("spear.charge.contact-cooldown-ticks", -1);
        damageMultiplier = (float) config.getDouble("spear.charge.damage-multiplier", -1.0);
        damage = readSpec("spear.charge.damage");
        knockback = readSpec("spear.charge.knockback");
        dismount = readSpec("spear.charge.dismount");
        broken = false;

        if (scanTask != null) {
            scanTask.cancel();
            scanTask = null;
        }
        int interval = config.getInt("spear.auto-patch-interval-ticks", 20);
        if (enabled && interval > 0) {
            scanTask = Bukkit.getScheduler().runTaskTimer(plugin, this::patchAllOnline, interval, interval);
        }
        if (enabled) {
            Bukkit.getScheduler().runTask(plugin, this::patchAllOnline);
        }
    }

    private Spec readSpec(String path) {
        var config = plugin.getConfig();
        return new Spec(
                config.getBoolean(path + ".override", true),
                Math.max(0, config.getInt(path + ".max-duration-ticks", 1200)),
                (float) config.getDouble(path + ".min-speed", 0.0),
                (float) config.getDouble(path + ".min-relative-speed", 0.0));
    }

    public static boolean isSpear(ItemStack stack) {
        return stack != null && !stack.getType().isAir() && stack.getType().name().endsWith("_SPEAR");
    }

    /**
     * Applies the configured rules to a spear. Safe to call repeatedly: nothing is written when the
     * item already matches.
     *
     * @return true if the item was modified
     */
    public boolean apply(ItemStack stack) {
        if (!enabled || broken || !isSpear(stack)) {
            return false;
        }
        try {
            KineticWeapon base = stack.getData(DataComponentTypes.KINETIC_WEAPON);
            if (base == null) {
                return false;
            }

            int wantedContact = contactCooldownTicks >= 0 ? contactCooldownTicks : base.contactCooldownTicks();
            float wantedMultiplier = damageMultiplier >= 0.0f ? damageMultiplier : base.damageMultiplier();
            KineticWeapon.Condition wantedDamage = damage.override() ? damage.toCondition() : base.damageConditions();
            KineticWeapon.Condition wantedKnockback =
                    knockback.override() ? knockback.toCondition() : base.knockbackConditions();
            KineticWeapon.Condition wantedDismount =
                    dismount.override() ? dismount.toCondition() : base.dismountConditions();

            boolean unchanged = base.delayTicks() == delayTicks
                    && base.contactCooldownTicks() == wantedContact
                    && Float.compare(base.damageMultiplier(), wantedMultiplier) == 0
                    && sameCondition(base.damageConditions(), wantedDamage)
                    && sameCondition(base.knockbackConditions(), wantedKnockback)
                    && sameCondition(base.dismountConditions(), wantedDismount);
            if (unchanged) {
                return false;
            }

            KineticWeapon updated = KineticWeapon.kineticWeapon()
                    .contactCooldownTicks(wantedContact)
                    .delayTicks(delayTicks)
                    .dismountConditions(wantedDismount)
                    .knockbackConditions(wantedKnockback)
                    .damageConditions(wantedDamage)
                    .forwardMovement(base.forwardMovement())
                    .damageMultiplier(wantedMultiplier)
                    .sound(base.sound())
                    .hitSound(base.hitSound())
                    .build();
            stack.setData(DataComponentTypes.KINETIC_WEAPON, updated);
            return true;
        } catch (LinkageError | RuntimeException ex) {
            // The server's API differs from what this build expects. Stop touching items.
            broken = true;
            plugin.getLogger().log(Level.SEVERE,
                    "Spear module disabled: the kinetic weapon API did not behave as expected. "
                            + "Please report this with your server version.", ex);
            return false;
        }
    }

    private static boolean sameCondition(KineticWeapon.Condition a, KineticWeapon.Condition b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.maxDurationTicks() == b.maxDurationTicks()
                && Float.compare(a.minSpeed(), b.minSpeed()) == 0
                && Float.compare(a.minRelativeSpeed(), b.minRelativeSpeed()) == 0;
    }

    // ------------------------------------------------------------------
    // Keeping spears patched
    // ------------------------------------------------------------------

    private void patchAllOnline() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            patchPlayer(player);
        }
    }

    private void patchPlayer(Player player) {
        if (!enabled || !player.isOnline()) {
            return;
        }
        PlayerInventory inventory = player.getInventory();
        int size = inventory.getSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (isSpear(stack) && apply(stack)) {
                inventory.setItem(slot, stack);
            }
        }
        ItemStack cursor = player.getItemOnCursor();
        if (isSpear(cursor) && apply(cursor)) {
            player.setItemOnCursor(cursor);
        }
    }

    private void patchSoon(Player player, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> patchPlayer(player), delayTicks);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        patchSoon(event.getPlayer(), 1L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        patchSoon(event.getPlayer(), 1L);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent event) {
        patchSoon(event.getPlayer(), 1L);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            patchSoon(player, 1L);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            patchSoon(player, 1L);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (apply(stack)) {
            event.getItem().setItemStack(stack);
        }
    }
}
