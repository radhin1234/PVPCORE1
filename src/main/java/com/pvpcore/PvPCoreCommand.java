package com.pvpcore;

import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.KineticWeapon;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * /pvpcore reload | info | spear | kit spearelytra [player]
 */
public final class PvPCoreCommand implements TabExecutor {

    private static final List<String> SUBCOMMANDS = List.of("reload", "info", "spear", "kit", "stats");

    private final PvPCore plugin;

    public PvPCoreCommand(PvPCore plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            send(sender, "Usage: /" + label + " <reload|info|spear|kit|stats>", NamedTextColor.YELLOW);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                plugin.reloadModules();
                send(sender, "PvPCore config reloaded.", NamedTextColor.GREEN);
            }
            case "info" -> info(sender);
            case "spear" -> patchHeldSpear(sender);
            case "kit" -> kit(sender, args);
            case "stats" -> stats(sender, args);
            default -> send(sender, "Unknown subcommand. Use reload, info, spear, kit or stats.", NamedTextColor.RED);
        }
        return true;
    }

    /** /pvpcore stats reset <player|all confirm> */
    private void stats(CommandSender sender, String[] args) {
        StatsManager stats = plugin.getStats();
        if (args.length >= 3 && args[1].equalsIgnoreCase("reset")) {
            if (args[2].equalsIgnoreCase("all")) {
                if (args.length < 4 || !args[3].equalsIgnoreCase("confirm")) {
                    send(sender, "This wipes everyone's K/D. Run /pvpcore stats reset all confirm to do it.",
                            NamedTextColor.YELLOW);
                    return;
                }
                send(sender, "Reset the stats of " + stats.resetAll() + " players.", NamedTextColor.GREEN);
                return;
            }
            UUID id = stats.findByName(args[2]);
            if (id == null || !stats.reset(id)) {
                send(sender, "No stats found for " + args[2] + ".", NamedTextColor.RED);
                return;
            }
            send(sender, "Reset the stats of " + args[2] + ".", NamedTextColor.GREEN);
            return;
        }
        send(sender, "Usage: /pvpcore stats reset <player|all confirm>", NamedTextColor.YELLOW);
    }

    private void info(CommandSender sender) {
        var config = plugin.getConfig();
        send(sender, "K/D stats: " + plugin.getStats().rankedCount() + " ranked players", NamedTextColor.AQUA);
        send(sender, "Wind charge reset: " + onOff(config.getBoolean("wind-charge-reset.enabled", true)), NamedTextColor.AQUA);
        send(sender, "Shield stun: " + onOff(config.getBoolean("shield-stun.enabled", true)), NamedTextColor.AQUA);
        if (plugin.isSpearModuleAvailable()) {
            send(sender, "Spear rules: " + onOff(config.getBoolean("spear.enabled", true)), NamedTextColor.AQUA);
        } else {
            send(sender, "Spear rules: unavailable (server has no spear API)", NamedTextColor.RED);
        }
        if (sender instanceof Player player) {
            describeSpear(player, player.getInventory().getItemInMainHand());
        }
    }

    private void patchHeldSpear(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "Only players can use this.", NamedTextColor.RED);
            return;
        }
        if (!plugin.isSpearModuleAvailable()) {
            send(sender, "This server has no spear API.", NamedTextColor.RED);
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!SpearListener.isSpear(held)) {
            send(sender, "Hold a spear in your main hand.", NamedTextColor.RED);
            return;
        }
        boolean changed = plugin.applySpearRules(held);
        if (changed) {
            player.getInventory().setItemInMainHand(held);
        }
        send(sender, changed ? "Spear rules applied." : "This spear already matches the config.", NamedTextColor.GREEN);
        describeSpear(player, held);
    }

    private void describeSpear(Player player, ItemStack stack) {
        if (!plugin.isSpearModuleAvailable() || !SpearListener.isSpear(stack)) {
            return;
        }
        KineticWeapon weapon = stack.getData(DataComponentTypes.KINETIC_WEAPON);
        if (weapon == null) {
            send(player, "Held spear has no kinetic weapon component.", NamedTextColor.RED);
            return;
        }
        send(player, "Held spear: delay " + weapon.delayTicks() + "t, contact cooldown "
                + weapon.contactCooldownTicks() + "t, damage x" + weapon.damageMultiplier(), NamedTextColor.GRAY);
        send(player, "  damage: " + describe(weapon.damageConditions()), NamedTextColor.GRAY);
        send(player, "  knockback: " + describe(weapon.knockbackConditions()), NamedTextColor.GRAY);
        send(player, "  dismount: " + describe(weapon.dismountConditions()), NamedTextColor.GRAY);
    }

    private static String describe(KineticWeapon.Condition condition) {
        if (condition == null) {
            return "none";
        }
        return condition.maxDurationTicks() + "t window, min speed " + condition.minSpeed()
                + ", min relative speed " + condition.minRelativeSpeed();
    }

    // ------------------------------------------------------------------
    // Test kit
    // ------------------------------------------------------------------

    private void kit(CommandSender sender, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("spearelytra")) {
            send(sender, "Usage: /pvpcore kit spearelytra [player]", NamedTextColor.YELLOW);
            return;
        }
        Player target;
        if (args.length >= 3) {
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                send(sender, "Player not found: " + args[2], NamedTextColor.RED);
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            send(sender, "Console must name a player.", NamedTextColor.RED);
            return;
        }
        giveSpearElytraKit(target);
        send(sender, "Gave the spear/elytra test kit to " + target.getName() + ".", NamedTextColor.GREEN);
    }

    /** Adds the items to the inventory without clearing it; armor goes into empty armor slots only. */
    private void giveSpearElytraKit(Player target) {
        PlayerInventory inventory = target.getInventory();

        equip(inventory, inventory.getHelmet() == null, new ItemStack(Material.NETHERITE_HELMET), 0);
        ItemStack elytra = new ItemStack(Material.ELYTRA);
        elytra.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
        equip(inventory, inventory.getChestplate() == null, elytra, 1);
        equip(inventory, inventory.getLeggings() == null, new ItemStack(Material.NETHERITE_LEGGINGS), 2);
        equip(inventory, inventory.getBoots() == null, new ItemStack(Material.NETHERITE_BOOTS), 3);

        Material spearMaterial = Material.matchMaterial("NETHERITE_SPEAR");
        if (spearMaterial != null) {
            ItemStack spear = new ItemStack(spearMaterial);
            spear.addUnsafeEnchantment(Enchantment.UNBREAKING, 3);
            plugin.applySpearRules(spear);
            inventory.addItem(spear);
        }

        ItemStack rockets = new ItemStack(Material.FIREWORK_ROCKET, 64);
        FireworkMeta rocketMeta = (FireworkMeta) rockets.getItemMeta();
        rocketMeta.setPower(3);
        rockets.setItemMeta(rocketMeta);
        inventory.addItem(rockets);

        inventory.addItem(new ItemStack(Material.WIND_CHARGE, 64));
        inventory.addItem(new ItemStack(Material.MACE));
        inventory.addItem(new ItemStack(Material.NETHERITE_AXE));
        inventory.addItem(new ItemStack(Material.GOLDEN_APPLE, 16));
        if (inventory.getItemInOffHand().getType().isAir()) {
            inventory.setItemInOffHand(new ItemStack(Material.SHIELD));
        }
    }

    /** armorSlot: 0 helmet, 1 chestplate, 2 leggings, 3 boots. */
    private static void equip(PlayerInventory inventory, boolean slotFree, ItemStack stack, int armorSlot) {
        if (!slotFree) {
            inventory.addItem(stack);
            return;
        }
        switch (armorSlot) {
            case 0 -> inventory.setHelmet(stack);
            case 1 -> inventory.setChestplate(stack);
            case 2 -> inventory.setLeggings(stack);
            default -> inventory.setBoots(stack);
        }
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("stats")) {
            if ("reset".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                out.add("reset");
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("stats") && args[1].equalsIgnoreCase("reset")) {
            String prefix = args[2].toLowerCase(Locale.ROOT);
            if ("all".startsWith(prefix)) {
                out.add("all");
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    out.add(player.getName());
                }
            }
        } else if (args.length == 4 && args[0].equalsIgnoreCase("stats") && args[2].equalsIgnoreCase("all")) {
            if ("confirm".startsWith(args[3].toLowerCase(Locale.ROOT))) {
                out.add("confirm");
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("kit")) {
            if ("spearelytra".startsWith(args[1].toLowerCase(Locale.ROOT))) {
                out.add("spearelytra");
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("kit")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(player.getName());
                }
            }
        }
        return out;
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static void send(CommandSender sender, String message, NamedTextColor color) {
        sender.sendMessage(Component.text(message, color));
    }
}
