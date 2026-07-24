package com.dermoha.networkstorage.integrations;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.UpdateChecker;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

public class UpdatePlaceholderExpansion extends PlaceholderExpansion {

    private final NetworkStoragePlugin plugin;

    public UpdatePlaceholderExpansion(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "networkstorage";
    }

    @Override
    public @NotNull String getAuthor() {
        return "DerMoha";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public boolean canRegister() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        UpdateChecker checker = plugin.getUpdateChecker();
        if (checker == null) {
            return "";
        }
        String key = params.toLowerCase();
        return switch (key) {
            case "update_available" -> String.valueOf(checker.isUpdateAvailable());
            case "update_current" -> {
                String current = checker.getCurrentVersion();
                yield current != null ? current : "";
            }
            case "update_latest" -> {
                if (!checker.hasCompletedCheck()) {
                    yield "";
                }
                yield checker.getLatestVersion() != null ? checker.getLatestVersion() : "";
            }
            default -> null;
        };
    }
}