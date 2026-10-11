package com.tidalsurger;

import com.tidalsurger.ability.AbilityService;
import com.tidalsurger.ability.CooldownBars;
import com.tidalsurger.ability.CooldownStore;
import com.tidalsurger.command.TidalSurgerCommand;
import com.tidalsurger.config.PluginSettings;
import com.tidalsurger.core.TidalCoreManager;
import com.tidalsurger.input.InputListener;
import com.tidalsurger.item.TidalSurgerItem;
import com.tidalsurger.listener.ProtectionListener;
import com.tidalsurger.surf.TidalSurfManager;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * TidalSurger - a legendary ocean trident for Paper 1.21.11.
 *
 * <ul>
 *   <li><b>Tidal Surf</b> (offhand key): ride a water wave forward for five
 *       seconds with no fall damage.</li>
 *   <li><b>Tidal Core</b> (Shift + offhand key): seal the target inside a
 *       10x10x10 water cube for five seconds.</li>
 * </ul>
 */
public final class TidalSurgerPlugin extends JavaPlugin {

    private volatile PluginSettings settings;
    private NamespacedKey itemKey;
    private NamespacedKey instanceKey;
    private TidalSurgerItem itemFactory;
    private CooldownStore cooldowns;
    private CooldownBars cooldownBars;
    private TidalCoreManager coreManager;
    private TidalSurfManager surfManager;
    private AbilityService abilities;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        mergeConfigDefaults();

        this.settings = new PluginSettings(getConfig());
        this.itemKey = new NamespacedKey(this, "tidal_surger");
        this.instanceKey = new NamespacedKey(this, "tidal_surger_id");

        this.cooldowns = new CooldownStore(this);
        this.cooldownBars = new CooldownBars(this);
        this.itemFactory = new TidalSurgerItem(this);
        this.coreManager = new TidalCoreManager(this);
        this.surfManager = new TidalSurfManager(this);
        this.abilities = new AbilityService(this);

        this.cooldowns.load();
        this.surfManager.start();
        this.coreManager.start();
        this.cooldownBars.start();

        getServer().getPluginManager().registerEvents(new InputListener(this), this);
        getServer().getPluginManager().registerEvents(new ProtectionListener(this), this);

        TidalSurgerCommand executor = new TidalSurgerCommand(this);
        if (getCommand("tidalsurger") != null) {
            getCommand("tidalsurger").setExecutor(executor);
            getCommand("tidalsurger").setTabCompleter(executor);
        } else {
            getLogger().warning("The tidalsurger command is missing from plugin.yml.");
        }

        this.itemFactory.registerRecipe();
        getLogger().info("Tidal Surger enabled on " + getServer().getName() + " " + getServer().getMinecraftVersion());
    }

    @Override
    public void onDisable() {
        if (surfManager != null) {
            surfManager.shutdown();
        }
        if (coreManager != null) {
            coreManager.shutdown();
        }
        if (cooldownBars != null) {
            cooldownBars.shutdown();
        }
        if (cooldowns != null) {
            cooldowns.save();
        }
        getLogger().info("Tidal Surger disabled; every water cube and wave was restored.");
    }

    /**
     * Adds any key that is new in this plugin version to an existing config.yml,
     * so upgrading never leaves the plugin on "Missing message" fallbacks.
     */
    private void mergeConfigDefaults() {
        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    /** Rebuilds the settings snapshot and the crafting recipe from disk. */
    public void reload() {
        reloadConfig();
        mergeConfigDefaults();
        this.settings = new PluginSettings(getConfig());
        this.itemFactory.registerRecipe();
        this.cooldownBars.reload();
    }

    public PluginSettings settings() {
        return settings;
    }

    public NamespacedKey itemKey() {
        return itemKey;
    }

    public NamespacedKey instanceKey() {
        return instanceKey;
    }

    public TidalSurgerItem itemFactory() {
        return itemFactory;
    }

    public CooldownStore cooldowns() {
        return cooldowns;
    }

    public CooldownBars cooldownBars() {
        return cooldownBars;
    }

    public TidalCoreManager coreManager() {
        return coreManager;
    }

    public TidalSurfManager surfManager() {
        return surfManager;
    }

    public AbilityService abilities() {
        return abilities;
    }

    public boolean isTidalSurger(ItemStack stack) {
        return itemFactory.isTidalSurger(stack);
    }
}
