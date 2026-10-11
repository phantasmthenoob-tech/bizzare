package com.tidalsurger.command;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.ability.AbilityType;
import com.tidalsurger.config.Messages;
import com.tidalsurger.core.WaterCube;
import com.tidalsurger.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code /tidalsurger} (aliases {@code /ts}, {@code /tidalsurge}).
 */
public final class TidalSurgerCommand implements CommandExecutor, TabCompleter {

    private final TidalSurgerPlugin plugin;
    private final Messages messages;

    public TidalSurgerCommand(TidalSurgerPlugin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give" -> give(sender, args);
            case "reload" -> reload(sender);
            case "info" -> info(sender);
            case "cooldown" -> cooldown(sender, args);
            case "cubes" -> cubes(sender);
            default -> {
                messages.send(sender, "unknown-command");
                sendUsage(sender, label);
            }
        }
        return true;
    }

    private void give(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tidalsurger.command.give")) {
            messages.send(sender, "no-permission");
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                messages.send(sender, "player-not-found", "player", args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            target = player;
        } else {
            messages.send(sender, "player-only");
            return;
        }

        ItemStack item = plugin.itemFactory().create();
        String displayName = plugin.getConfig().getString("item.name", "Tidal Surger");
        Map<Integer, ItemStack> leftovers = target.getInventory().addItem(item);
        for (ItemStack leftover : leftovers.values()) {
            target.getWorld().dropItemNaturally(target.getLocation(), leftover);
        }
        target.playSound(target.getLocation(), Sound.BLOCK_CONDUIT_ACTIVATE, 1.0F, 1.2F);
        target.playSound(target.getLocation(), Sound.ITEM_TRIDENT_RIPTIDE_1, 0.8F, 1.4F);

        messages.send(target, "give-self", "item", displayName);
        if (!target.equals(sender)) {
            messages.send(sender, "give-other", "player", target.getName(), "item", displayName);
        }
    }

    /**
     * {@code /ts cooldown [player]} - wipes a player's ability cooldowns and the
     * boss bars that are draining for them, so both abilities are usable again at
     * once. Clearing your own cooldowns needs no target.
     */
    private void cooldown(CommandSender sender, String[] args) {
        if (!sender.hasPermission("tidalsurger.command.cooldown")) {
            messages.send(sender, "no-permission");
            return;
        }
        Player target;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                messages.send(sender, "player-not-found", "player", args[1]);
                return;
            }
        } else if (sender instanceof Player self) {
            target = self;
        } else {
            messages.send(sender, "player-only");
            return;
        }

        for (AbilityType type : AbilityType.values()) {
            plugin.cooldowns().clear(target, type);
        }
        plugin.cooldowns().save();
        // The draining boss bars go away at once, not on their next expiry tick.
        plugin.cooldownBars().clear(target);
        // ...and so does the vanilla item-cooldown overlay on the trident.
        target.setCooldown(Material.TRIDENT, 0);

        if (target.equals(sender)) {
            messages.send(target, "cooldown-cleared-self");
        } else {
            messages.send(sender, "cooldown-cleared-other", "player", target.getName());
            messages.send(target, "cooldown-cleared-notify", "player", sender.getName());
        }
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("tidalsurger.command.reload")) {
            messages.send(sender, "no-permission");
            return;
        }
        plugin.reload();
        messages.send(sender, "reloaded");
    }

    private void info(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.send(sender, "player-only");
            return;
        }
        messages.send(player, "info-header");
        messages.send(player, "info-surf", "status", status(player, AbilityType.TIDAL_SURF));
        messages.send(player, "info-core", "status", status(player, AbilityType.TIDAL_CORE));
        messages.send(player, "info-usage");
    }

    private String status(Player player, AbilityType type) {
        long remaining = plugin.cooldowns().remainingMillis(player, type);
        if (remaining <= 0L) {
            return plugin.getConfig().getString("messages.info-ready", "ready");
        }
        String template = plugin.getConfig().getString("messages.info-cooldown", "%seconds%s cooldown");
        return template.replace("%seconds%", String.format(Locale.ROOT, "%.1f", remaining / 1000.0D));
    }

    private void cubes(CommandSender sender) {
        if (!sender.hasPermission("tidalsurger.command.debug")) {
            messages.send(sender, "no-permission");
            return;
        }
        List<WaterCube> active = plugin.coreManager().cubes();
        if (active.isEmpty()) {
            messages.send(sender, "cubes-none");
            return;
        }
        StringBuilder details = new StringBuilder();
        for (WaterCube cube : active) {
            if (details.length() > 0) {
                details.append(", ");
            }
            details.append(cube.describe());
        }
        messages.send(sender, "cubes-list", "count", String.valueOf(active.size()), "details", details.toString());
    }

    private void sendUsage(CommandSender sender, String label) {
        messages.send(sender, "info-usage");
        sender.sendMessage(Text.mm("<gray>/" + label + " give [player] <dark_gray>| <gray>receive the Tidal Surger"));
        sender.sendMessage(Text.mm("<gray>/" + label + " info <dark_gray>| <gray>ability status"));
        sender.sendMessage(Text.mm("<gray>/" + label + " cooldown [player] <dark_gray>| <gray>clear ability cooldowns"));
        sender.sendMessage(Text.mm("<gray>/" + label + " cubes <dark_gray>| <gray>list active water cubes"));
        sender.sendMessage(Text.mm("<gray>/" + label + " reload <dark_gray>| <gray>reload config.yml"));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> suggestions = new ArrayList<>();
        if (args.length == 1) {
            for (String option : List.of("give", "info", "cooldown", "cubes", "reload")) {
                if (option.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    suggestions.add(option);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("cooldown"))) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    suggestions.add(player.getName());
                }
            }
        }
        return suggestions;
    }
}
