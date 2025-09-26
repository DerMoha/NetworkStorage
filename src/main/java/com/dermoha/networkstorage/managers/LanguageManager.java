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
        // Always load English from resources as the base.
        loadFromResource("lang_en.yml");

        // Load the configured language if it's not English, overwriting the defaults.
        if (!language.equals("en")) {
            File langFile = new File(plugin.getDataFolder(), "lang_" + language + ".yml");
            if (langFile.exists()) {
                loadFromFile(langFile); // This uses put(), overwriting defaults.
            } else {
                // Load the default for the language from resources, overwriting defaults.
                loadFromResource("lang_" + language + ".yml");
            }
        }

        // Save the language file for the configured language to the plugin folder if it doesn't exist.
        File userLangFile = new File(plugin.getDataFolder(), "lang_" + language + ".yml");
        if (!userLangFile.exists()) {
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
            if ("lang_en.yml".equals(fileName)) {
                plugin.getLogger().severe("English language file (lang_en.yml) is missing! This is a critical error.");
            }
            return;
        }

        try {
            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new InputStreamReader(stream));
            for (String key : yaml.getKeys(true)) {
                if (yaml.isString(key)) {
                    // Overwrite existing keys to allow the fallback system to work correctly.
                    messages.put(key, yaml.getString(key));
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
