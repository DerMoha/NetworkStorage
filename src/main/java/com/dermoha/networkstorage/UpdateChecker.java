package com.dermoha.networkstorage;

import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.managers.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UpdateChecker {

    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/MFLw2RTS/version";
    private static final Pattern VERSION_PATTERN = Pattern.compile("\"version_number\":\"([^\"]+)\"");

    private final NetworkStoragePlugin plugin;
    private final ConfigManager configManager;
    private final LanguageManager lang;

    public UpdateChecker(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.lang = plugin.getLanguageManager();
    }

    public void checkForUpdates() {
        if (!configManager.isUpdateCheckEnabled()) {
            return;
        }

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String currentVersion = plugin.getDescription().getVersion();
                String latestVersion = fetchLatestVersion();

                if (latestVersion != null && isNewerVersion(latestVersion, currentVersion)) {
                    notifyAdmins(currentVersion, latestVersion);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
                notifyCheckFailed(e.getMessage());
            }
        });
    }

    private String fetchLatestVersion() throws Exception {
        URL url = new URL(MODRINTH_API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try {
            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }

                String json = response.toString();
                Matcher matcher = VERSION_PATTERN.matcher(json);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } finally {
            connection.disconnect();
        }
        return null;
    }

    private boolean isNewerVersion(String latest, String current) {
        String[] latestParts = latest.split("\\.");
        String[] currentParts = current.split("\\.");

        int length = Math.max(latestParts.length, currentParts.length);
        for (int i = 0; i < length; i++) {
            int latestPart = i < latestParts.length ? parseVersionPart(latestParts[i]) : 0;
            int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;

            if (latestPart > currentPart) {
                return true;
            }
            if (latestPart < currentPart) {
                return false;
            }
        }
        return false;
    }

    private int parseVersionPart(String part) {
        StringBuilder sb = new StringBuilder();
        for (char c : part.toCharArray()) {
            if (Character.isDigit(c)) {
                sb.append(c);
            } else {
                break;
            }
        }
        return sb.length() > 0 ? Integer.parseInt(sb.toString()) : 0;
    }

    private void notifyAdmins(String currentVersion, String latestVersion) {
        if (!configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }

        String messageTemplate = lang.getMessage("update.available");
        String message;
        if (messageTemplate == null) {
            message = "§e[NetworkStorage] A new version (§a" + latestVersion + "§e) is available! Current version: §c" + currentVersion;
        } else {
            message = messageTemplate.formatted(latestVersion, currentVersion);
        }

        final String finalMessage = message;

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.isOp()) {
                    player.sendMessage(finalMessage);
                }
            }
            String consoleMsg = lang.getMessage("update.notify.console");
            if (consoleMsg != null) {
                plugin.getLogger().info(consoleMsg.formatted(latestVersion, currentVersion));
            }
        });
    }

    private void notifyCheckFailed(String error) {
        if (!configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            String message = lang.getMessage("update.check.failed");
            if (message != null) {
                message = message.formatted(error);
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.isOp()) {
                        player.sendMessage(message);
                    }
                }
            }
        });
    }
}