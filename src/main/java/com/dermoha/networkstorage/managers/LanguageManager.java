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
        // Ensure default language files are present for customization.
        saveDefaultLanguageFile("lang_en.yml");
        saveDefaultLanguageFile("lang_de.yml");

        // Determine which file to load. Default to English.
        File langFile = new File(plugin.getDataFolder(), "lang_" + this.language + ".yml");
        File englishFile = new File(plugin.getDataFolder(), "lang_en.yml");

        // First, load English as the base for fallbacks.
        if (englishFile.exists()) {
            loadFromFile(englishFile);
        } else {
            // This should not happen if saveDefaultLanguageFile works, but it's a good safeguard.
            plugin.getLogger().severe("English language file (lang_en.yml) is missing! Cannot load messages.");
            return;
        }

        // If a different language is configured, load it to override English messages.
        if (!"en".equals(this.language)) {
            if (langFile.exists()) {
                loadFromFile(langFile);
            } else {
                plugin.getLogger().warning("Language file for '" + this.language + "' not found. Using English.");
            }
        }
    }

    private void saveDefaultLanguageFile(String fileName) {
        File langFile = new File(plugin.getDataFolder(), fileName);
        if (!langFile.exists()) {
            plugin.saveResource(fileName, false); // false to not replace if it exists
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
