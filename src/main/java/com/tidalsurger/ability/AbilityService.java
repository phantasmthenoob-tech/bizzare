package com.tidalsurger.ability;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.config.Messages;
import com.tidalsurger.config.PluginSettings;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

/**
 * Resolves an ability activation: permission, enable flag, cooldown, then hands
 * the request to the matching ability implementation.
 */
public final class AbilityService {

    private final TidalSurgerPlugin plugin;
    private final Messages messages;

    public AbilityService(TidalSurgerPlugin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin);
    }

    public boolean activate(Player player, AbilityType type) {
        PluginSettings settings = plugin.settings();
        PluginSettings.AbilityConfig ability = settings.ability(type);

        if (!plugin.isTidalSurger(player.getInventory().getItemInMainHand())
                && !plugin.isTidalSurger(player.getInventory().getItemInOffHand())) {
            return false;
        }
        if (!player.hasPermission("tidalsurger.use") || !player.hasPermission(ability.permission())) {
            messages.send(player, "no-permission");
            return false;
        }
        if (!ability.enabled()) {
            messages.send(player, "ability-disabled", "ability", type.displayName());
            return false;
        }

        boolean bypass = player.hasPermission("tidalsurger.bypass.cooldown");
        long remaining = plugin.cooldowns().remainingMillis(player, type);
        if (remaining > 0L && !bypass) {
            messages.send(player, "cooldown",
                    "ability", type.displayName(),
                    "seconds", String.format(java.util.Locale.ROOT, "%.1f", remaining / 1000.0D));
            if (settings.surf.sounds) {
                player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, 0.6F, 1.6F);
            }
            return false;
        }

        boolean success = switch (type) {
            case TIDAL_SURF -> plugin.surfManager().begin(player);
            case TIDAL_CORE -> plugin.coreManager().cast(player);
        };

        if (success && !bypass) {
            plugin.cooldowns().set(player, type, ability.cooldownSeconds());
            if (settings.input.showItemCooldown) {
                player.setCooldown(Material.TRIDENT, ability.cooldownSeconds() * 20);
            }
            if (ability.cooldownSeconds() > 0) {
                plugin.cooldownBars().onCooldownStarted(player, type);
            }
        }
        return success;
    }
}
