package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;

public class LanguageManager {

    private final NetworkStoragePlugin plugin;
    private FileConfiguration langConfig;
    private String lang;

    public LanguageManager(NetworkStoragePlugin plugin, String lang) {
        this.plugin = plugin;
        this.lang = lang;
        loadLangFile();
    }

    private void loadLangFile() {
        String fileName = "lang_" + lang + ".yml";
        File langFile = new File(plugin.getDataFolder(), fileName);
        if (!langFile.exists()) {
            plugin.saveResource(fileName, false);
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);

        // Fallback to default lang file in JAR
        InputStream defaultStream = plugin.getResource(fileName);
        if (defaultStream != null) {
            langConfig.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(defaultStream)));
        }
    }

    public String getMessage(String key) {
        String message = langConfig.getString(key, "&cMessage not found: " + key);
        return message.replace("&", "§");
    }

    public void setLanguage(String lang) {
        this.lang = lang;
        loadLangFile();
    }
}