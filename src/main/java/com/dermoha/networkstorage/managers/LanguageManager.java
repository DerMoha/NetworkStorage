package com.dermoha.networkstorage.managers;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

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
        String requestedLang = lang;
        String fileName = "lang_" + requestedLang + ".yml";

        // Pick the reference config used to fill in missing keys. Prefer the
        // JAR's lang_<code>.yml (preserves current behavior for shipped
        // languages). Fall back to lang_en.yml for user-supplied languages.
        FileConfiguration reference = loadJarConfig(fileName);
        boolean bundled = reference != null;
        FileConfiguration englishReference = loadJarConfig("lang_en.yml");
        if (reference == null && englishReference == null) {
            plugin.getLogger().severe("Default language file 'lang_en.yml' is missing from the JAR. Cannot load translations.");
            langConfig = new YamlConfiguration();
            return;
        }
        if (reference == null) {
            reference = englishReference;
            plugin.getLogger().info("Language '" + requestedLang + "' has no bundled file in the JAR. Drop 'lang_"
                    + requestedLang + ".yml' into plugins/NetworkStorage/ to provide custom translations; missing keys will be filled in from English.");
        }

        // Resolve the user file. If it doesn't exist, copy the JAR default
        // (if present). If neither exists, fall back to English.
        File userFile = new File(plugin.getDataFolder(), fileName);
        if (!userFile.exists()) {
            if (bundled) {
                plugin.saveResource(fileName, false);
            } else {
                plugin.getLogger().warning("No translation file for language '" + requestedLang + "'. Expected '"
                        + fileName + "' in plugins/NetworkStorage/. Falling back to English.");
                requestedLang = "en";
                fileName = "lang_en.yml";
                userFile = new File(plugin.getDataFolder(), fileName);
                if (!userFile.exists()) {
                    plugin.saveResource(fileName, false);
                }
                reference = englishReference;
            }
        }

        lang = requestedLang;
        langFile = userFile;

        try {
            YamlConfiguration parsed = new YamlConfiguration();
            parsed.load(langFile);
            langConfig = parsed;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to read '" + fileName + "': " + e.getMessage()
                    + ". Preserving the file and using bundled translations in memory.");
            langConfig = reference != null ? reference : new YamlConfiguration();
            return;
        }

        // Merge missing keys from the reference into the loaded config so
        // partial translations still work end-to-end.
        if (reference == null) {
            return;
        }
        Set<String> referenceKeys = reference.getKeys(true);
        boolean updated = false;
        for (String key : referenceKeys) {
            if (!langConfig.isSet(key)) {
                langConfig.set(key, reference.get(key));
                updated = true;
            }
        }

        if (updated) {
            try {
                java.nio.file.Path temporary = Files.createTempFile(langFile.toPath().getParent(), "translations-", ".tmp");
                try {
                    Files.writeString(temporary, langConfig.saveToString(), StandardCharsets.UTF_8);
                    try {
                        Files.move(temporary, langFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                    } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, langFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
                plugin.getLogger().info("Updated '" + fileName + "' with missing translations.");
            } catch (IOException e) {
                plugin.getLogger().severe("Could not save updated language file '" + fileName + "': " + e.getMessage());
            }
        }
    }

    private FileConfiguration loadJarConfig(String fileName) {
        InputStream stream = plugin.getResource(fileName);
        if (stream == null) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            YamlConfiguration parsed = new YamlConfiguration();
            parsed.load(reader);
            return parsed;
        } catch (Exception e) {
            plugin.getLogger().warning("Could not read '" + fileName + "' from JAR: " + e.getMessage());
            return null;
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
