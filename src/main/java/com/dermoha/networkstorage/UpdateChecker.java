package com.dermoha.networkstorage;

import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.util.NetworkStorageConstants;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker {

    private static final String MODRINTH_API_URL = "https://api.modrinth.com/v2/project/MFLw2RTS/version";

    private final NetworkStoragePlugin plugin;
    private final String userAgent;
    private final ConfigManager configManager;
    private final LanguageManager lang;

    private volatile String currentVersion;
    private volatile String latestVersion;
    private volatile boolean updateAvailable;
    private volatile long lastCheckedAtMs;
    private volatile long lastCheckedRateLimitRemaining = -1L;

    private volatile CompletableFuture<Result> inFlight;

    public UpdateChecker(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.lang = plugin.getLanguageManager();
        this.currentVersion = plugin.getDescription().getVersion();
        this.userAgent = NetworkStorageConstants.UPDATE_USER_AGENT_PREFIX
                + plugin.getDescription().getVersion()
                + " (https://github.com/DerMoha/NetworkStorage)";
    }

    public CompletableFuture<Result> checkNow() {
        if (!configManager.isUpdateCheckEnabled()) {
            CompletableFuture<Result> disabled = CompletableFuture.completedFuture(Result.disabled());
            inFlight = disabled;
            return disabled;
        }

        if (inFlight != null && !inFlight.isDone()) {
            return inFlight;
        }

        CompletableFuture<Result> future = new CompletableFuture<>();
        inFlight = future;

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String fetched = fetchLatestVersion();
                if (fetched == null) {
                    lastCheckedAtMs = System.currentTimeMillis();
                    notifyCheckFailed("empty response from Modrinth");
                    future.complete(Result.failed("empty response from Modrinth"));
                    return;
                }

                String localVersion = plugin.getDescription().getVersion();
                currentVersion = localVersion;
                latestVersion = fetched;
                updateAvailable = isNewerVersion(fetched, localVersion);
                lastCheckedAtMs = System.currentTimeMillis();

                if (updateAvailable) {
                    notifyAdmins(localVersion, fetched);
                } else {
                    plugin.getLogger().info("[NetworkStorage] You are running the latest version (" + localVersion + ").");
                }

                future.complete(Result.success(localVersion, fetched, updateAvailable));
            } catch (Exception e) {
                lastCheckedAtMs = System.currentTimeMillis();
                plugin.getLogger().warning("Failed to check for updates: " + e.getMessage());
                notifyCheckFailed(e.getMessage());
                future.complete(Result.failed(e.getMessage()));
            }
        });

        future.whenComplete((r, ex) -> inFlight = null);
        return future;
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }

    public String getCurrentVersion() {
        return currentVersion != null ? currentVersion : plugin.getDescription().getVersion();
    }

    public long getLastCheckedAtMs() {
        return lastCheckedAtMs;
    }

    public boolean hasCompletedCheck() {
        return lastCheckedAtMs > 0L;
    }

    public String getStatus() {
        if (!plugin.getConfigManager().isUpdateCheckEnabled()) {
            return "disabled";
        }
        if (!hasCompletedCheck()) {
            return "unknown";
        }
        return updateAvailable ? "outdated" : "latest";
    }

    public String getUserAgent() {
        return userAgent;
    }

    public long getLastCheckedRateLimitRemaining() {
        return lastCheckedRateLimitRemaining;
    }

    private String fetchLatestVersion() throws Exception {
        URL url = new URL(MODRINTH_API_URL);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        try {
            int responseCode = connection.getResponseCode();
            captureRateLimitHeaders(connection);

            if (responseCode == 429) {
                plugin.getLogger().warning("Modrinth rate limit hit during update check. Will retry on next scheduled run.");
                return null;
            }
            if (responseCode != 200) {
                return null;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonArray()) {
                    return null;
                }
                JsonArray versions = parsed.getAsJsonArray();
                if (versions.isEmpty()) {
                    return null;
                }
                JsonObject first = versions.get(0).getAsJsonObject();
                JsonElement versionNumber = first.get("version_number");
                if (versionNumber == null || versionNumber.isJsonNull()) {
                    return null;
                }
                return versionNumber.getAsString();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void captureRateLimitHeaders(HttpURLConnection connection) {
        String remaining = connection.getHeaderField("X-Ratelimit-Remaining");
        if (remaining != null) {
            try {
                lastCheckedRateLimitRemaining = Long.parseLong(remaining);
                if (lastCheckedRateLimitRemaining < 2) {
                    plugin.getLogger().warning("Modrinth rate limit nearly exhausted (remaining=" + remaining + "). Update checks will pause until the limit resets.");
                }
            } catch (NumberFormatException ignored) {
            }
        }
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

    public void notifyPlayer(Player player, String currentVer, String latestVer) {
        if (!configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }
        player.sendMessage(lang.getMessage("update.available").formatted(latestVer, currentVer));
    }

    private void notifyAdmins(String currentVersion, String latestVersion) {
        if (!configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }

        final String finalMessage = lang.getMessage("update.available").formatted(latestVersion, currentVersion);
        final String consoleMsg = lang.getMessage("update.notify.console").formatted(latestVersion, currentVersion);

        Bukkit.getScheduler().runTask(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isNotifyTarget(player)) {
                    player.sendMessage(finalMessage);
                }
            }
            plugin.getLogger().info(consoleMsg);
        });
    }

    public boolean isNotifyTarget(Player player) {
        if (player.isOp()) {
            return true;
        }
        String perm = configManager.getUpdateNotifyPermission();
        return perm != null && !perm.isBlank() && player.hasPermission(perm);
    }

    private void notifyCheckFailed(String error) {
        if (!configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            String message = lang.getMessage("update.check.failed").formatted(error);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isNotifyTarget(player)) {
                    player.sendMessage(message);
                }
            }
        });
    }

    public void reportManualResult(CommandSender sender) {
        if (!configManager.isUpdateCheckEnabled()) {
            sender.sendMessage("§7[NetworkStorage] Update checks are disabled in config.yml.");
            return;
        }
        if (!hasCompletedCheck()) {
            sender.sendMessage(lang.getMessage("update.command.never_checked"));
            return;
        }
        if (updateAvailable) {
            sender.sendMessage(lang.getMessage("update.command.latest").formatted(latestVersion, currentVersion));
        } else {
            sender.sendMessage(lang.getMessage("update.up_to_date").formatted(currentVersion));
        }
    }

    public void reportChecking(CommandSender sender) {
        sender.sendMessage(lang.getMessage("update.command.checking"));
    }

    public static final class Result {

        private final Status status;
        private final String currentVersion;
        private final String latestVersion;
        private final boolean updateAvailable;
        private final String error;

        private Result(Status status, String currentVersion, String latestVersion, boolean updateAvailable, String error) {
            this.status = status;
            this.currentVersion = currentVersion;
            this.latestVersion = latestVersion;
            this.updateAvailable = updateAvailable;
            this.error = error;
        }

        public static Result success(String current, String latest, boolean available) {
            return new Result(Status.SUCCESS, current, latest, available, null);
        }

        public static Result failed(String error) {
            return new Result(Status.FAILURE, null, null, false, error);
        }

        public static Result disabled() {
            return new Result(Status.DISABLED, null, null, false, null);
        }

        public Status getStatus() {
            return status;
        }

        public String getCurrentVersion() {
            return currentVersion;
        }

        public String getLatestVersion() {
            return latestVersion;
        }

        public boolean isUpdateAvailable() {
            return updateAvailable;
        }

        public String getError() {
            return error;
        }
    }

    public enum Status {
        SUCCESS,
        FAILURE,
        DISABLED
    }
}