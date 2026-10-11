package com.tidalsurger.surf;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.config.Messages;
import com.tidalsurger.config.PluginSettings;
import com.tidalsurger.util.BlockSnapshot;
import com.tidalsurger.util.BlockUtils;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * <b>Tidal Surf</b> - the offhand-key ability.
 *
 * <p>Raises a flowing wave under the caster and carries them forward for about
 * five seconds. The wave is built from real temporary water that is restored to
 * its original state as it fades, plus blue splash/dolphin particles and foam.
 * Fall damage is suppressed for the whole ride and for a short grace period
 * afterwards.</p>
 */
public final class TidalSurfManager {

    private final TidalSurgerPlugin plugin;
    private final Messages messages;
    private final Map<UUID, SurfSession> sessions = new HashMap<>();
    private final Map<UUID, Long> fallImmuneUntil = new HashMap<>();
    private final Random random = new Random();

    private BukkitTask task;
    private int tick;

    public TidalSurfManager(TidalSurgerPlugin plugin) {
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
        for (SurfSession session : new ArrayList<>(sessions.values())) {
            session.restoreAll();
        }
        sessions.clear();
    }

    public boolean isSurfing(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    public boolean hasFallImmunity(Player player) {
        Long until = fallImmuneUntil.get(player.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    public void clear(Player player) {
        SurfSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            session.restoreAll();
        }
        fallImmuneUntil.remove(player.getUniqueId());
    }

    /**
     * @return true when the ride started (the caller then applies the cooldown).
     */
    public boolean begin(Player player) {
        if (sessions.containsKey(player.getUniqueId())) {
            messages.send(player, "already-surfing");
            return false;
        }

        PluginSettings.Surf config = plugin.settings().surf;
        World world = player.getWorld();
        Location origin = player.getLocation();

        Vector direction = horizontal(origin.getDirection(), new Vector(0.0D, 0.0D, 1.0D));
        sessions.put(player.getUniqueId(), new SurfSession(config.durationTicks, direction));
        player.setFallDistance(0.0F);

        if (config.sounds) {
            world.playSound(origin, Sound.ITEM_TRIDENT_RIPTIDE_1, 1.1F, 1.15F);
            world.playSound(origin, Sound.ENTITY_PLAYER_SPLASH, 1.0F, 0.9F);
        }
        world.spawnParticle(Particle.SPLASH, origin, 50, 1.3D, 0.35D, 1.3D, 0.06D);
        world.spawnParticle(Particle.DOLPHIN, origin, 25, 1.1D, 0.25D, 1.1D, 0.02D);
        world.spawnParticle(Particle.BUBBLE, origin, 20, 0.9D, 0.2D, 0.9D, 0.02D);

        messages.send(player, "surf-start");
        if (plugin.settings().debug) {
            plugin.getLogger().info("[debug] Tidal Surf started for " + player.getName());
        }
        return true;
    }

    private void tick() {
        this.tick++;
        if (!sessions.isEmpty()) {
            Iterator<Map.Entry<UUID, SurfSession>> iterator = sessions.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<UUID, SurfSession> entry = iterator.next();
                SurfSession session = entry.getValue();
                Player player = Bukkit.getPlayer(entry.getKey());

                if (player == null || !player.isOnline() || player.isDead()) {
                    session.restoreAll();
                    iterator.remove();
                    continue;
                }
                session.ticksLeft--;
                if (session.ticksLeft <= 0) {
                    end(player, session);
                    iterator.remove();
                    continue;
                }
                apply(player, session);
            }
        }
        fallImmuneUntil.entrySet().removeIf(node -> node.getValue() + 20_000L < System.currentTimeMillis());
    }

    private void end(Player player, SurfSession session) {
        PluginSettings.Surf config = plugin.settings().surf;
        session.restoreAll();
        player.setFallDistance(0.0F);
        fallImmuneUntil.put(player.getUniqueId(), System.currentTimeMillis() + config.fallImmunityTicks * 50L);
        if (config.sounds) {
            player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_SPLASH, 0.8F, 0.7F);
        }
    }

    private void apply(Player player, SurfSession session) {
        PluginSettings.Surf config = plugin.settings().surf;
        World world = player.getWorld();
        Location location = player.getLocation();

        Vector direction = horizontal(location.getDirection(), session.direction);
        session.direction = direction;
        Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX());

        Vector velocity = direction.clone().multiply(config.speed);
        double vertical;
        if (player.isOnGround()) {
            // Skimming the surface: keep the crest raised just above the ground.
            vertical = config.lift;
        } else {
            vertical = clamp(player.getVelocity().getY(), config.maxFall, config.maxRise);
        }
        velocity.setY(vertical);
        player.setVelocity(velocity);
        player.setFallDistance(0.0F);

        renderWave(world, location, direction, side, config);
        if (config.placeWater) {
            placeWaveWater(session, world, location, direction, side, config);
            restoreFaded(session, config.waterFadeTicks);
        }
        if (config.sounds && tick % 14 == 0) {
            world.playSound(location, Sound.BLOCK_WATER_AMBIENT, 0.45F, 1.5F);
        }
    }

    private void renderWave(World world, Location base, Vector direction, Vector side, PluginSettings.Surf config) {
        double width = config.waveWidth + 1.0D;
        int points = 9;
        for (int i = 0; i < points; i++) {
            double ratio = (i / (double) (points - 1)) * 2.0D - 1.0D;
            double forward = 1.15D - Math.abs(ratio) * 0.45D;
            Location point = base.clone()
                    .add(side.clone().multiply(ratio * width))
                    .add(direction.clone().multiply(forward))
                    .add(0.0D, 0.12D, 0.0D);
            world.spawnParticle(Particle.SPLASH, point, 2, 0.16D, 0.1D, 0.16D, 0.02D);
            if (i % 2 == 0) {
                world.spawnParticle(Particle.DOLPHIN, point, 1, 0.1D, 0.05D, 0.1D, 0.0D);
            }
        }

        Location stern = base.clone().subtract(direction.clone().multiply(1.2D));
        world.spawnParticle(Particle.CLOUD, stern, 5, 0.45D, 0.12D, 0.45D, 0.01D);
        world.spawnParticle(Particle.BUBBLE, base, 4, 0.5D, 0.25D, 0.5D, 0.02D);
        if (random.nextInt(3) == 0) {
            world.spawnParticle(Particle.FALLING_WATER, base.clone().add(0.0D, 0.4D, 0.0D), 3, 0.7D, 0.2D, 0.7D, 0.0D);
        }
    }

    /**
     * Replaces the air/plants under and around the surfer with temporary water
     * so the wave is physical, not only a particle effect.
     */
    private void placeWaveWater(SurfSession session, World world, Location base, Vector direction, Vector side, PluginSettings.Surf config) {
        int radius = config.waveWidth;
        Set<Material> skip = plugin.settings().core.skipMaterials;
        BlockData water = Material.WATER.createBlockData();

        for (int forward = -1; forward <= 1; forward++) {
            for (int across = -radius; across <= radius; across++) {
                int x = base.getBlockX() + (int) Math.round(direction.getX() * forward + side.getX() * across);
                int z = base.getBlockZ() + (int) Math.round(direction.getZ() * forward + side.getZ() * across);
                for (int layer = 0; layer <= 1; layer++) {
                    int y = base.getBlockY() - layer;
                    if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                        continue;
                    }
                    Block block = world.getBlockAt(x, y, z);
                    if (!BlockUtils.isReplaceable(block, skip)) {
                        continue;
                    }
                    long key = BlockUtils.key(x, y, z);
                    if (!session.placedKeys.add(key)) {
                        continue;
                    }
                    session.placed.addLast(new BlockSnapshot(block, block.getBlockData().clone(), tick));
                    block.setBlockData(water, false);
                }
            }
        }
    }

    private void restoreFaded(SurfSession session, int fadeTicks) {
        while (!session.placed.isEmpty()) {
            BlockSnapshot snapshot = session.placed.peekFirst();
            if (tick - snapshot.placedTick() < fadeTicks) {
                break;
            }
            session.placed.pollFirst();
            Block block = snapshot.block();
            session.placedKeys.remove(BlockUtils.key(block.getX(), block.getY(), block.getZ()));
            snapshot.restore(true);
        }
    }

    private static Vector horizontal(Vector input, Vector fallback) {
        Vector flat = input.clone();
        flat.setY(0.0D);
        if (flat.lengthSquared() < 1.0E-6D) {
            return fallback.clone().normalize();
        }
        return flat.normalize();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** One in-flight ride. */
    private static final class SurfSession {

        private final Set<Long> placedKeys = new HashSet<>();
        private final ArrayDeque<BlockSnapshot> placed = new ArrayDeque<>();
        private int ticksLeft;
        private Vector direction;

        SurfSession(int durationTicks, Vector direction) {
            this.ticksLeft = durationTicks;
            this.direction = direction.clone();
        }

        void restoreAll() {
            while (!placed.isEmpty()) {
                placed.pollFirst().restore(true);
            }
            placedKeys.clear();
        }
    }
}
