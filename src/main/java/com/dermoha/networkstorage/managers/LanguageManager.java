package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;

public class LanguageManager {

    private final NetworkStoragePlugin plugin;
    private FileConfiguration langConfig;
    private File langFile;
    private String lang;

    public LanguageManager(NetworkStoragePlugin plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
        loadAndCheckLangFile();
    }

    private void loadAndCheckLangFile() {
        String fileName = "lang_" + lang + ".yml";
        InputStream defaultStream = plugin.getResource(fileName);
        if (defaultStream == null) {
            plugin.getLogger().warning("Unsupported language '" + lang + "'. Falling back to English.");
            lang = "en";
            fileName = "lang_en.yml";
            defaultStream = plugin.getResource(fileName);
        }

        if (defaultStream == null) {
            plugin.getLogger().severe("Default language file 'lang_en.yml' is missing from the JAR. Cannot load translations.");
            langConfig = new YamlConfiguration();
            return;
        }

        langFile = new File(plugin.getDataFolder(), fileName);

        // If the user's file doesn't exist, create it from the JAR's default.
        if (!langFile.exists()) {
            plugin.saveResource(fileName, false);
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // Load the default language file from the JAR to use as a reference.
        FileConfiguration defaultLangConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream));

        // Check for missing keys and add them.
        Set<String> defaultKeys = defaultLangConfig.getKeys(true);
        boolean updated = false;
        for (String key : defaultKeys) {
            if (!langConfig.isSet(key)) {
                langConfig.set(key, defaultLangConfig.get(key));
                updated = true;
            }
        }

        // If we added any missing keys, save the users file.
        if (updated) {
            try {
                langConfig.save(langFile);
                plugin.getLogger().info("Updated '" + fileName + "' with missing translations.");
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save updated language file '" + fileName + "': " + e.getMessage());
            }
        }
    }

    public String getMessage(String key) {
        String message = langConfig.getString(key);
        if (message == null) {
            message = "§cMissing translation: " + key;
            plugin.getLogger().warning("Missing translation key '" + key + "' in language file '" + lang + "'. Please report this to the developer.");
        }
        return message.replace("&", "§");
    }

    public void setLanguage(String lang) {
        this.lang = lang;
        loadAndCheckLangFile();
    }
}
