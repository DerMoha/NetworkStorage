package com.dermoha.networkstorage.runtime;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.listeners.WirelessTerminalListener;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.inventory.ShapedRecipe;
import java.util.List;
import java.util.Set;

/** Owns recipe validation, fallback and registration for one runtime. */
public final class WirelessRecipeRegistration implements AutoCloseable {
    private static final String WIRELESS_RECIPE_KEY = "wireless_terminal";
    private final NetworkStoragePlugin plugin;
    public WirelessRecipeRegistration(NetworkStoragePlugin plugin) { this.plugin = plugin; }
    @Override public void close() { plugin.getServer().removeRecipe(new NamespacedKey(plugin, WIRELESS_RECIPE_KEY)); }

    public void register() {
        NamespacedKey key = new NamespacedKey(plugin, WIRELESS_RECIPE_KEY);
        plugin.getServer().removeRecipe(key);
        if (!tryRegisterWirelessRecipe(key, getConfiguredWirelessRecipeShape(), true)) {
            plugin.getLogger().warning("Falling back to the default wireless terminal recipe.");
            plugin.getServer().removeRecipe(key);
            if (!tryRegisterWirelessRecipe(key, getDefaultWirelessRecipeShape(), false)) {
                plugin.getLogger().severe("Default wireless terminal recipe registration failed.");
            }
        }
    }

    private boolean tryRegisterWirelessRecipe(NamespacedKey key, String[] shape, boolean useConfiguredIngredients) {
        try {
            ShapedRecipe recipe = new ShapedRecipe(key, WirelessTerminalListener.createWirelessTerminal(plugin));
            recipe.shape(shape);
            if (useConfiguredIngredients) {
                if (!applyConfiguredWirelessRecipeIngredients(recipe, shape)) {
                    return false;
                }
            } else {
                applyDefaultWirelessRecipeIngredients(recipe);
            }
            if (!plugin.getServer().addRecipe(recipe)) {
                plugin.getLogger().warning("Wireless terminal recipe could not be registered.");
                return false;
            }
            return true;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Invalid wireless terminal recipe config: " + e.getMessage());
            return false;
        }
    }

    private String[] getConfiguredWirelessRecipeShape() {
        List<String> shape = plugin.getConfig().getStringList("wireless-terminal-recipe.shape");
        if (shape.isEmpty()) {
            return getDefaultWirelessRecipeShape();
        }
        if (!isValidRecipeShape(shape)) {
            plugin.getLogger().warning("Invalid wireless terminal recipe shape; using default shape.");
            return getDefaultWirelessRecipeShape();
        }
        return shape.toArray(new String[0]);
    }

    private boolean isValidRecipeShape(List<String> shape) {
        if (shape.isEmpty() || shape.size() > 3) {
            return false;
        }
        int width = shape.get(0).length();
        if (width == 0 || width > 3) {
            return false;
        }
        boolean hasIngredientSlot = false;
        for (String row : shape) {
            if (row == null || row.length() != width) {
                return false;
            }
            for (int i = 0; i < row.length(); i++) {
                if (row.charAt(i) != ' ') {
                    hasIngredientSlot = true;
                }
            }
        }
        return hasIngredientSlot;
    }

    private boolean applyConfiguredWirelessRecipeIngredients(ShapedRecipe recipe, String[] shape) {
        Set<Character> requiredIngredients = getRequiredRecipeIngredients(shape);
        Set<Character> configuredIngredients = new java.util.HashSet<>();
        ConfigurationSection ingredients = plugin.getConfig().getConfigurationSection("wireless-terminal-recipe.ingredients");
        if (ingredients == null) {
            applyDefaultWirelessRecipeIngredients(recipe);
            configuredIngredients.add('C');
            configuredIngredients.add('S');
            configuredIngredients.add('D');
            return hasAllRequiredRecipeIngredients(requiredIngredients, configuredIngredients);
        }

        for (String keyChar : ingredients.getKeys(false)) {
            if (keyChar.length() != 1) {
                plugin.getLogger().warning("Ignoring invalid wireless recipe ingredient key '" + keyChar + "'.");
                continue;
            }
            char ingredientKey = keyChar.charAt(0);
            if (!requiredIngredients.contains(ingredientKey)) {
                plugin.getLogger().warning("Ignoring unused wireless recipe ingredient key '" + keyChar + "'.");
                continue;
            }

            String materialName = ingredients.getString(keyChar);
            Material ingredient = materialName == null ? null : Material.matchMaterial(materialName);
            if (ingredient == null || ingredient == Material.AIR || !ingredient.isItem()) {
                plugin.getLogger().warning("Invalid material '" + materialName + "' in wireless terminal recipe.");
                continue;
            }
            recipe.setIngredient(ingredientKey, ingredient);
            configuredIngredients.add(ingredientKey);
        }

        return hasAllRequiredRecipeIngredients(requiredIngredients, configuredIngredients);
    }

    private Set<Character> getRequiredRecipeIngredients(String[] shape) {
        Set<Character> requiredIngredients = new java.util.HashSet<>();
        for (String row : shape) {
            for (int i = 0; i < row.length(); i++) {
                char ingredientKey = row.charAt(i);
                if (ingredientKey != ' ') {
                    requiredIngredients.add(ingredientKey);
                }
            }
        }
        return requiredIngredients;
    }

    private boolean hasAllRequiredRecipeIngredients(Set<Character> requiredIngredients, Set<Character> configuredIngredients) {
        for (char ingredientKey : requiredIngredients) {
            if (!configuredIngredients.contains(ingredientKey)) {
                plugin.getLogger().warning("Missing wireless recipe ingredient for key '" + ingredientKey + "'.");
                return false;
            }
        }
        return true;
    }

    private void applyDefaultWirelessRecipeIngredients(ShapedRecipe recipe) {
        recipe.setIngredient('C', Material.COMPASS);
        recipe.setIngredient('S', Material.NETHER_STAR);
        recipe.setIngredient('D', Material.DIAMOND_BLOCK);
    }

    private String[] getDefaultWirelessRecipeShape() {
        return new String[] {"CCC", "CSC", "CDC"};
    }

}
