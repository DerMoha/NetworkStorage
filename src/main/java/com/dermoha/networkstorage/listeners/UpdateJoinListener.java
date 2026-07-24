package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.UpdateChecker;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public class UpdateJoinListener implements Listener {

    private final NetworkStoragePlugin plugin;
    private final UpdateChecker updateChecker;

    public UpdateJoinListener(NetworkStoragePlugin plugin, UpdateChecker updateChecker) {
        this.plugin = plugin;
        this.updateChecker = updateChecker;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (updateChecker == null) {
            return;
        }
        if (!plugin.getConfigManager().isUpdateCheckEnabled()) {
            return;
        }
        if (!plugin.getConfigManager().shouldNotifyAdminsOnUpdate()) {
            return;
        }
        if (!updateChecker.hasCompletedCheck()) {
            return;
        }
        if (!updateChecker.isUpdateAvailable()) {
            return;
        }
        if (!updateChecker.isNotifyTarget(event.getPlayer())) {
            return;
        }
        updateChecker.notifyPlayer(
                event.getPlayer(),
                updateChecker.getCurrentVersion(),
                updateChecker.getLatestVersion()
        );
    }
}