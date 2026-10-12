package com.tidalsurger.item;

import com.tidalsurger.TidalSurgerPlugin;
import com.tidalsurger.util.Text;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Builds the legendary Tidal Surger and recognises existing copies.
 *
 * <p>The Tidal Surger is a <b>netherite sword</b> that wears the legendary
 * trident's skin: the base material is {@link Material#NETHERITE_SWORD}, so it
 * fights with the netherite sword's damage, attack speed and sweeping, while the
 * {@code minecraft:item_model} component points the client at the vanilla
 * {@code minecraft:trident} item model. That model (and the resource pack's
 * {@code custom_model_data 1011} branch inside it) is what makes the sword look
 * exactly like the special trident, in the hand and in the inventory.</p>
 *
 * <p>Identity comes from a {@link org.bukkit.persistence.PersistentDataContainer}
 * tag, never from the display name, so a renamed or anvil-modified weapon still
 * counts as a Tidal Surger while every ordinary netherite sword and trident is
 * ignored.</p>
 */
public final class TidalSurgerItem {

    /** Identifies the Tidal Surger; the tag alone is what really matters. */
    private static boolean isBaseMaterial(Material material) {
        return material == Material.NETHERITE_SWORD || material == Material.TRIDENT;
    }

    private final TidalSurgerPlugin plugin;

    public TidalSurgerItem(TidalSurgerPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isTidalSurger(ItemStack stack) {
        if (stack == null || !isBaseMaterial(stack.getType())) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(plugin.itemKey(), PersistentDataType.BYTE);
    }

    public String instanceId(ItemStack stack) {
        if (stack == null || !isBaseMaterial(stack.getType())) {
            return null;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(plugin.instanceKey(), PersistentDataType.STRING);
    }

    /**
     * Creates a fresh Tidal Surger: a netherite sword rendered with the trident's
     * model. Each copy carries its own unique instance id so worlds/logs can tell
     * two Tidal Surgers apart.
     */
    public ItemStack create() {
        ItemStack stack = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }

        meta.displayName(Text.mm(plugin.getConfig().getString("item.name", "<aqua><bold>Tidal Surger")));

        List<Component> lore = new ArrayList<>();
        for (String line : plugin.getConfig().getStringList("item.lore")) {
            lore.add(Text.mm(line));
        }
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }

        // The disguise: the client renders this netherite sword with the trident's
        // item model, which is also where the resource pack's custom skin lives.
        applyItemModel(meta);

        int customModelData = plugin.getConfig().getInt("item.custom-model-data", 0);
        if (customModelData > 0) {
            meta.setCustomModelData(customModelData);
        }
        if (plugin.getConfig().getBoolean("item.glowing", true)) {
            meta.setEnchantmentGlintOverride(Boolean.TRUE);
        }
        if (plugin.getConfig().getBoolean("item.unbreakable", true)) {
            meta.setUnbreakable(true);
            meta.addItemFlags(ItemFlag.HIDE_UNBREAKABLE);
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);

        applyEnchantments(meta);

        meta.getPersistentDataContainer().set(plugin.itemKey(), PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(plugin.instanceKey(), PersistentDataType.STRING, UUID.randomUUID().toString());

        stack.setItemMeta(meta);
        return stack;
    }

    private void applyItemModel(ItemMeta meta) {
        String configured = plugin.getConfig().getString("item.item-model", "minecraft:trident");
        if (configured == null || configured.isBlank()) {
            return;
        }
        String id = configured.trim().toLowerCase(Locale.ROOT);
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        NamespacedKey model;
        try {
            model = NamespacedKey.fromString(id);
        } catch (IllegalArgumentException invalid) {
            model = null;
        }
        if (model == null) {
            plugin.getLogger().warning("Invalid item.item-model '" + configured + "'; the item keeps its default model.");
            return;
        }
        meta.setItemModel(model);
    }

    @SuppressWarnings("deprecation")
    private void applyEnchantments(ItemMeta meta) {
        for (String entry : plugin.getConfig().getStringList("item.enchantments")) {
            if (entry == null || entry.isBlank() || !entry.contains(":")) {
                continue;
            }
            String[] parts = entry.split(":", 2);
            NamespacedKey key = NamespacedKey.minecraft(parts[0].trim().toLowerCase(Locale.ROOT));
            Enchantment enchantment = Enchantment.getByKey(key);
            if (enchantment == null) {
                plugin.getLogger().warning("Unknown enchantment in item.enchantments: " + entry);
                continue;
            }
            int level;
            try {
                level = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException bad) {
                continue;
            }
            meta.addEnchant(enchantment, level, true);
        }
    }

    /**
     * Registers (or re-registers) the configured crafting recipe.
     */
    public void registerRecipe() {
        NamespacedKey recipeKey = new NamespacedKey(plugin, "tidal_surger");
        if (!plugin.getConfig().getBoolean("item.recipe.enabled", true)) {
            plugin.getServer().removeRecipe(recipeKey);
            return;
        }
        ConfigurationSection ingredients = plugin.getConfig().getConfigurationSection("item.recipe.ingredients");
        List<String> shape = plugin.getConfig().getStringList("item.recipe.shape");
        if (ingredients == null || shape.isEmpty() || shape.size() > 3) {
            plugin.getLogger().warning("item.recipe is incomplete; the crafting recipe was skipped.");
            return;
        }
        try {
            ShapedRecipe recipe = new ShapedRecipe(recipeKey, create());
            recipe.shape(shape.toArray(new String[0]));
            for (String symbol : ingredients.getKeys(false)) {
                Material material = Material.matchMaterial(String.valueOf(ingredients.get(symbol)).toUpperCase(Locale.ROOT));
                if (material == null) {
                    plugin.getLogger().warning("Unknown recipe ingredient '" + ingredients.get(symbol) + "'; the recipe was skipped.");
                    return;
                }
                recipe.setIngredient(symbol.charAt(0), material);
            }
            // 1.21.11 keeps a registered recipe by id, so replace it explicitly instead of
            // relying on addRecipe's overwrite flag (which throws on a duplicate id).
            plugin.getServer().removeRecipe(recipeKey);
            plugin.getServer().addRecipe(recipe);
        } catch (IllegalArgumentException invalid) {
            plugin.getLogger().warning("Could not register the Tidal Surger recipe: " + invalid.getMessage());
        }
    }
}
