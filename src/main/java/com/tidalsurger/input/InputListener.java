package com.tidalsurger.input;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.ability.AbilityType;
import com.tidalsurger.config.PluginSettings;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;

/**
 * The plugin's key-detection layer.
 *
 * <p>Minecraft does not hand raw key presses to server plugins, but the vanilla
 * offhand keybind (F) is transmitted as a "swap items with offhand" action and
 * Paper surfaces it as {@link PlayerSwapHandItemsEvent}. That gives us a
 * reliable, client-mod-free offhand-key signal, and the player's sneak state at
 * that moment yields the Shift + offhand combination.</p>
 *
 * <p>The combination is configurable through {@code input.*}: the sneak grace
 * window allows a quick Shift tap just before F, the vanilla swap can be
 * cancelled so F acts as a dedicated ability key, and an optional
 * right-click fallback covers clients whose swap key was remapped.</p>
 */
public final class InputListener implements Listener {

    private final TidalSurgerPlugin plugin;
    private final Map<UUID, Long> lastSneak = new HashMap<>();

    public InputListener(TidalSurgerPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onSwapHandItems(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        PluginSettings settings = plugin.settings();

        ItemStack main = event.getMainHandItem();
        ItemStack off = event.getOffHandItem();
        boolean mainIsTrident = plugin.isTidalSurger(main);
        boolean offIsTrident = plugin.isTidalSurger(off);

        boolean holdsTrident = settings.input.requireItemInMainHand ? mainIsTrident : (mainIsTrident || offIsTrident);
        if (!holdsTrident) {
            // An ordinary trident (or any other item) keeps the vanilla swap behaviour.
            return;
        }

        debug(player, "offhand key detected (main=" + mainIsTrident + ", off=" + offIsTrident + ")");

        boolean shift = isShiftHeld(player, settings);
        AbilityType type = shift ? AbilityType.TIDAL_CORE : AbilityType.TIDAL_SURF;

        if (settings.input.cancelVanillaSwap) {
            event.setCancelled(true);
        }
        debug(player, "trigger " + type.configKey() + " (shift=" + shift + ")");
        plugin.abilities().activate(player, type);
    }

    /**
     * Optional documented fallback: right-click (sneak + right-click) while
     * holding the Tidal Surger in the main hand.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        PluginSettings settings = plugin.settings();
        if (!settings.input.rightClickFallback) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.isTidalSurger(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (player.isSneaking()) {
            event.setCancelled(true);
            plugin.abilities().activate(player, AbilityType.TIDAL_CORE);
        } else if (event.getAction() == Action.RIGHT_CLICK_AIR) {
            plugin.abilities().activate(player, AbilityType.TIDAL_SURF);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleSneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking()) {
            lastSneak.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastSneak.remove(event.getPlayer().getUniqueId());
    }

    private boolean isShiftHeld(Player player, PluginSettings settings) {
        if (player.isSneaking()) {
            return true;
        }
        Long pressed = lastSneak.get(player.getUniqueId());
        return pressed != null && System.currentTimeMillis() - pressed <= settings.input.sneakGraceMillis;
    }

    private void debug(Player player, String message) {
        if (plugin.settings().debug) {
            plugin.getLogger().log(Level.INFO, "[debug] " + player.getName() + ": " + message);
        }
    }
}
