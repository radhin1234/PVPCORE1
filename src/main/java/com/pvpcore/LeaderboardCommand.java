package com.pvpcore;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * /leaderboard: opens a dialog listing the best K/D first. Console, and clients or servers
 * that cannot show dialogs, get the same list in chat.
 */
public final class LeaderboardCommand implements CommandExecutor {

    private static final String DEFAULT_TITLE = "<gold><bold>K/D Leaderboard</bold></gold>";
    private static final int BODY_WIDTH = 320;
    private static final TextColor SILVER = TextColor.color(0xC0C0C0);
    private static final TextColor BRONZE = TextColor.color(0xCD7F32);

    private final PvPCore plugin;
    private final StatsManager stats;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private boolean dialogWarned;

    public LeaderboardCommand(PvPCore plugin, StatsManager stats) {
        this.plugin = plugin;
        this.stats = stats;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        var config = plugin.getConfig();
        int size = Math.max(1, Math.min(50, config.getInt("leaderboard.size", 10)));
        int decimals = Math.max(0, Math.min(4, config.getInt("leaderboard.kd-decimals", 2)));
        Component title = miniMessage.deserialize(config.getString("leaderboard.title", DEFAULT_TITLE));

        UUID viewer = sender instanceof Player player ? player.getUniqueId() : null;
        Component subtitle = Component.text("Best K/D first. The small numbers are kills/deaths.", NamedTextColor.GRAY);
        Component list = listComponent(stats.top(size), viewer, decimals);
        Component own = ownComponent(viewer, decimals);

        if (sender instanceof Player player && showDialog(player, title, subtitle, list, own)) {
            return true;
        }

        // Console, or the dialog could not be shown.
        sender.sendMessage(title);
        sender.sendMessage(subtitle);
        sender.sendMessage(list);
        if (own != null) {
            sender.sendMessage(own);
        }
        return true;
    }

    private boolean showDialog(Player player, Component title, Component subtitle, Component list, Component own) {
        try {
            List<DialogBody> body = new ArrayList<>();
            body.add(DialogBody.plainMessage(subtitle, BODY_WIDTH));
            body.add(DialogBody.plainMessage(list, BODY_WIDTH));
            if (own != null) {
                body.add(DialogBody.plainMessage(own, BODY_WIDTH));
            }

            Dialog dialog = Dialog.create(factory -> factory.empty()
                    .base(DialogBase.builder(title)
                            .canCloseWithEscape(true)
                            .body(body)
                            .build())
                    .type(DialogType.notice()));

            player.showDialog(dialog);
            return true;
        } catch (LinkageError | RuntimeException ex) {
            if (!dialogWarned) {
                dialogWarned = true;
                plugin.getLogger().log(Level.WARNING,
                        "Could not show the leaderboard dialog, falling back to chat. "
                                + "Dialogs need Paper 1.21.7 or newer.", ex);
            }
            return false;
        }
    }

    private Component listComponent(List<StatsManager.Standing> top, UUID viewer, int decimals) {
        if (top.isEmpty()) {
            return Component.text("No ranked players yet. Get a kill to be the first!", NamedTextColor.YELLOW);
        }
        TextComponent.Builder builder = Component.text();
        for (int i = 0; i < top.size(); i++) {
            if (i > 0) {
                builder.append(Component.newline());
            }
            builder.append(line(top.get(i), viewer, decimals));
        }
        return builder.build();
    }

    private static Component line(StatsManager.Standing s, UUID viewer, int decimals) {
        TextColor rankColor = switch (s.rank()) {
            case 1 -> NamedTextColor.GOLD;
            case 2 -> SILVER;
            case 3 -> BRONZE;
            default -> NamedTextColor.WHITE;
        };
        NamedTextColor nameColor = s.id().equals(viewer) ? NamedTextColor.AQUA : NamedTextColor.WHITE;

        return Component.text()
                .append(Component.text("#" + s.rank(), rankColor, TextDecoration.BOLD))
                .append(Component.text("  " + s.name(), nameColor))
                .append(Component.text("  " + format(s.kd(), decimals) + " K/D", rankColor))
                .append(Component.text("  " + s.kills() + "/" + s.deaths(), NamedTextColor.GRAY))
                .build();
    }

    /** The viewer's own line, or null for the console. */
    private Component ownComponent(UUID viewer, int decimals) {
        if (viewer == null) {
            return null;
        }
        StatsManager.Standing s = stats.standing(viewer);
        if (s == null) {
            return Component.text("You are not ranked yet. Get a kill to join the board.", NamedTextColor.YELLOW);
        }
        return Component.text()
                .append(Component.text("You: ", NamedTextColor.GRAY))
                .append(Component.text("#" + s.rank(), NamedTextColor.AQUA, TextDecoration.BOLD))
                .append(Component.text("  " + format(s.kd(), decimals) + " K/D", NamedTextColor.AQUA))
                .append(Component.text("  " + s.kills() + "/" + s.deaths(), NamedTextColor.GRAY))
                .build();
    }

    private static String format(double kd, int decimals) {
        return String.format(Locale.ROOT, "%." + decimals + "f", kd);
    }
}
