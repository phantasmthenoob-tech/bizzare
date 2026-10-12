package com.tidalsurger.config;

import com.tidalsurger.ability.AbilityType;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Immutable snapshot of config.yml. A reload simply builds a new instance, so a
 * running ability always reads a consistent set of values.
 */
public final class PluginSettings {

    public final boolean debug;
    public final Input input;
    public final Surf surf;
    public final Core core;
    public final CooldownBar cooldownBar;
    public final int maxConcurrentCubesPerPlayer;
    public final int maxConcurrentCubesTotal;

    public PluginSettings(FileConfiguration config) {
        this.debug = config.getBoolean("general.debug", false);
        this.input = new Input(config);
        this.surf = new Surf(config);
        this.core = new Core(config);
        this.cooldownBar = new CooldownBar(config);
        this.maxConcurrentCubesPerPlayer = Math.max(1, config.getInt("abilities.max-concurrent-cubes-per-player", 1));
        this.maxConcurrentCubesTotal = Math.max(1, config.getInt("abilities.max-concurrent-cubes-total", 24));
    }

    public AbilityConfig ability(AbilityType type) {
        return type == AbilityType.TIDAL_SURF
                ? new AbilityConfig(surf.enabled, surf.permission, surf.cooldownSeconds)
                : new AbilityConfig(core.enabled, core.permission, core.cooldownSeconds);
    }

    /**
     * Shared view of one ability's enable flag, permission and cooldown.
     */
    public record AbilityConfig(boolean enabled, String permission, int cooldownSeconds) {
    }

    /** Offhand-key detection options. */
    public static final class Input {
        public final boolean cancelVanillaSwap;
        public final long sneakGraceMillis;
        public final boolean requireItemInMainHand;
        public final boolean rightClickFallback;
        public final boolean showItemCooldown;

        Input(FileConfiguration config) {
            this.cancelVanillaSwap = config.getBoolean("input.cancel-vanilla-swap", true);
            this.sneakGraceMillis = Math.max(0L, config.getLong("input.sneak-grace-millis", 350L));
            this.requireItemInMainHand = config.getBoolean("input.require-item-in-main-hand", true);
            this.rightClickFallback = config.getBoolean("input.right-click-fallback", false);
            this.showItemCooldown = config.getBoolean("input.show-item-cooldown", true);
        }
    }

    /** Tidal Surf options. */
    public static final class Surf {
        public final boolean enabled;
        public final String permission;
        public final int cooldownSeconds;
        public final int durationTicks;
        public final double speed;
        public final double lift;
        public final double maxRise;
        public final double maxFall;
        public final double ascendSpeed;
        public final double descendSpeed;
        public final int waveWidth;
        public final int waterOffset;
        public final boolean placeWater;
        public final int waterFadeTicks;
        public final int fallImmunityTicks;
        public final boolean sounds;

        Surf(FileConfiguration config) {
            String base = "abilities.tidal-surf.";
            this.enabled = config.getBoolean(base + "enabled", true);
            this.permission = config.getString(base + "permission", AbilityType.TIDAL_SURF.defaultPermission());
            this.cooldownSeconds = Math.max(0, config.getInt(base + "cooldown-seconds", 2));
            this.durationTicks = Math.max(20, config.getInt(base + "duration-ticks", 100));
            this.speed = clamp(config.getDouble(base + "speed", 0.78), 0.05, 3.0);
            this.lift = clamp(config.getDouble(base + "lift", 0.22), 0.0, 1.0);
            this.maxRise = clamp(config.getDouble(base + "max-rise", 0.45), 0.0, 2.0);
            this.maxFall = clamp(config.getDouble(base + "max-fall", -0.28), -2.0, 0.0);
            this.ascendSpeed = clamp(config.getDouble(base + "ascend-speed", 0.45), 0.0, 2.0);
            this.descendSpeed = clamp(config.getDouble(base + "descend-speed", -0.45), -2.0, 0.0);
            this.waveWidth = Math.max(0, Math.min(3, config.getInt(base + "wave-width", 1)));
            this.waterOffset = Math.max(0, Math.min(6, config.getInt(base + "water-offset", 3)));
            this.placeWater = config.getBoolean(base + "place-water", true);
            this.waterFadeTicks = Math.max(2, config.getInt(base + "water-fade-ticks", 14));
            this.fallImmunityTicks = Math.max(0, config.getInt(base + "fall-immunity-ticks", 45));
            this.sounds = config.getBoolean(base + "sounds", true);
        }
    }

    /** Tidal Core options. */
    public static final class Core {
        public final boolean enabled;
        public final String permission;
        public final int cooldownSeconds;
        public final int size;
        public final int height;
        public final int hoverHeight;
        public final int hoverTicks;
        public final int landingOffset;
        public final boolean hoverTracking;
        public final double reanchorThreshold;
        public final int reanchorIntervalTicks;
        public final int fallStepTicks;
        public final int durationTicks;
        public final double totalDamage;
        public final int damageIntervalTicks;
        public final boolean drainBreathBar;
        public final boolean suppressNaturalRegen;
        public final boolean damageCreditOwner;
        public final boolean sounds;
        public final int maxTargetDistance;
        public final boolean trapAllInside;
        public final boolean trapPlayers;
        public final boolean trapMobs;
        public final boolean blockEnderPearls;
        public final boolean blockTeleportCommands;
        public final boolean ownerWaterBreathing;
        public final int renderInterval;
        public final double edgeStep;
        public final List<Particle> edgeParticles;
        public final List<Particle> accentParticles;
        public final List<Particle> ambientParticles;
        public final Set<Material> skipMaterials;
        public final Shockwave shockwave;

        Core(FileConfiguration config) {
            String base = "abilities.tidal-core.";
            this.enabled = config.getBoolean(base + "enabled", true);
            this.permission = config.getString(base + "permission", AbilityType.TIDAL_CORE.defaultPermission());
            this.cooldownSeconds = Math.max(0, config.getInt(base + "cooldown-seconds", 4));
            this.size = Math.max(3, Math.min(24, config.getInt(base + "size", 10)));
            this.height = Math.max(1, Math.min(24, config.getInt(base + "height", 10)));
            this.hoverHeight = Math.max(0, Math.min(32, config.getInt(base + "hover-height", 4)));
            this.hoverTicks = Math.max(0, config.getInt(base + "hover-ticks", 50));
            this.landingOffset = Math.max(0, Math.min(8, config.getInt(base + "landing-offset", 2)));
            this.hoverTracking = config.getBoolean(base + "hover-tracking", true);
            this.reanchorThreshold = clamp(config.getDouble(base + "reanchor-threshold", 1.5D), 0.1D, 8.0D);
            this.reanchorIntervalTicks = Math.max(1, config.getInt(base + "reanchor-interval-ticks", 4));
            this.fallStepTicks = Math.max(1, config.getInt(base + "fall-step-ticks", 2));
            this.durationTicks = Math.max(20, config.getInt(base + "duration-ticks", 100));
            this.totalDamage = Math.max(0.0D, config.getDouble(base + "total-damage", 6.0D));
            this.damageIntervalTicks = Math.max(1, config.getInt(base + "damage-interval-ticks", 20));
            this.drainBreathBar = config.getBoolean(base + "drain-breath-bar", true);
            this.suppressNaturalRegen = config.getBoolean(base + "suppress-natural-regen", true);
            this.damageCreditOwner = config.getBoolean(base + "damage-credit-owner", true);
            this.sounds = config.getBoolean(base + "sounds", true);
            this.maxTargetDistance = Math.max(4, Math.min(120, config.getInt(base + "max-target-distance", 30)));
            this.trapAllInside = config.getBoolean(base + "trap-all-inside", true);
            this.trapPlayers = config.getBoolean(base + "trap-players", true);
            this.trapMobs = config.getBoolean(base + "trap-mobs", true);
            this.blockEnderPearls = config.getBoolean(base + "block-ender-pearls", true);
            this.blockTeleportCommands = config.getBoolean(base + "block-teleport-commands", true);
            this.ownerWaterBreathing = config.getBoolean(base + "owner-water-breathing", true);
            this.renderInterval = Math.max(1, config.getInt(base + "render-interval", 4));
            this.edgeStep = clamp(config.getDouble(base + "edge-step", 1.0), 0.35, 4.0);
            this.edgeParticles = particles(config, base + "edge-particles", List.of("SOUL_FIRE_FLAME", "SCULK_SOUL"), Particle.SOUL_FIRE_FLAME);
            this.accentParticles = particles(config, base + "accent-particles", List.of("DOLPHIN", "ELECTRIC_SPARK"), Particle.DOLPHIN);
            this.ambientParticles = particles(config, base + "ambient-particles", List.of("BUBBLE", "FALLING_WATER"), Particle.BUBBLE);
            this.skipMaterials = materials(config, base + "skip-materials",
                    List.of("WATER", "LAVA", "FIRE", "SOUL_FIRE", "NETHER_PORTAL", "END_PORTAL", "END_GATEWAY",
                            "BUDDING_AMETHYST", "SPAWNER", "BARRIER", "LIGHT", "STRUCTURE_VOID", "BEDROCK"));
            this.shockwave = new Shockwave(config, base + "shockwave.");
        }

        /**
         * The landing slam: ground shake, launched edge blocks, impact damage and
         * knock-up. All values are documented in config.yml.
         */
        public static final class Shockwave {
            public final boolean enabled;
            public final double radius;
            public final int blockCount;
            public final double blockLaunch;
            public final int blockLifetimeTicks;
            public final double damage;
            public final double launch;
            public final double knockback;
            public final double shake;
            public final int shakeTicks;
            public final int fallImmunityTicks;
            public final boolean effects;

            Shockwave(FileConfiguration config, String base) {
                this.enabled = config.getBoolean(base + "enabled", true);
                this.radius = clamp(config.getDouble(base + "radius", 6.0D), 1.0D, 24.0D);
                this.blockCount = Math.max(0, Math.min(96, config.getInt(base + "block-count", 32)));
                this.blockLaunch = clamp(config.getDouble(base + "block-launch", 0.55D), 0.1D, 2.0D);
                this.blockLifetimeTicks = Math.max(5, config.getInt(base + "block-lifetime-ticks", 30));
                this.damage = Math.max(0.0D, config.getDouble(base + "damage", 4.0D));
                this.launch = clamp(config.getDouble(base + "launch", 0.75D), 0.0D, 3.0D);
                this.knockback = clamp(config.getDouble(base + "knockback", 0.45D), 0.0D, 3.0D);
                this.shake = clamp(config.getDouble(base + "shake", 0.12D), 0.0D, 1.0D);
                this.shakeTicks = Math.max(0, config.getInt(base + "shake-ticks", 12));
                this.fallImmunityTicks = Math.max(0, config.getInt(base + "fall-immunity-ticks", 60));
                this.effects = config.getBoolean(base + "effects", true);
            }
        }
    }

    /**
     * The blue recharge BossBar shown at the top of the screen while an ability
     * is on cooldown.
     */
    public static final class CooldownBar {
        public final boolean enabled;
        public final BarColor color;
        public final BarStyle style;
        public final String title;
        public final String readyTitle;
        public final int readyLingerTicks;
        public final int updateIntervalTicks;

        CooldownBar(FileConfiguration config) {
            String base = "cooldown-bar.";
            this.enabled = config.getBoolean(base + "enabled", true);
            this.color = barColor(config.getString(base + "color", "BLUE"));
            this.style = barStyle(config.getString(base + "style", "SOLID"));
            this.title = config.getString(base + "title", "<aqua>%ability% <dark_gray>| <white>%seconds%s");
            this.readyTitle = config.getString(base + "ready-title", "<aqua>%ability% <green>ready");
            this.readyLingerTicks = Math.max(0, config.getInt(base + "ready-linger-ticks", 20));
            this.updateIntervalTicks = Math.max(1, config.getInt(base + "update-interval-ticks", 1));
        }
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static BarColor barColor(String name) {
        if (name != null) {
            try {
                return BarColor.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // fall through to the default below
            }
        }
        return BarColor.BLUE;
    }

    private static BarStyle barStyle(String name) {
        if (name != null) {
            try {
                return BarStyle.valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // fall through to the default below
            }
        }
        return BarStyle.SOLID;
    }

    private static List<Particle> particles(FileConfiguration config, String path, List<String> defaults, Particle fallback) {
        List<String> names = config.getStringList(path);
        if (names.isEmpty()) {
            names = defaults;
        }
        List<Particle> result = new ArrayList<>(names.size());
        for (String name : names) {
            Particle particle = parseParticle(name);
            if (particle != null) {
                result.add(particle);
            }
        }
        if (result.isEmpty()) {
            result.add(fallback);
        }
        return List.copyOf(result);
    }

    private static Particle parseParticle(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        try {
            return Particle.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    private static Set<Material> materials(FileConfiguration config, String path, List<String> defaults) {
        List<String> names = config.getStringList(path);
        if (names.isEmpty()) {
            names = defaults;
        }
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (String name : names) {
            if (name == null || name.isBlank()) {
                continue;
            }
            Material material = Material.matchMaterial(name.trim().toUpperCase(Locale.ROOT));
            if (material != null) {
                result.add(material);
            }
        }
        return result;
    }
}
