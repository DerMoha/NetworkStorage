package com.dermoha.networkstorage.managers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LanguageManager {
    private final String language;
    private final Map<String, String> messages = new HashMap<>();
    private final JavaPlugin plugin;

    public LanguageManager(JavaPlugin plugin, FileConfiguration config) {
        this.plugin = plugin;
        this.language = config.getString("language", "en").toLowerCase(Locale.ROOT);
        loadMessages();
    }

    private void loadMessages() {
        // First try to load from the plugin's data folder
        File langFile = new File(plugin.getDataFolder(), "lang_" + language + ".yml");
        if (langFile.exists()) {
            loadFromFile(langFile);
        } else {
            // If not found in data folder, load from resources
            loadFromResource("lang_" + language + ".yml");
        }

        // Always load English as fallback for missing keys
        if (!language.equals("en")) {
            loadFromResource("lang_en.yml");
        }

        // Save the language file to the plugin folder if it doesn't exist
        if (!langFile.exists()) {
            plugin.saveResource("lang_" + language + ".yml", false);
        }
    }

    private void loadFromFile(File file) {
        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            for (String key : yaml.getKeys(true)) {
                if (yaml.isString(key)) {
                    messages.put(key, yaml.getString(key));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error loading language file: " + file.getName());
            e.printStackTrace();
        }
    }

    private void loadFromResource(String fileName) {
        InputStream stream = plugin.getResource(fileName);
        if (stream == null) {
            plugin.getLogger().warning("Could not find language file in resources: " + fileName);
            return;
        }

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
            for (String key : yaml.getKeys(true)) {
                if (yaml.isString(key)) {
                    // Only add if key doesn't exist (fallback mechanism)
                    messages.putIfAbsent(key, yaml.getString(key));
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error loading language file from resources: " + fileName);
            e.printStackTrace();
        }
    }

    public String get(String key, Object... args) {
        String msg = messages.getOrDefault(key, key);
        try {
            return args.length > 0 ? String.format(msg, args) : msg;
        } catch (Exception e) {
            plugin.getLogger().warning("Error formatting message for key: " + key);
            return msg;
        }
    }
}
