package com.dermoha.networkstorage.listeners;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.event.Listener;

/**
 * Compatibility listener. Physical hopper transfers are owned by vanilla;
 * NetworkContainerListener observes completed mutations and refreshes scans.
 */
public class HopperIntegrationListener implements Listener {
    public HopperIntegrationListener(NetworkStoragePlugin plugin) {
    }
}
