package com.tidalsurger.core;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.config.Messages;
import com.tidalsurger.config.PluginSettings;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>Tidal Core</b> - the Shift + offhand-key ability.
 *
 * <p>Ray-traces the player's crosshair for an enemy or a block, then raises a
 * 10x10x10 block water cube around that point and seals every living entity
 * inside for five seconds. The cube's owner is never trapped or slowed down and
 * can swim in and out freely to attack the victim.</p>
 */
public final class TidalCoreManager {

    private final TidalSurgerPlugin plugin;
    private final Messages messages;
    private final List<WaterCube> cubes = new ArrayList<>();
    private final Map<UUID, Integer> cubesByOwner = new HashMap<>();
    private final Set<UUID> internalTeleports = ConcurrentHashMap.newKeySet();

    /** True while a cube is applying its own drowning damage. */
    private boolean applyingCoreDamage;

    private BukkitTask task;

    public TidalCoreManager(TidalSurgerPlugin plugin) {
        this.plugin = plugin;
        this.messages = new Messages(plugin);
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (WaterCube cube : new ArrayList<>(cubes)) {
            cube.destroy();
        }
        cubes.clear();
        cubesByOwner.clear();
        internalTeleports.clear();
    }

    private void tick() {
        if (cubes.isEmpty()) {
            return;
        }
        Iterator<WaterCube> iterator = cubes.iterator();
        while (iterator.hasNext()) {
            WaterCube cube = iterator.next();
            if (!cube.tick()) {
                iterator.remove();
                cubesByOwner.computeIfPresent(cube.ownerId(), (uuid, count) -> count <= 1 ? null : count - 1);
            }
        }
    }

    /** @return true when the cube was created (the caller then applies the cooldown). */
    public boolean cast(Player caster) {
        PluginSettings settings = plugin.settings();
        PluginSettings.Core config = settings.core;

        if (cubes.size() >= settings.maxConcurrentCubesTotal
                || cubesByOwner.getOrDefault(caster.getUniqueId(), 0) >= settings.maxConcurrentCubesPerPlayer) {
            messages.send(caster, "core-limit");
            return false;
        }

        Target target = findTarget(caster, config);
        if (target == null) {
            messages.send(caster, "core-no-target", "range", String.valueOf(config.maxTargetDistance));
            return false;
        }

        Location anchor = target.location();
        World world = anchor.getWorld();
        WaterCube cube = new WaterCube(plugin, caster.getUniqueId(), world, anchor, target.entity(), config);
        cube.build();

        cubes.add(cube);
        cubesByOwner.merge(caster.getUniqueId(), 1, Integer::sum);

        Location center = cube.centerLocation();
        world.playSound(center, Sound.ITEM_TRIDENT_THUNDER, 0.9F, 0.8F);
        world.playSound(center, Sound.BLOCK_CONDUIT_ACTIVATE, 1.0F, 0.7F);
        world.playSound(center, Sound.ENTITY_ELDER_GUARDIAN_AMBIENT, 0.6F, 0.6F);
        world.spawnParticle(Particle.SPLASH, center, 70, config.size / 2.0D, config.height / 2.0D, config.size / 2.0D, 0.1D);
        world.spawnParticle(Particle.DOLPHIN, center, 60, config.size / 2.0D, config.height / 2.0D, config.size / 2.0D, 0.05D);

        messages.send(caster, "core-cast");
        if (target.entity() instanceof Player victim) {
            messages.send(victim, "core-hover");
            victim.playSound(victim.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.8F, 1.2F);
        }
        if (settings.debug) {
            plugin.getLogger().info("[debug] Tidal Core cast by " + caster.getName() + " -> " + cube.describe());
        }
        return true;
    }

    /**
     * Looks at the world the same way the player does: an entity in front of the
     * crosshair wins, otherwise the block face hit, otherwise a point at maximum
     * range. The returned location is the anchor the water cube lands on - the
     * target's feet, or the surface above the block that was hit.
     */
    private Target findTarget(Player caster, PluginSettings.Core config) {
        World world = caster.getWorld();
        Location eye = caster.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        double maxDistance = config.maxTargetDistance;

        RayTraceResult blockHit = world.rayTraceBlocks(eye, direction, maxDistance, FluidCollisionMode.NEVER, true);
        double limit = maxDistance;
        if (blockHit != null && blockHit.getHitBlock() != null) {
            limit = Math.max(1.0D, eye.toVector().distance(blockHit.getHitPosition()));
        }

        RayTraceResult entityHit = world.rayTraceEntities(eye, direction, limit, 0.9D,
                entity -> entity instanceof LivingEntity
                        && !(entity instanceof ArmorStand)
                        && entity.isValid()
                        && !entity.getUniqueId().equals(caster.getUniqueId()));

        if (entityHit != null && entityHit.getHitEntity() != null) {
            return new Target(entityHit.getHitEntity().getLocation(), entityHit.getHitEntity());
        }
        if (blockHit != null && blockHit.getHitBlock() != null) {
            // The surface standing on the hit block, so the cube drops onto it.
            return new Target(blockHit.getHitBlock().getLocation().add(0.5D, 1.0D, 0.5D), null);
        }
        return new Target(eye.clone().add(direction.multiply(maxDistance)), null);
    }

    /** Called by a cube before it moves a victim, so the protection listener allows it. */
    public void teleportInternally(Player player, Location location) {
        internalTeleports.add(player.getUniqueId());
        try {
            player.teleport(location);
        } finally {
            internalTeleports.remove(player.getUniqueId());
        }
    }

    public boolean isInternalTeleport(Player player) {
        return internalTeleports.contains(player.getUniqueId());
    }

    /** Marks the start of the plugin's own drowning damage (see the protection listener). */
    public void beginCoreDamage() {
        applyingCoreDamage = true;
    }

    public void endCoreDamage() {
        applyingCoreDamage = false;
    }

    public boolean isApplyingCoreDamage() {
        return applyingCoreDamage;
    }

    public boolean isTrapped(Entity entity) {
        return cubeTrapping(entity) != null;
    }

    public WaterCube cubeTrapping(Entity entity) {
        for (WaterCube cube : cubes) {
            if (cube.isTrapping(entity)) {
                return cube;
            }
        }
        return null;
    }

    public boolean hasCubes() {
        return !cubes.isEmpty();
    }

    public List<WaterCube> cubes() {
        return List.copyOf(cubes);
    }

    /** A resolved crosshair target. */
    private record Target(Location location, Entity entity) {
    }
}
