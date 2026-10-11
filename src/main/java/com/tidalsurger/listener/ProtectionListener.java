package com.tidalsurger.listener;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.config.Messages;
import com.tidalsurger.core.WaterCube;
import org.bukkit.Location;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTeleportEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.Locale;

/**
 * Enforces the Tidal Core barrier and protects surfers from fall damage.
 *
 * <p>Containment is deliberately not block-based. Victims are pushed back the
 * moment they would cross the boundary, and every teleport that would take them
 * out of the cube - ender pearls, chorus fruit, {@code /tp} or another plugin -
 * is refused. The cube's owner and any bystander are not affected, so the water
 * stays swimmable for them.</p>
 */
public final class ProtectionListener implements Listener {

    private final TidalSurgerPlugin plugin;
    private final Messages messages;

    public ProtectionListener(TidalSurgerPlugin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin);
    }

    /**
     * Primary containment: rewrite the destination of the movement packet instead
     * of teleporting, which keeps the victim's movement smooth at the boundary.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.coreManager().hasCubes()) {
            return;
        }
        Player player = event.getPlayer();
        WaterCube cube = plugin.coreManager().cubeTrapping(player);
        if (cube == null) {
            return;
        }
        Location to = event.getTo();
        if (to == null || cube.isWithinBounds(to)) {
            return;
        }
        Location clamped = cube.clampInside(to);
        clamped.setYaw(to.getYaw());
        clamped.setPitch(to.getPitch());
        event.setTo(clamped);
    }

    /** Blocks ender pearls, chorus fruit, /tp and third-party teleports out of the cube. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!plugin.coreManager().hasCubes()) {
            return;
        }
        Player player = event.getPlayer();
        if (plugin.coreManager().isInternalTeleport(player)) {
            return;
        }
        WaterCube cube = plugin.coreManager().cubeTrapping(player);
        if (cube == null) {
            return;
        }
        Location to = event.getTo();
        if (to == null || cube.isWithinBounds(to)) {
            return;
        }
        event.setCancelled(true);
        messages.send(player, "cannot-escape");
        if (plugin.settings().debug) {
            plugin.getLogger().info("[debug] Blocked " + event.getCause() + " escape attempt by " + player.getName());
        }
    }

    /** Non-player victims (mobs with teleport AI, shulkers, endermen) are contained too. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityTeleport(EntityTeleportEvent event) {
        if (!plugin.coreManager().hasCubes()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Player) {
            return;
        }
        WaterCube cube = plugin.coreManager().cubeTrapping(entity);
        if (cube == null) {
            return;
        }
        Location to = event.getTo();
        if (to != null && !cube.isWithinBounds(to)) {
            event.setCancelled(true);
        }
    }

    /**
     * While a victim is sealed the cube owns drowning: vanilla drowning ticks are
     * suppressed so the victim takes exactly the configured total (3 hearts) even
     * though their breath bar is genuinely empty.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrownTick(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.DROWNING) {
            return;
        }
        if (plugin.coreManager().isApplyingCoreDamage()) {
            return;
        }
        if (plugin.coreManager().isTrapped(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Natural (hunger) regeneration is paused while an entity is sealed, so the
     * three hearts of drowning damage actually stay lost instead of being healed
     * back between hits. Other healing (potions, golden apples, plugins) still works.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onNaturalRegen(EntityRegainHealthEvent event) {
        if (!plugin.settings().core.suppressNaturalRegen) {
            return;
        }
        if (event.getRegainReason() != EntityRegainHealthEvent.RegainReason.SATIATED) {
            return;
        }
        if (plugin.coreManager().isTrapped(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Ender pearls are simply refused while trapped. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPearlLaunch(ProjectileLaunchEvent event) {
        if (!plugin.settings().core.blockEnderPearls || !plugin.coreManager().hasCubes()) {
            return;
        }
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) {
            return;
        }
        if (plugin.coreManager().isTrapped(player)) {
            event.setCancelled(true);
            messages.send(player, "cannot-escape");
        }
    }

    /** /tp, /teleport and their namespaced forms are refused with a readable message. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.settings().core.blockTeleportCommands || !plugin.coreManager().hasCubes()) {
            return;
        }
        String message = event.getMessage();
        if (message == null || message.length() < 2) {
            return;
        }
        int space = message.indexOf(' ');
        String label = (space == -1 ? message.substring(1) : message.substring(1, space)).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon != -1) {
            label = label.substring(colon + 1);
        }
        if (!label.equals("tp") && !label.equals("teleport")) {
            return;
        }
        if (plugin.coreManager().isTrapped(event.getPlayer())) {
            event.setCancelled(true);
            messages.send(event.getPlayer(), "blocked-command");
        }
    }

    /**
     * No fall damage while surfing (nor during the short grace period afterwards),
     * and none for an entity that was just launched by a Tidal Core slam: the slam
     * itself is the only damage the blast deals.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) {
            return;
        }
        Entity entity = event.getEntity();
        if (plugin.coreManager().shockwave().hasFallImmunity(entity)) {
            event.setCancelled(true);
            entity.setFallDistance(0.0F);
            return;
        }
        if (!(entity instanceof Player player)) {
            return;
        }
        if (plugin.surfManager().isSurfing(player) || plugin.surfManager().hasFallImmunity(player)) {
            event.setCancelled(true);
            player.setFallDistance(0.0F);
        }
    }

    /** Shockwave debris may never place itself back into the world. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDebrisLand(EntityChangeBlockEvent event) {
        if (plugin.coreManager().shockwave().isDebris(event.getEntity().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** A player who relogs mid-cooldown gets their recharge bar back. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.cooldownBars().onJoin(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        plugin.surfManager().clear(player);
        plugin.cooldownBars().onQuit(player);
        plugin.cooldowns().save();
    }
}
