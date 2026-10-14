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
import java.nio.charset.StandardCharsets;
import java.math.BigInteger;
import java.util.function.Consumer;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker implements AutoCloseable {

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

    private CompletableFuture<Result> inFlight;
    private volatile boolean closed;
    private volatile Result lastResult;
    private volatile long lastSuccessAtMs;
    private final Fetcher fetcher;
    private final Consumer<Runnable> dispatch;

    @FunctionalInterface
    interface Fetcher { String fetch() throws Exception; }


    public UpdateChecker(NetworkStoragePlugin plugin) {
        this(plugin, null, null);
    }

    UpdateChecker(NetworkStoragePlugin plugin, Fetcher fetcher, Consumer<Runnable> dispatch) {
        this.plugin = plugin;
        this.fetcher = fetcher == null ? this::fetchLatestVersion : fetcher;
        this.dispatch = dispatch == null ? task -> Bukkit.getScheduler().runTaskAsynchronously(plugin, task) : dispatch;
        this.configManager = plugin.getConfigManager();
        this.lang = plugin.getLanguageManager();
        this.currentVersion = plugin.getDescription().getVersion();
        this.userAgent = NetworkStorageConstants.UPDATE_USER_AGENT_PREFIX
                + plugin.getDescription().getVersion()
                + " (https://github.com/DerMoha/NetworkStorage)";
    }

    UpdateChecker(String currentVersion, Fetcher fetcher, Consumer<Runnable> dispatch) {
        this.plugin = null;
        this.configManager = null;
        this.lang = null;
        this.currentVersion = currentVersion;
        this.userAgent = "NetworkStorage-test";
        this.fetcher = fetcher;
        this.dispatch = dispatch;
    }

    private boolean enabled() { return configManager == null || configManager.isUpdateCheckEnabled(); }

    public synchronized CompletableFuture<Result> checkNow() {
        if (closed) return CompletableFuture.completedFuture(Result.failed("Update checker retired"));
        if (!enabled()) {
            lastResult = Result.disabled();
            return CompletableFuture.completedFuture(lastResult);
        }
        if (inFlight != null) return inFlight;
        CompletableFuture<Result> future = new CompletableFuture<>();
        inFlight = future;
        try {
            dispatch.accept(() -> {
                Result result;
                try {
                    String fetched = fetcher.fetch();
                    result = fetched == null ? Result.failed("No compatible release returned by Modrinth")
                            : Result.success(currentVersion, fetched, isNewerVersion(fetched, currentVersion));
                } catch (Exception error) {
                    result = Result.failed(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
                }
                synchronized (this) {
                    if (closed || inFlight != future) return;
                    lastCheckedAtMs = System.currentTimeMillis();
                    lastResult = result;
                    if (result.getStatus() == Status.SUCCESS) {
                        latestVersion = result.getLatestVersion();
                        updateAvailable = result.isUpdateAvailable();
                        lastSuccessAtMs = lastCheckedAtMs;
                    }
                    inFlight = null;
                    if (result.getStatus() == Status.FAILURE) notifyCheckFailed(result.getError());
                    else if (result.isUpdateAvailable()) notifyAdmins(currentVersion, latestVersion);
                    future.complete(result);
                }
            });
        } catch (RuntimeException failure) {
            if (inFlight == future) inFlight = null;
            lastResult = Result.failed(failure.getMessage());
            lastCheckedAtMs = System.currentTimeMillis();
            future.complete(lastResult);
        }
        return future;
    }

    @Override
    public synchronized void close() {
        closed = true;
        CompletableFuture<Result> pending = inFlight;
        inFlight = null;
        if (pending != null) pending.complete(Result.failed("Update checker retired"));
    }

    public boolean isClosed() { return closed; }
    public Result getLastResult() { return lastResult; }
    public long getLastSuccessfulCheckAtMs() { return lastSuccessAtMs; }

    public boolean isUpdateAvailable() {
        return lastResult != null && lastResult.getStatus() == Status.SUCCESS && updateAvailable;
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
        if (!enabled()) {
            return "disabled";
        }
        if (!hasCompletedCheck()) {
            return "unknown";
        }
        if (lastResult != null && lastResult.getStatus() == Status.FAILURE) return lastSuccessAtMs > 0 ? "stale" : "failed";
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

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                JsonElement parsed = JsonParser.parseReader(reader);
                if (!parsed.isJsonArray()) {
                    return null;
                }
                JsonArray versions = parsed.getAsJsonArray();
                if (versions.isEmpty()) {
                    return null;
                }
                String best = null;
                for (JsonElement element : versions) {
                    JsonObject version = element.getAsJsonObject();
                    if (!version.has("version_type") || !"release".equals(version.get("version_type").getAsString())) continue;
                    if (version.has("game_versions") && !version.getAsJsonArray("game_versions").contains(
                            new com.google.gson.JsonPrimitive(Bukkit.getMinecraftVersion()))) continue;
                    JsonElement number = version.get("version_number");
                    if (number == null || number.isJsonNull()) continue;
                    String candidate = number.getAsString();
                    if (best == null || isNewerVersion(candidate, best)) best = candidate;
                }
                return best;
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

    /** Numeric dotted versions, optional v prefix and SemVer prerelease/build suffixes. */
    static boolean isNewerVersion(String latest, String current) {
        return compareVersions(latest, current) > 0;
    }

    private static int compareVersions(String first, String second) {
        String[] a = first.replaceFirst("^[vV]", "").split("\\+", 2)[0].split("-", 2);
        String[] b = second.replaceFirst("^[vV]", "").split("\\+", 2)[0].split("-", 2);
        String[] x = a[0].split("\\.");
        String[] y = b[0].split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int comparison = new BigInteger(i < x.length ? x[i] : "0")
                    .compareTo(new BigInteger(i < y.length ? y[i] : "0"));
            if (comparison != 0) return comparison;
        }
        if (a.length != b.length) return a.length == 1 ? 1 : -1;
        if (a.length == 1) return 0;
        x = a[1].split("\\."); y = b[1].split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            boolean numericX = x[i].matches("[0-9]+"), numericY = y[i].matches("[0-9]+");
            int comparison = numericX && numericY ? new BigInteger(x[i]).compareTo(new BigInteger(y[i]))
                    : numericX != numericY ? (numericX ? -1 : 1) : x[i].compareTo(y[i]);
            if (comparison != 0) return comparison;
        }
        return Integer.compare(x.length, y.length);
    }

    public void notifyPlayer(Player player, String currentVer, String latestVer) {
        if (closed || configManager == null || !configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }
        player.sendMessage(lang.getMessage("update.available").formatted(latestVer, currentVer));
    }

    private void notifyAdmins(String currentVersion, String latestVersion) {
        if (closed || configManager == null || !configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }

        final String finalMessage = lang.getMessage("update.available").formatted(latestVersion, currentVersion);
        final String consoleMsg = lang.getMessage("update.notify.console").formatted(latestVersion, currentVersion);

        scheduleNotification(() -> {
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
        if (closed || configManager == null || !configManager.shouldNotifyAdminsOnUpdate()) {
            return;
        }

        scheduleNotification(() -> {
            String message = lang.getMessage("update.check.failed").formatted(error);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (isNotifyTarget(player)) {
                    player.sendMessage(message);
                }
            }
        });
    }

    private synchronized void scheduleNotification(Runnable task) {
        if (closed) return;
        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!closed) task.run();
            });
        } catch (RuntimeException rejection) {
            if (!closed) plugin.getLogger().warning("Unable to schedule update notification: " + rejection.getMessage());
        }
    }

    public void reportManualResult(CommandSender sender) {
        reportManualResult(sender, lastResult);
    }

    public void reportManualResult(CommandSender sender, Result result) {
        if (closed) return;
        if (result != null && result.getStatus() == Status.FAILURE) {
            sender.sendMessage(lang.getMessage("update.check.failed").formatted(result.getError()));
            return;
        }
        if (result != null && result.getStatus() == Status.DISABLED) {
            sender.sendMessage("§7[NetworkStorage] Update checks are disabled in config.yml.");
            return;
        }
        if (result == null) {
            sender.sendMessage(lang.getMessage("update.command.never_checked"));
            return;
        }
        if (result.isUpdateAvailable()) {
            sender.sendMessage(lang.getMessage("update.command.latest").formatted(result.getLatestVersion(), result.getCurrentVersion()));
        } else {
            sender.sendMessage(lang.getMessage("update.up_to_date").formatted(result.getCurrentVersion()));
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