package com.tidalsurger.core;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.config.Messages;
import com.tidalsurger.config.PluginSettings;
import com.tidalsurger.util.BlockSnapshot;
import com.tidalsurger.util.BlockUtils;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * One active Tidal Core.
 *
 * <p>Life cycle:</p>
 * <ol>
 *   <li><b>HOVER</b> - a translucent blue water cube materialises in the air,
 *       {@code hover-height} blocks above the target's head, and hangs there for
 *       {@code hover-ticks} (2.5 s by default). While it hangs it keeps itself
 *       centred on a moving target, so a victim cannot simply jog out from under
 *       it.</li>
 *   <li><b>FALL</b> - the whole cube descends all the way down until it reaches
 *       its landing surface: the ground below it, another player, a mob that is
 *       aggroed to the caster, or the entity the caster aimed at. Its floor
 *       comes to rest {@code landing-offset} blocks below that surface, so the
 *       core never stops in mid-air where the crosshair happened to point.</li>
 *   <li><b>SLAM</b> - the moment the cube hits the ground the landing shockwave
 *       fires: the ring of ground blocks around the impact is launched into the
 *       air, entities in the blast are thrown up and take one hit of slam damage,
 *       and the ground shakes. Over open water there is no ground to launch and
 *       the impact stays a splash.</li>
 *   <li><b>CONTAIN</b> - for {@code duration-ticks} (5 s) the victims are sealed
 *       inside: they swim freely but are pushed back at every boundary, every
 *       teleport out of the cube is refused, and anyone entering the water is
 *       trapped as well - the barrier cannot be walked, swum or teleported out of.
 *       During those five seconds they genuinely drown ({@code drain-breath-bar}:
 *       the air meter empties and bubbles burst) and take {@code total-damage}
 *       (3 hearts by default) of {@code minecraft:drown} damage, applied directly
 *       so water breathing, respiration and armour cannot change it. Natural
 *       regeneration is paused while sealed, so the three hearts actually stay
 *       lost for the whole window.</li>
 * </ol>
 *
 * <p>The world is never permanently changed: only passable, non-tile-entity
 * blocks are ever swapped for water, every change is snapshotted and restored
 * byte-for-byte, and the cube's owner is never trapped or slowed down.</p>
 */
public final class WaterCube {

    /** How far inside the outer face the invisible barrier keeps victims. */
    private static final double MARGIN = 0.32D;

    /** The three stages of the ability. */
    public enum Phase {
        HOVER, FALL, CONTAIN
    }

    private final TidalSurgerPlugin plugin;
    private final Messages messages;
    private final PluginSettings.Core config;
    private final UUID ownerId;
    private final World world;
    private final int size;
    private final int height;
    private final int durationTicks;
    private final UUID primaryTargetId;

    /** Top-left-bottom corner of the cube; moves while it falls and tracks. */
    private int minX;
    private int minY;
    private int minZ;
    /** Floor height the cube drops to when nothing stops it first (scanned as the fall starts). */
    private int fallFloor;
    /** The entity whose surface stopped the descent, named for the debug log. */
    private String landingEntityName;

    private final Set<UUID> trapped = new LinkedHashSet<>();
    private final ArrayDeque<BlockSnapshot> placed = new ArrayDeque<>();
    private final Set<Long> placedKeys = new HashSet<>();
    private final Random random = new Random();

    private Phase phase = Phase.HOVER;
    private int phaseTicks;
    private int containTicks;
    private int ticks;
    private int blocksPlaced;
    private boolean containmentActive;
    private boolean destroyed;
    private boolean ownerEffectsApplied;
    private DamageSource damageSource;

    public WaterCube(TidalSurgerPlugin plugin, UUID ownerId, World world, Location anchor, Entity primaryTarget,
                     PluginSettings.Core config) {
        this.plugin = plugin;
        this.messages = new Messages(plugin);
        this.config = config;
        this.ownerId = ownerId;
        this.world = world;
        this.size = config.size;
        this.height = config.height;
        this.durationTicks = config.durationTicks;
        this.primaryTargetId = primaryTarget == null ? null : primaryTarget.getUniqueId();

        int half = size / 2;
        this.minX = anchor.getBlockX() - half;
        this.minZ = anchor.getBlockZ() - half;
        this.minY = clampTop(clampFloor(anchor.getBlockY() - config.landingOffset) + config.hoverHeight);
    }

    // ---------------------------------------------------------------- geometry

    public int minX() {
        return minX;
    }

    public int minY() {
        return minY;
    }

    public int minZ() {
        return minZ;
    }

    public int maxX() {
        return minX + size - 1;
    }

    public int maxY() {
        return minY + height - 1;
    }

    public int maxZ() {
        return minZ + size - 1;
    }

    public Phase phase() {
        return phase;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public int trappedCount() {
        return trapped.size();
    }

    public boolean isDestroyed() {
        return destroyed;
    }

    public long remainingMillis() {
        return Math.max(0L, (long) (durationTicks - containTicks) * 50L);
    }

    public Location centerLocation() {
        return new Location(world, (minX + maxX() + 1) / 2.0D, (minY + maxY() + 1) / 2.0D, (minZ + maxZ() + 1) / 2.0D);
    }

    public String describe() {
        return world.getName() + "[" + minX + "," + minY + "," + minZ + "->" + maxX() + "," + maxY() + "," + maxZ()
                + "] phase=" + phase.name().toLowerCase(Locale.ROOT) + " trapped=" + trapped.size();
    }

    /** The floor of the cube may not leave the world. */
    private int clampFloor(int y) {
        int lowest = world.getMinHeight();
        int highest = world.getMaxHeight() - height;
        return Math.max(lowest, Math.min(y, highest));
    }

    /** The cube may not stick out of the top of the world while hovering. */
    private int clampTop(int y) {
        int highest = world.getMaxHeight() - height;
        return Math.max(world.getMinHeight(), Math.min(y, highest));
    }

    // ------------------------------------------------------------------ build

    /** Places the temporary water for the cube's current position. */
    public void build() {
        BlockData water = Material.WATER.createBlockData();
        Set<Material> skip = config.skipMaterials;

        for (int x = minX; x <= maxX(); x++) {
            for (int z = minZ; z <= maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    world.getChunkAt(x >> 4, z >> 4);
                }
                for (int y = minY; y <= maxY(); y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!BlockUtils.isReplaceable(block, skip)) {
                        continue;
                    }
                    placedKeys.add(BlockUtils.key(x, y, z));
                    placed.addLast(new BlockSnapshot(block, block.getBlockData().clone(), 0));
                    block.setBlockData(water, false);
                    blocksPlaced++;
                }
            }
        }
        if (plugin.settings().debug) {
            plugin.getLogger().info("[debug] Water cube @" + describe() + " (" + blocksPlaced + " temporary water blocks)");
        }
    }

    /**
     * Moves the whole cube (falling, or following a target): every block the cube
     * previously touched is restored and then the water is laid down again at the
     * new position.
     */
    public void moveTo(int newMinX, int newMinY, int newMinZ) {
        if (newMinX == minX && newMinY == minY && newMinZ == minZ) {
            return;
        }
        restoreAll();
        this.minX = newMinX;
        this.minY = newMinY;
        this.minZ = newMinZ;
        build();
    }

    private void restoreAll() {
        while (!placed.isEmpty()) {
            placed.pollFirst().restore(true);
        }
        placedKeys.clear();
    }

    // ------------------------------------------------------------------- tick

    /** @return true while the cube should keep running. */
    public boolean tick() {
        if (destroyed) {
            return false;
        }
        ticks++;
        switch (phase) {
            case HOVER -> tickHover();
            case FALL -> tickFall();
            case CONTAIN -> tickContain();
        }
        if (!destroyed && ticks % config.renderInterval == 0) {
            render();
        }
        return !destroyed;
    }

    /** Stage 1: hang in the air above the target's head. */
    private void tickHover() {
        phaseTicks++;
        trackTarget();
        renderHoverDrips();
        if (phaseTicks >= config.hoverTicks) {
            phase = Phase.FALL;
            phaseTicks = 0;
            this.fallFloor = groundFloor();
            if (config.sounds) {
                world.playSound(centerLocation(), Sound.ITEM_BUCKET_EMPTY, 1.0F, 0.55F);
                world.playSound(centerLocation(), Sound.ENTITY_PLAYER_SPLASH, 1.0F, 0.6F);
            }
            Entity target = primaryTargetId == null ? null : Bukkit.getEntity(primaryTargetId);
            if (target instanceof Player victim) {
                messages.send(victim, "core-drop");
            }
        }
    }

    /** Keeps the hovering cube centred on the marked target. */
    private void trackTarget() {
        if (!config.hoverTracking || primaryTargetId == null) {
            return;
        }
        if (phaseTicks % config.reanchorIntervalTicks != 0) {
            return;
        }
        Entity target = Bukkit.getEntity(primaryTargetId);
        if (target == null || !target.isValid() || !world.equals(target.getWorld())) {
            return;
        }
        Location location = target.getLocation();
        double centerX = minX + size / 2.0D;
        double centerZ = minZ + size / 2.0D;
        int floor = clampFloor(location.getBlockY() - config.landingOffset);
        int hoverY = clampTop(floor + config.hoverHeight);
        if (hungryDrift(location, centerX, centerZ) && hoverY == minY) {
            return;
        }
        moveTo(location.getBlockX() - size / 2, hoverY, location.getBlockZ() - size / 2);
    }

    private boolean hungryDrift(Location location, double centerX, double centerZ) {
        double dx = location.getX() - centerX;
        double dz = location.getZ() - centerZ;
        return Math.sqrt(dx * dx + dz * dz) < config.reanchorThreshold;
    }

    /** Stage 2: descend until the cube reaches its landing surface. */
    private void tickFall() {
        phaseTicks++;
        if (phaseTicks % config.fallStepTicks != 0) {
            return;
        }
        trackDuringFall();
        int floor = landingFloor();
        if (minY <= floor) {
            land();
            return;
        }
        int step = Math.max(1, Math.min(8, (minY - floor + 3) / 4));
        moveTo(minX, Math.max(floor, minY - step), minZ);
        renderFallTrail();
        if (config.sounds && phaseTicks % (config.fallStepTicks * 2) == 0) {
            world.playSound(centerLocation(), Sound.ENTITY_PLAYER_SPLASH, 0.5F, 1.4F);
        }
        if (minY <= floor) {
            land();
        }
    }

    /**
     * The highest solid ground under the cube's footprint, scanned once as the
     * fall starts. The cube comes to rest {@code landing-offset} blocks below it,
     * so it genuinely reaches the ground instead of stopping at the aim point.
     */
    private int groundFloor() {
        int surface = world.getMinHeight();
        for (int x = minX; x <= maxX(); x++) {
            for (int z = minZ; z <= maxZ(); z++) {
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = maxY() + 1; y >= world.getMinHeight(); y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!block.getType().isAir() && block.getType().isSolid() && !block.isLiquid()) {
                        surface = Math.max(surface, y + 1);
                        break;
                    }
                }
            }
        }
        return clampFloor(surface - config.landingOffset);
    }

    /**
     * The floor the cube is dropping towards right now: the ground under it, or the
     * feet of the highest worthwhile target in the drop column - another player, the
     * entity the caster aimed at, or a mob aggroed to the caster. Targets above the
     * cube are ignored, so nothing drags the cube back up.
     */
    private int landingFloor() {
        int floor = fallFloor;
        landingEntityName = null;
        double bottom = Math.min(fallFloor, minY) - 1.0D;
        double top = maxY() + 1.0D;
        Location center = new Location(world, (minX + maxX() + 1) / 2.0D, (bottom + top) / 2.0D,
                (minZ + maxZ() + 1) / 2.0D);
        double radius = size / 2.0D + 1.0D;
        for (Entity entity : world.getNearbyEntities(center, radius, (top - bottom) / 2.0D, radius)) {
            if (!isLandingTarget(entity)) {
                continue;
            }
            int target = clampFloor(entity.getLocation().getBlockY() - config.landingOffset);
            if (target <= minY && target > floor) {
                floor = target;
                landingEntityName = entity.getName() + " (" + entity.getType() + ")";
            }
        }
        return floor;
    }

    /** Anyone worth landing on: another player, the aimed entity, or a mob aggroed to the caster. */
    private boolean isLandingTarget(Entity entity) {
        if (!entity.isValid() || entity.getUniqueId().equals(ownerId)) {
            return false;
        }
        if (entity instanceof ArmorStand) {
            return false;
        }
        if (entity instanceof Player player) {
            return config.trapPlayers
                    && player.getGameMode() != GameMode.SPECTATOR
                    && !player.hasPermission("tidalsurger.bypass.trap");
        }
        if (!config.trapMobs) {
            return false;
        }
        if (entity.getUniqueId().equals(primaryTargetId)) {
            return true;
        }
        return entity instanceof Mob mob && mob.getTarget() != null
                && mob.getTarget().getUniqueId().equals(ownerId);
    }

    /** Keeps the descending cube over the marked target, without changing its height. */
    private void trackDuringFall() {
        if (!config.hoverTracking || primaryTargetId == null) {
            return;
        }
        if (phaseTicks % config.reanchorIntervalTicks != 0) {
            return;
        }
        Entity target = Bukkit.getEntity(primaryTargetId);
        if (target == null || !target.isValid() || !world.equals(target.getWorld())) {
            return;
        }
        Location location = target.getLocation();
        double centerX = minX + size / 2.0D;
        double centerZ = minZ + size / 2.0D;
        if (hungryDrift(location, centerX, centerZ)) {
            return;
        }
        moveTo(location.getBlockX() - size / 2, minY, location.getBlockZ() - size / 2);
    }

    /** Stage 3: seal the victims in and drown them for the configured duration. */
    private void tickContain() {
        containTicks++;
        if (containTicks > durationTicks) {
            destroy();
            return;
        }
        sweepEnterers();
        containVictims();
        applyOwnerEffects();
        applyDrownDamage();
    }

    private void land() {
        phase = Phase.CONTAIN;
        phaseTicks = 0;
        containTicks = 0;
        containmentActive = true;
        trapInitialEntities();

        Location center = centerLocation();
        if (config.sounds) {
            world.playSound(center, Sound.ENTITY_GENERIC_SPLASH, 1.1F, 0.7F);
            world.playSound(center, Sound.BLOCK_CONDUIT_ATTACK_TARGET, 0.9F, 0.8F);
        }
        world.spawnParticle(Particle.SPLASH, center, 140, size / 2.0D, 0.4D, size / 2.0D, 0.12D);
        world.spawnParticle(Particle.CLOUD, center, 60, size / 2.0D, 0.2D, size / 2.0D, 0.05D);

        // The landing slam: launched edge blocks, blast damage and the shaking ground.
        Location impact = new Location(world, (minX + maxX() + 1) / 2.0D, minY, (minZ + maxZ() + 1) / 2.0D);
        plugin.coreManager().shockwave().impact(world, impact, Bukkit.getPlayer(ownerId));

        if (plugin.settings().debug) {
            plugin.getLogger().info("[debug] Water cube landed: " + describe()
                    + (landingEntityName == null ? " (ground)" : " on " + landingEntityName));
        }
    }

    // ------------------------------------------------------------- containment

    private void trapInitialEntities() {
        Entity primary = primaryTargetId == null ? null : Bukkit.getEntity(primaryTargetId);
        if (primary != null) {
            trapEntity(primary);
        }
        sweepEnterers();
    }

    /**
     * Seals every living entity inside the water: whoever was caught when it
     * landed and whoever swims, walks or teleports in later. Being in the water
     * is the trap, so simply entering it is not an escape route.
     */
    private void sweepEnterers() {
        if (!config.trapAllInside) {
            return;
        }
        double half = size / 2.0D + 1.0D;
        for (Entity entity : world.getNearbyEntities(centerLocation(), half, height / 2.0D + 1.0D, half)) {
            if (!trapped.contains(entity.getUniqueId()) && containsPoint(entity.getLocation())) {
                trapEntity(entity);
            }
        }
    }

    public void trapEntity(Entity entity) {
        if (!(entity instanceof LivingEntity)) {
            return;
        }
        if (entity.getUniqueId().equals(ownerId)) {
            return;
        }
        if (entity instanceof Player player) {
            if (!config.trapPlayers || player.getGameMode() == GameMode.SPECTATOR
                    || player.hasPermission("tidalsurger.bypass.trap")) {
                return;
            }
        } else if (!config.trapMobs) {
            return;
        }
        if (!trapped.add(entity.getUniqueId())) {
            return;
        }
        entity.setFallDistance(0.0F);
        if (entity instanceof Player player) {
            messages.send(player, "core-trap");
            messages.send(player, "core-trap-sub", "seconds", String.valueOf(durationTicks / 20));
            player.playSound(player.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 0.7F, 1.4F);
        }
    }

    public boolean isTrapping(Entity entity) {
        return containmentActive && trapped.contains(entity.getUniqueId());
    }

    /**
     * Keeps every trapped entity inside the cube. Being inside is not physical -
     * the cube is water - so anyone crossing the boundary is pushed straight back
     * in, which is what makes the barrier feel solid for victims only.
     */
    private void containVictims() {
        Iterator<UUID> iterator = trapped.iterator();
        while (iterator.hasNext()) {
            UUID id = iterator.next();
            Entity entity = Bukkit.getEntity(id);
            if (entity == null) {
                // Offline or unloaded: keep them trapped, relogging is not an escape.
                continue;
            }
            if (!entity.isValid() || entity.isDead()) {
                // Death is a release - never drag a respawned player back into the water.
                iterator.remove();
                continue;
            }
            if (!world.equals(entity.getWorld())) {
                continue;
            }
            if (entity instanceof LivingEntity living) {
                living.setFallDistance(0.0F);
                if (config.drainBreathBar) {
                    // Real drowning: the breath bar actually empties while they are sealed.
                    living.setRemainingAir(0);
                }
            }
            Location location = entity.getLocation();
            if (isWithinBounds(location)) {
                continue;
            }
            Location safe = clampInside(location);
            if (entity instanceof Player player) {
                plugin.coreManager().teleportInternally(player, safe);
                messages.send(player, "cannot-escape");
            } else {
                entity.teleport(safe);
            }
            entity.setVelocity(new Vector(0.0D, 0.12D, 0.0D));
            world.spawnParticle(Particle.SPLASH, location, 16, 0.35D, 0.35D, 0.35D, 0.05D);
            world.playSound(location, Sound.ENTITY_PLAYER_SPLASH, 0.65F, 1.6F);
        }
    }

    /**
     * Fixed drowning damage spread over the containment window, applied directly
     * so the victim's breath bar, water breathing, respiration, armour and
     * invulnerability frames cannot reduce or delay it.
     */
    private void applyDrownDamage() {
        if (config.totalDamage <= 0.0D || config.damageIntervalTicks <= 0) {
            return;
        }
        if (containTicks % config.damageIntervalTicks != 0) {
            return;
        }
        int hits = Math.max(1, durationTicks / config.damageIntervalTicks);
        double perHit = config.totalDamage / hits;
        plugin.coreManager().beginCoreDamage();
        try {
            for (UUID id : trapped) {
                Entity entity = Bukkit.getEntity(id);
                if (!(entity instanceof LivingEntity living) || !living.isValid() || living.isDead()) {
                    continue;
                }
                living.setNoDamageTicks(0);
                living.damage(perHit, damageSource());
                world.spawnParticle(Particle.BUBBLE, living.getLocation().add(0.0D, 1.0D, 0.0D), 12, 0.35D, 0.45D, 0.35D, 0.03D);
                world.playSound(living.getLocation(), Sound.ENTITY_PLAYER_HURT_DROWN, 0.7F, 1.1F);
            }
        } finally {
            plugin.coreManager().endCoreDamage();
        }
    }

    private DamageSource damageSource() {
        if (damageSource == null) {
            DamageSource.Builder builder = DamageSource.builder(DamageType.DROWN);
            Player owner = config.damageCreditOwner ? Bukkit.getPlayer(ownerId) : null;
            if (owner != null) {
                // The damage type stays minecraft:drown ("drowned whilst trying to escape ..."),
                // while the kill is credited to whoever cast the cube.
                builder.withCausingEntity(owner);
                builder.withDirectEntity(owner);
            }
            damageSource = builder.build();
        }
        return damageSource;
    }

    private void applyOwnerEffects() {
        if (!config.ownerWaterBreathing) {
            return;
        }
        Player owner = Bukkit.getPlayer(ownerId);
        if (owner == null || !owner.isOnline()) {
            return;
        }
        if (!world.equals(owner.getWorld()) || !containsPoint(owner.getLocation())) {
            if (ownerEffectsApplied) {
                removeOwnerEffects(owner);
            }
            return;
        }
        owner.addPotionEffect(new PotionEffect(PotionEffectType.WATER_BREATHING, 80, 0, true, false, false));
        owner.addPotionEffect(new PotionEffect(PotionEffectType.DOLPHINS_GRACE, 80, 0, true, false, false));
        ownerEffectsApplied = true;
    }

    private void removeOwnerEffects(Player owner) {
        ownerEffectsApplied = false;
        owner.removePotionEffect(PotionEffectType.WATER_BREATHING);
        owner.removePotionEffect(PotionEffectType.DOLPHINS_GRACE);
    }

    // --------------------------------------------------------------- visuals

    /**
     * Draws the twelve dark-blue edges, aqua corner bursts, an inner aqua swirl
     * and ambient water particles.
     */
    private void render() {
        double[][] range = {{minX, maxX()}, {minY, maxY()}, {minZ, maxZ()}};
        double[] position = new double[3];

        for (int axis = 0; axis < 3; axis++) {
            int otherA = (axis + 1) % 3;
            int otherB = (axis + 2) % 3;
            for (int i = 0; i < 2; i++) {
                for (int j = 0; j < 2; j++) {
                    double fixedA = range[otherA][i] + (i == 0 ? 0.0D : 1.0D);
                    double fixedB = range[otherB][j] + (j == 0 ? 0.0D : 1.0D);
                    double from = range[axis][0];
                    double to = range[axis][1] + 1.0D;
                    int index = 0;
                    for (double step = from; step <= to + 1.0E-3D; step += config.edgeStep) {
                        position[axis] = step;
                        position[otherA] = fixedA;
                        position[otherB] = fixedB;
                        Particle particle = config.edgeParticles.get(index % config.edgeParticles.size());
                        world.spawnParticle(particle, position[0], position[1], position[2], 1, 0.0D, 0.0D, 0.0D, 0.0D);
                        index++;
                    }
                }
            }
        }

        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < 2; j++) {
                for (int k = 0; k < 2; k++) {
                    double x = (i == 0 ? minX : maxX() + 1.0D);
                    double y = (j == 0 ? minY : maxY() + 1.0D);
                    double z = (k == 0 ? minZ : maxZ() + 1.0D);
                    Particle particle = config.accentParticles.get((i + j + k) % config.accentParticles.size());
                    world.spawnParticle(particle, x, y, z, 5, 0.12D, 0.12D, 0.12D, 0.01D);
                }
            }
        }

        double centerX = (minX + maxX() + 1) / 2.0D;
        double centerZ = (minZ + maxZ() + 1) / 2.0D;
        double radius = Math.max(1.0D, size / 2.0D - 1.0D);
        int swirlCount = 16;
        for (int i = 0; i < swirlCount; i++) {
            double progress = ((i / (double) swirlCount) + ticks * 0.02D) % 1.0D;
            double angle = progress * Math.PI * 4.0D + ticks * 0.12D;
            double x = centerX + Math.cos(angle) * radius;
            double z = centerZ + Math.sin(angle) * radius;
            double y = minY + 0.5D + progress * (height - 1);
            Particle particle = config.accentParticles.get(i % config.accentParticles.size());
            world.spawnParticle(particle, x, y, z, 1, 0.05D, 0.05D, 0.05D, 0.0D);
        }

        for (int i = 0; i < 7; i++) {
            double x = minX + random.nextDouble() * size;
            double y = minY + random.nextDouble() * height;
            double z = minZ + random.nextDouble() * size;
            Particle particle = config.ambientParticles.get(i % config.ambientParticles.size());
            world.spawnParticle(particle, x, y, z, 2, 0.35D, 0.35D, 0.35D, 0.0D);
        }

        double top = maxY() + 1.0D;
        world.spawnParticle(Particle.SPLASH, (minX + maxX() + 1) / 2.0D, top, (minZ + maxZ() + 1) / 2.0D,
                22, size / 2.0D, 0.1D, size / 2.0D, 0.02D);
        world.spawnParticle(Particle.BUBBLE_COLUMN_UP, (minX + maxX() + 1) / 2.0D, minY + 0.2D, (minZ + maxZ() + 1) / 2.0D,
                10, size / 3.0D, 0.1D, size / 3.0D, 0.01D);
    }

    /** Water dripping from the underside while the cube hangs in the air. */
    private void renderHoverDrips() {
        for (int i = 0; i < 6; i++) {
            double x = minX + random.nextDouble() * size;
            double z = minZ + random.nextDouble() * size;
            world.spawnParticle(Particle.FALLING_WATER, x, minY - 0.15D, z, 2, 0.1D, 0.0D, 0.1D, 0.0D);
        }
        world.spawnParticle(Particle.DRIPPING_WATER, (minX + maxX() + 1) / 2.0D, minY - 0.2D,
                (minZ + maxZ() + 1) / 2.0D, 12, size / 2.5D, 0.0D, size / 2.5D, 0.0D);
    }

    /** Spray thrown out by the cube as it plummets. */
    private void renderFallTrail() {
        world.spawnParticle(Particle.SPLASH, (minX + maxX() + 1) / 2.0D, minY + 0.1D, (minZ + maxZ() + 1) / 2.0D,
                40, size / 2.0D, 0.1D, size / 2.0D, 0.08D);
        world.spawnParticle(Particle.CLOUD, (minX + maxX() + 1) / 2.0D, minY + height, (minZ + maxZ() + 1) / 2.0D,
                25, size / 2.0D, 0.2D, size / 2.0D, 0.02D);
    }

    // ---------------------------------------------------------------- destroy

    /**
     * Restores every changed block, releases the victims safely and clears the
     * owner's water effects. Safe to call more than once.
     */
    public void destroy() {
        if (destroyed) {
            return;
        }
        destroyed = true;

        restoreAll();

        for (UUID id : trapped) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                continue;
            }
            entity.setFallDistance(0.0F);
            entity.setVelocity(new Vector(0.0D, 0.0D, 0.0D));
            if (entity instanceof LivingEntity living) {
                // The sealed window drained their breath bar, so give it back: a released
                // victim must not keep drowning after the ability is over.
                living.setRemainingAir(living.getMaximumAir());
                if (!living.isOnGround()) {
                    // Released safely: the water sets them down instead of dropping them, so
                    // the ability's drown damage is the only damage a victim takes.
                    living.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 100, 0, true, false, false));
                }
            }
            world.spawnParticle(Particle.SPLASH, entity.getLocation(), 24, 0.6D, 0.6D, 0.6D, 0.06D);
            if (entity instanceof Player player) {
                messages.send(player, "core-released");
                player.playSound(player.getLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, 0.8F, 1.3F);
            }
        }
        trapped.clear();

        Player owner = Bukkit.getPlayer(ownerId);
        if (owner != null && ownerEffectsApplied) {
            removeOwnerEffects(owner);
        }
        world.playSound(centerLocation(), Sound.BLOCK_CONDUIT_DEACTIVATE, 1.0F, 0.8F);
        world.playSound(centerLocation(), Sound.ENTITY_PLAYER_SPLASH, 1.0F, 0.6F);
    }

    // --------------------------------------------------------------- queries

    /** Loose check: is the point anywhere in the cube's block box? */
    public boolean containsPoint(Location location) {
        if (location == null || !world.equals(location.getWorld())) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= minX - 0.05D && x <= maxX() + 1.05D
                && y >= minY - 0.05D && y <= maxY() + 1.05D
                && z >= minZ - 0.05D && z <= maxZ() + 1.05D;
    }

    /** Strict check: is the point still inside the invisible barrier? */
    public boolean isWithinBounds(Location location) {
        if (location == null || !world.equals(location.getWorld())) {
            return false;
        }
        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();
        return x >= minX + MARGIN && x <= maxX() + 1.0D - MARGIN
                && y >= minY + MARGIN && y <= maxY() + 1.0D - MARGIN
                && z >= minZ + MARGIN && z <= maxZ() + 1.0D - MARGIN;
    }

    public Location clampInside(Location location) {
        Location safe = location.clone();
        safe.setX(clamp(location.getX(), minX + MARGIN, maxX() + 1.0D - MARGIN));
        safe.setY(clamp(location.getY(), minY + MARGIN, maxY() + 1.0D - MARGIN));
        safe.setZ(clamp(location.getZ(), minZ + MARGIN, maxZ() + 1.0D - MARGIN));
        return safe;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
