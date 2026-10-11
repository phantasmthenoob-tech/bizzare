package com.tidalsurger.core;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.config.PluginSettings;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Tidal Core's landing shockwave - the ground slam.
 *
 * <p>When the water cube hits solid ground it kicks off a Paladin-style
 * shockwave:</p>
 * <ul>
 *   <li>the ring of ground blocks around the impact is launched into the air
 *       (temporary debris that can never place itself back down),</li>
 *   <li>every living entity in the blast radius is thrown up and away and takes
 *       one hit of slam damage,</li>
 *   <li>the ground itself shakes for a few ticks (a rumble plus micro-shoves),
 *       with an expanding debris ring.</li>
 * </ul>
 *
 * <p>The block boost only happens where the slam actually hits solid ground: over
 * open water there is nothing to launch and the impact stays a pure splash. No
 * block is ever permanently changed - the debris entities are removed by
 * {@link #tick()} and {@code EntityChangeBlockEvent} is cancelled for them as a
 * second safety net.</p>
 */
public final class CoreShockwave {

    /** How far above / below the impact height a solid surface is looked for. */
    private static final int GROUND_SCAN_ABOVE = 2;
    private static final int GROUND_SCAN_BELOW = 6;

    private final TidalSurgerPlugin plugin;
    private final Random random = new Random();

    /** Launched blocks, by entity id, and the tick at which each one is removed. */
    private final Set<UUID> debris = new HashSet<>();
    private final Map<UUID, Long> debrisExpiry = new HashMap<>();

    /** Entities that were launched and may not take fall damage for a while. */
    private final Map<UUID, Long> fallImmuneUntil = new HashMap<>();

    private final List<Shake> shakes = new ArrayList<>();
    private long ticks;
    private BukkitTask task;

    public CoreShockwave(TidalSurgerPlugin plugin) {
        this.plugin = plugin;
    }

    public void start() {
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        for (UUID id : new ArrayList<>(debris)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) {
                entity.remove();
            }
        }
        debris.clear();
        debrisExpiry.clear();
        fallImmuneUntil.clear();
        shakes.clear();
    }

    /** True while this falling block is shockwave debris. */
    public boolean isDebris(UUID entityId) {
        return debris.contains(entityId);
    }

    /** True while a just-launched entity is protected from fall damage. */
    public boolean hasFallImmunity(Entity entity) {
        if (entity == null) {
            return false;
        }
        Long until = fallImmuneUntil.get(entity.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    // ------------------------------------------------------------------ impact

    /**
     * Runs the whole slam at a landing point: launched edge blocks, blast damage
     * and knock-up, then the shaking ground.
     *
     * @param impact the cube's landing point (the centre of its floor)
     * @param owner  the caster, never damaged by their own slam (may be offline)
     */
    public void impact(World world, Location impact, Player owner) {
        PluginSettings.Core.Shockwave config = plugin.settings().core.shockwave;
        if (!config.enabled) {
            return;
        }
        Location center = impact.clone().add(0.5D, 0.0D, 0.5D);

        int launched = boostEdgeBlocks(world, impact, config);
        blast(world, center, owner, config);

        if (config.shakeTicks > 0) {
            shakes.add(new Shake(world, center, config.radius, config.shakeTicks, config.shake, config.effects));
        }
        if (config.effects) {
            world.playSound(center, Sound.ITEM_MACE_SMASH_GROUND_HEAVY, 1.0F, 0.7F);
            world.playSound(center, Sound.ITEM_MACE_SMASH_GROUND, 1.0F, 1.0F);
            world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 0.55F, 0.7F);
            if (launched == 0) {
                // No solid ground under the impact: a pure water crash.
                world.playSound(center, Sound.ENTITY_PLAYER_SPLASH, 1.1F, 0.6F);
            }
            world.spawnParticle(Particle.EXPLOSION_EMITTER, center.clone().add(0.0D, 0.3D, 0.0D), 1);
            world.spawnParticle(Particle.SPLASH, center, 80, config.radius / 3.0D, 0.35D, config.radius / 3.0D, 0.1D);
            world.spawnParticle(Particle.CLOUD, center, 35, config.radius / 3.0D, 0.2D, config.radius / 3.0D, 0.06D);
            world.spawnParticle(Particle.POOF, center, 20, config.radius / 2.5D, 0.15D, config.radius / 2.5D, 0.02D);
        }
        if (plugin.settings().debug) {
            plugin.getLogger().info("[debug] Tidal Core shockwave at "
                    + String.format(Locale.ROOT, "%.1f,%.1f,%.1f", center.getX(), center.getY(), center.getZ())
                    + " (launched blocks: " + launched + ")");
        }
    }

    /**
     * Launches the ring of ground blocks around the impact. Blocks are only
     * spawned where the gentle scan finds solid, non-tile-entity ground, so the
     * boost never happens over open water and never copies a chest or a sign.
     *
     * @return how many blocks were launched
     */
    private int boostEdgeBlocks(World world, Location impact, PluginSettings.Core.Shockwave config) {
        int count = config.blockCount;
        if (count <= 0) {
            return 0;
        }
        int launched = 0;
        double radius = config.radius;
        for (int i = 0; i < count; i++) {
            double angle = (Math.PI * 2.0D * i) / count + random.nextDouble() * 0.14D;
            int x = impact.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = impact.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                world.getChunkAt(x >> 4, z >> 4);
            }
            Block ground = findGround(world, x, z, impact.getBlockY());
            if (ground == null) {
                continue;
            }
            BlockData data = ground.getBlockData().clone();
            Location spawn = ground.getLocation().add(0.5D, 1.0D, 0.5D);

            Vector outward = new Vector(
                    ground.getX() + 0.5D - impact.getX(),
                    0.0D,
                    ground.getZ() + 0.5D - impact.getZ());
            if (outward.lengthSquared() > 1.0E-6D) {
                outward.normalize().multiply(0.14D);
            } else {
                outward.zero();
            }

            FallingBlock falling = world.spawn(spawn, FallingBlock.class);
            falling.setBlockData(data);
            falling.setDropItem(false);
            falling.setCancelDrop(true);
            falling.setHurtEntities(false);
            falling.setVelocity(outward.setY(config.blockLaunch));

            debris.add(falling.getUniqueId());
            debrisExpiry.put(falling.getUniqueId(), ticks + config.blockLifetimeTicks);
            launched++;

            if (config.effects) {
                world.spawnParticle(Particle.BLOCK, spawn, 10, 0.25D, 0.1D, 0.25D, 0.02D, data);
                world.spawnParticle(Particle.CLOUD, spawn, 3, 0.2D, 0.1D, 0.2D, 0.01D);
            }
        }
        return launched;
    }

    /** The top solid surface at (x, z) near the impact height, if there is one. */
    private Block findGround(World world, int x, int z, int impactY) {
        int min = Math.max(world.getMinHeight(), impactY - GROUND_SCAN_BELOW);
        int max = Math.min(world.getMaxHeight() - 1, impactY + GROUND_SCAN_ABOVE);
        for (int y = max; y >= min; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (isGround(block)) {
                return block;
            }
        }
        return null;
    }

    private boolean isGround(Block block) {
        Material type = block.getType();
        if (type.isAir() || !type.isSolid() || block.isLiquid()) {
            return false;
        }
        // A chest or a sign would lose its contents in a falling block, so skip
        // every block that carries tile-entity data.
        BlockState state = block.getState(false);
        return !(state instanceof TileState);
    }

    /** One hit of slam damage, and the knock-up, for everything caught in it. */
    private void blast(World world, Location center, Player owner, PluginSettings.Core.Shockwave config) {
        boolean hurt = config.damage > 0.0D;
        boolean push = config.launch > 0.0D || config.knockback > 0.0D;
        if (!hurt && !push) {
            return;
        }
        DamageSource source = damageSource(owner);
        double radius = config.radius;
        for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living) || living.isDead()) {
                continue;
            }
            if (owner != null && entity.getUniqueId().equals(owner.getUniqueId())) {
                continue;
            }
            if (entity instanceof Player player && player.getGameMode() == GameMode.SPECTATOR) {
                continue;
            }
            if (push) {
                Vector away = living.getLocation().toVector().subtract(center.toVector());
                away.setY(0.0D);
                double distance = Math.min(away.length(), radius);
                // Full force at the impact, tapering to 30% at the rim.
                double falloff = 1.0D - (distance / radius) * 0.7D;
                if (away.lengthSquared() > 1.0E-6D) {
                    away.normalize().multiply(config.knockback * falloff);
                } else {
                    away.zero();
                }
                living.setVelocity(away.setY(config.launch * falloff));
                living.setFallDistance(0.0F);
                if (config.fallImmunityTicks > 0) {
                    fallImmuneUntil.put(living.getUniqueId(),
                            System.currentTimeMillis() + config.fallImmunityTicks * 50L);
                }
            }
            if (hurt) {
                living.setNoDamageTicks(0);
                living.damage(config.damage, source);
            }
        }
    }

    private DamageSource damageSource(Player owner) {
        DamageSource.Builder builder = DamageSource.builder(DamageType.MACE_SMASH);
        if (owner != null) {
            builder.withCausingEntity(owner);
            builder.withDirectEntity(owner);
        }
        return builder.build();
    }

    // -------------------------------------------------------------------- tick

    private void tick() {
        ticks++;
        if (!shakes.isEmpty()) {
            Iterator<Shake> iterator = shakes.iterator();
            while (iterator.hasNext()) {
                Shake shake = iterator.next();
                shake.ticksLeft--;
                if (shake.ticksLeft <= 0) {
                    iterator.remove();
                    continue;
                }
                shake.shake();
            }
        }
        if (!debris.isEmpty()) {
            Iterator<Map.Entry<UUID, Long>> iterator = debrisExpiry.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, Long> entry = iterator.next();
                if (entry.getValue() > ticks) {
                    continue;
                }
                Entity entity = Bukkit.getEntity(entry.getKey());
                if (entity != null && entity.isValid()) {
                    entity.remove();
                }
                debris.remove(entry.getKey());
                iterator.remove();
            }
        }
        if (!fallImmuneUntil.isEmpty()) {
            long now = System.currentTimeMillis();
            fallImmuneUntil.entrySet().removeIf(entry -> entry.getValue() < now);
        }
    }

    /** One shaking ground: micro-shoves plus the expanding debris ring. */
    private final class Shake {

        private final World world;
        private final Location center;
        private final double radius;
        private final int totalTicks;
        private final double intensity;
        private final boolean effects;
        private int ticksLeft;

        private Shake(World world, Location center, double radius, int ticksLeft, double intensity, boolean effects) {
            this.world = world;
            this.center = center;
            this.radius = radius;
            this.totalTicks = ticksLeft;
            this.ticksLeft = ticksLeft;
            this.intensity = intensity;
            this.effects = effects;
        }

        private void shake() {
            if (intensity > 0.0D) {
                for (Entity entity : world.getNearbyEntities(center, radius, radius, radius)) {
                    if (!(entity instanceof LivingEntity living) || living.isDead()) {
                        continue;
                    }
                    if (living instanceof Player player && player.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }
                    Vector jitter = new Vector(
                            random.nextDouble() * 2.0D - 1.0D,
                            0.0D,
                            random.nextDouble() * 2.0D - 1.0D).multiply(intensity);
                    living.setVelocity(living.getVelocity().add(jitter));
                }
            }
            if (!effects) {
                return;
            }
            double progress = 1.0D - (ticksLeft / (double) totalTicks);
            double ringRadius = Math.max(0.6D, radius * progress);
            int points = 24;
            for (int i = 0; i < points; i++) {
                double angle = (Math.PI * 2.0D * i) / points;
                Location point = center.clone().add(Math.cos(angle) * ringRadius, 0.18D, Math.sin(angle) * ringRadius);
                world.spawnParticle(Particle.SPLASH, point, 2, 0.15D, 0.05D, 0.15D, 0.01D);
                if (i % 2 == 0) {
                    world.spawnParticle(Particle.CLOUD, point, 1, 0.1D, 0.05D, 0.1D, 0.005D);
                }
            }
            if (ticksLeft % 4 == 0) {
                world.playSound(center, Sound.ENTITY_WARDEN_STEP, 0.5F, 0.7F);
            }
        }
    }
}
