package com.tidalsurger.ability;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks one cooldown per player and ability, persisted to
 * {@code plugins/TidalSurger/cooldowns.yml} so relogging cannot reset them.
 */
public final class CooldownStore {

    private final JavaPlugin plugin;
    private final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();
    private final File file;

    public CooldownStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "cooldowns.yml");
    }

    public long remainingMillis(Player player, AbilityType ability) {
        Map<String, Long> abilities = readyAt.get(player.getUniqueId());
        if (abilities == null) {
            return 0L;
        }
        Long ready = abilities.get(ability.configKey());
        if (ready == null) {
            return 0L;
        }
        long remaining = ready - System.currentTimeMillis();
        if (remaining <= 0L) {
            abilities.remove(ability.configKey());
            if (abilities.isEmpty()) {
                readyAt.remove(player.getUniqueId());
            }
            return 0L;
        }
        return remaining;
    }

    public boolean isReady(Player player, AbilityType ability) {
        return remainingMillis(player, ability) <= 0L;
    }

    public void set(Player player, AbilityType ability, int seconds) {
        if (seconds <= 0) {
            clear(player, ability);
            return;
        }
        readyAt.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>())
                .put(ability.configKey(), System.currentTimeMillis() + seconds * 1000L);
    }

    public void clear(Player player, AbilityType ability) {
        Map<String, Long> abilities = readyAt.get(player.getUniqueId());
        if (abilities == null) {
            return;
        }
        abilities.remove(ability.configKey());
        if (abilities.isEmpty()) {
            readyAt.remove(player.getUniqueId());
        }
    }

    public void clearAll(Player player) {
        readyAt.remove(player.getUniqueId());
    }

    public void load() {
        readyAt.clear();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("cooldowns");
        if (section == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (String uuidKey : section.getKeys(false)) {
            UUID uuid;
            try {
                uuid = UUID.fromString(uuidKey);
            } catch (IllegalArgumentException bad) {
                continue;
            }
            ConfigurationSection abilities = section.getConfigurationSection(uuidKey);
            if (abilities == null) {
                continue;
            }
            Map<String, Long> map = new HashMap<>();
            for (String ability : abilities.getKeys(false)) {
                long ready = abilities.getLong(ability);
                if (ready > now) {
                    map.put(ability, ready);
                }
            }
            if (!map.isEmpty()) {
                readyAt.put(uuid, map);
            }
        }
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<String, Long>> entry : readyAt.entrySet()) {
            for (Map.Entry<String, Long> ability : entry.getValue().entrySet()) {
                if (ability.getValue() > now) {
                    yaml.set("cooldowns." + entry.getKey() + "." + ability.getKey(), ability.getValue());
                }
            }
        }
        try {
            if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
                plugin.getLogger().warning("Could not create the plugin data folder; cooldowns will not persist.");
                return;
            }
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save cooldowns.yml: " + exception.getMessage());
        }
    }
}
