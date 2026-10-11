package com.tidalsurger.ability;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.config.PluginSettings;
import com.tidalsurger.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The blue recharge bar shown at the top of the screen while an ability is on
 * cooldown.
 *
 * <p>One BossBar per ability per player: it appears the moment the ability is
 * used, drains with the remaining cooldown (its fill is exactly the remaining
 * fraction) and shows the remaining seconds, then flips to the "ready" title for
 * a moment and disappears. If both abilities are recharging both bars are shown,
 * stacked at the top of the screen. Bars survive relogging because they are
 * rebuilt from the persisted {@link CooldownStore} on join.</p>
 */
public final class CooldownBars {

    private final TidalSurgerPlugin plugin;
    private final Map<UUID, EnumMap<AbilityType, Entry>> bars = new HashMap<>();
    private BukkitTask task;
    private int ticks;

    public CooldownBars(TidalSurgerPlugin plugin) {
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
        for (EnumMap<AbilityType, Entry> entries : bars.values()) {
            for (Entry entry : entries.values()) {
                entry.bar.removeAll();
            }
        }
        bars.clear();
    }

    /** Re-reads the bar settings after {@code /ts reload}. */
    public void reload() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            sync(player);
        }
    }

    public void onJoin(Player player) {
        sync(player);
    }

    public void onQuit(Player player) {
        EnumMap<AbilityType, Entry> entries = bars.remove(player.getUniqueId());
        if (entries != null) {
            for (Entry entry : entries.values()) {
                entry.bar.removeAll();
            }
        }
    }

    /**
     * Drops every bar of one player at once, e.g. when their cooldowns were
     * cleared by {@code /ts cooldown}. The next tick would remove an expired bar
     * anyway; this makes it disappear the instant the cooldown is gone.
     */
    public void clear(Player player) {
        EnumMap<AbilityType, Entry> entries = bars.remove(player.getUniqueId());
        if (entries == null) {
            return;
        }
        for (Entry entry : entries.values()) {
            entry.bar.removeAll();
        }
    }

    /** Called right after an ability was used, so its bar starts draining at once. */
    public void onCooldownStarted(Player player, AbilityType type) {
        if (!plugin.settings().cooldownBar.enabled) {
            return;
        }
        sync(player);
    }

    public boolean hasBar(Player player, AbilityType type) {
        EnumMap<AbilityType, Entry> entries = bars.get(player.getUniqueId());
        return entries != null && entries.containsKey(type);
    }

    // ------------------------------------------------------------------- ticks

    private void tick() {
        ticks++;
        if (bars.isEmpty()) {
            return;
        }
        int interval = Math.max(1, plugin.settings().cooldownBar.updateIntervalTicks);
        if (ticks % interval != 0) {
            return;
        }
        Iterator<Map.Entry<UUID, EnumMap<AbilityType, Entry>>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, EnumMap<AbilityType, Entry>> node = iterator.next();
            Player player = Bukkit.getPlayer(node.getKey());
            if (player == null || !player.isOnline()) {
                for (Entry entry : node.getValue().values()) {
                    entry.bar.removeAll();
                }
                iterator.remove();
                continue;
            }
            update(player, node.getValue());
            if (node.getValue().isEmpty()) {
                iterator.remove();
            }
        }
    }

    /** Creates, updates and retires the bars of one player. */
    private void sync(Player player) {
        EnumMap<AbilityType, Entry> entries = bars.computeIfAbsent(player.getUniqueId(),
                ignored -> new EnumMap<>(AbilityType.class));
        update(player, entries);
        if (entries.isEmpty()) {
            bars.remove(player.getUniqueId());
        }
    }

    private void update(Player player, EnumMap<AbilityType, Entry> entries) {
        PluginSettings settings = plugin.settings();
        PluginSettings.CooldownBar config = settings.cooldownBar;

        for (AbilityType type : AbilityType.values()) {
            long remaining = plugin.cooldowns().remainingMillis(player, type);
            long total = Math.max(1L, settings.ability(type).cooldownSeconds() * 1000L);
            Entry entry = entries.get(type);

            if (remaining > 0L) {
                if (!config.enabled) {
                    if (entry != null) {
                        entry.bar.removeAll();
                        entries.remove(type);
                    }
                    continue;
                }
                if (entry == null) {
                    entry = new Entry(Bukkit.createBossBar("", config.color, config.style));
                    entry.bar.addPlayer(player);
                    entries.put(type, entry);
                }
                if (entry.state != State.RECHARGING) {
                    entry.state = State.RECHARGING;
                    entry.readyTicks = 0;
                }
                double progress = Math.max(0.0D, Math.min(1.0D, remaining / (double) total));
                apply(entry, config);
                String title = title(config.title, type, remaining);
                if (!title.equals(entry.lastTitle)) {
                    entry.bar.setTitle(title);
                    entry.lastTitle = title;
                }
                if (Math.abs(entry.bar.getProgress() - progress) > 1.0E-4D) {
                    entry.bar.setProgress(progress);
                }
                if (!entry.bar.isVisible()) {
                    entry.bar.setVisible(true);
                }
            } else if (entry != null) {
                if (entry.state == State.RECHARGING) {
                    // Just became usable: flash the ready title for a moment.
                    entry.state = State.READY;
                    entry.readyTicks = 0;
                    entry.lastTitle = title(config.readyTitle, type, 0L);
                    entry.bar.setTitle(entry.lastTitle);
                    entry.bar.setProgress(1.0D);
                    apply(entry, config);
                } else {
                    entry.readyTicks++;
                    if (entry.readyTicks >= config.readyLingerTicks) {
                        entry.bar.removeAll();
                        entries.remove(type);
                    }
                }
            }
        }
    }

    /** Keeps colour and style in sync with a config that may have been reloaded. */
    private void apply(Entry entry, PluginSettings.CooldownBar config) {
        if (entry.bar.getColor() != config.color) {
            entry.bar.setColor(config.color);
        }
        if (entry.bar.getStyle() != config.style) {
            entry.bar.setStyle(config.style);
        }
    }

    private String title(String template, AbilityType type, long remainingMillis) {
        String rendered = template
                .replace("%ability%", type.displayName())
                .replace("%seconds%", String.format(Locale.ROOT, "%.1f", remainingMillis / 1000.0D));
        return Text.legacy(rendered);
    }

    /** The two display states of a bar. */
    private enum State {
        RECHARGING, READY
    }

    /** One ability's bar for one player. */
    private static final class Entry {

        private final BossBar bar;
        private State state = State.RECHARGING;
        private int readyTicks;
        private String lastTitle = "";

        private Entry(BossBar bar) {
            this.bar = bar;
        }
    }
}
