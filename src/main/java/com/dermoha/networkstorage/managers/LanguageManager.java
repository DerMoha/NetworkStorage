package com.dermoha.networkstorage.managers;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class LanguageManager {
    private final String language;
    private final Map<String, String> messages = new HashMap<>();

    public LanguageManager(FileConfiguration config) {
        this.language = config.getString("language", "en").toLowerCase(Locale.ROOT);
        loadMessages();
    }

    private void loadMessages() {
        // Load the selected language file first
        loadLangFile("lang_" + language + ".yml");
        // Fallback: Load English if keys are missing
        if (!language.equals("en")) {
            loadLangFile("lang_en.yml");
        }
    }

    private void loadLangFile(String fileName) {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(fileName);
        if (stream == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(new InputStreamReader(stream));
            for (String key : yaml.getKeys(false)) {
                messages.putIfAbsent(key, yaml.getString(key));
            }
        } catch (Exception e) {
            // Ignore loading errors, fallback will take effect
        }
    }

    public String get(String key, Object... args) {
        String msg = messages.getOrDefault(key, key);
        if (args.length > 0) {
            return String.format(msg, args);
        }
        return msg;
    }
}
