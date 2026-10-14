package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.UpdateChecker;
import com.dermoha.networkstorage.storage.Network;
import com.dermoha.networkstorage.storage.NetworkScanResult;
import com.dermoha.networkstorage.storage.NetworkScanStatus;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import java.io.File;
import java.util.Map;
import java.util.Locale;

/** Administrative dispatch and diagnostics for the current plugin runtime. */
public final class AdminCommand implements CommandExecutor {
    private final NetworkStoragePlugin plugin;

    public AdminCommand(NetworkStoragePlugin plugin) { this.plugin = plugin; }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (args.length == 0) {
                sender.sendMessage("§cUsage: /ns admin <reload|list|info|inspect|rescan|config|storage|update>");
                return true;
            }

            if (!plugin.getConfigManager().hasPermission(sender, "networkstorage.admin")) {
                sender.sendMessage(plugin.getLanguageManager().getMessage("no_permission_reload"));
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "reload":
                    sender.sendMessage(plugin.getLanguageManager().getMessage("reload.start"));
                    if (plugin.reload()) {
                        sender.sendMessage(plugin.getLanguageManager().getMessage("reload.success"));
                    } else {
                        sender.sendMessage("§cNetworkStorage reload failed; SQLite remains fail-closed.");
                    }
                    break;
                case "list":
                    handleAdminList(sender);
                    break;
                case "info":
                    handleAdminInfo(sender, args);
                    break;
                case "rescan":
                    handleAdminRescan(sender, args);
                    break;
                case "inspect":
                    handleAdminInspect(sender, args);
                    break;
                case "config":
                    if (!(sender instanceof Player)) {
                        sender.sendMessage("§cConfig editor requires a player.");
                        return true;
                    }
                    new com.dermoha.networkstorage.gui.ConfigEditorGUI(plugin).open((Player) sender);
                    break;
                case "storage":
                    handleAdminStorage(sender);
                    break;
                case "update":
                    handleAdminUpdate(sender);
                    break;
                default:
                    sender.sendMessage("§cUsage: /ns admin <reload|list|info|inspect|rescan|config|storage|update>");
            }
            return true;
    }

    private void handleAdminList(org.bukkit.command.CommandSender sender) {
        sender.sendMessage("§6=== All Networks ===");
        for (Network network : plugin.getNetworkManager().getAllNetworks()) {
            sender.sendMessage(String.format("§e%s §7(owner: §f%s§7, chests: §f%d§7, terminals: §f%d§7, senders: §f%d§7)",
                    network.getName(),
                    plugin.getNetworkManager().getNetworkOwnerName(network),
                    network.getChestLocations().size(),
                    network.getTerminalLocations().size(),
                    network.getSenderChestLocations().size()));
        }
    }

    private void handleAdminStorage(org.bukkit.command.CommandSender sender) {
        if (plugin.getNetworkManager().getStorageProvider() == null) {
            sender.sendMessage("§c[NetworkStorage] No storage provider is initialized.");
            return;
        }
        Map<String, Object> snap = plugin.getNetworkManager().getStorageProvider().snapshot();
        sender.sendMessage("§6§lNetworkStorage — Storage");
        sender.sendMessage("  §7persistence_status: §f" + plugin.getNetworkManager().getPersistenceStatus());
        sender.sendMessage("  §7persistence_pending: §f" + plugin.getNetworkManager().hasPendingPersistence());
        sender.sendMessage("  §7persistence_last_success_at: §f" + plugin.getNetworkManager().getLastPersistenceSuccessAt());
        if (!plugin.getNetworkManager().getLastPersistenceError().isBlank()) {
            sender.sendMessage("  §cpersistence_last_error: §f" + plugin.getNetworkManager().getLastPersistenceError());
        }
        for (Map.Entry<String, Object> entry : snap.entrySet()) {
            sender.sendMessage("  §7" + entry.getKey() + ": §f" + entry.getValue());
        }
        File lock = new File(plugin.getDataFolder(), "migration.lock");
        if (lock.exists()) {
            sender.sendMessage("  §c⚠ migration.lock present — migration did not finish cleanly");
        }
    }

    private void handleAdminUpdate(CommandSender sender) {
        UpdateChecker checker = plugin.getUpdateChecker();
        if (checker == null || checker.isClosed()) {
            sender.sendMessage("§c[NetworkStorage] Update checker is not initialized.");
            return;
        }
        checker.reportChecking(sender);
        checker.checkNow().whenComplete((result, error) -> {
            if (!plugin.isEnabled() || checker.isClosed() || plugin.getUpdateChecker() != checker) return;
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!plugin.isEnabled() || checker.isClosed() || plugin.getUpdateChecker() != checker) return;
                    if (error != null) {
                        sender.sendMessage("§c[NetworkStorage] Update check failed: " + error.getMessage());
                    } else if (result == null) {
                        sender.sendMessage("§c[NetworkStorage] Update check returned no result.");
                    } else {
                        checker.reportManualResult(sender, result);
                    }
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException ignored) {
                // Disable can race an HTTP completion after the ownership check.
            }
        });
    }

    private void handleAdminInfo(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ns admin info <network>");
            return;
        }
        Network network = plugin.getNetworkManager().getNetwork(args[1]);
        if (network == null) {
            sender.sendMessage("§cNetwork not found: " + args[1]);
            return;
        }
        NetworkScanResult scan = plugin.getNetworkManager().getNetworkScan(network, true,
                result -> sendAdminInfo(sender, network, result));
        sender.sendMessage("§7Network scan pending; current stored-item totals are not yet authoritative.");
        if (scan.status() == NetworkScanStatus.COMPLETE) {
            sendAdminInfo(sender, network, scan);
        }
    }

    private void sendAdminInfo(org.bukkit.command.CommandSender sender,
                               Network network,
                               NetworkScanResult scan) {
        sender.sendMessage("§6=== Network " + network.getName() + " ===");
        sender.sendMessage("§eOwner: §f" + plugin.getNetworkManager().getNetworkOwnerName(network));
        sender.sendMessage("§eChests: §f" + network.getChestLocations().size());
        sender.sendMessage("§eTerminals: §f" + network.getTerminalLocations().size());
        sender.sendMessage("§eSender chests: §f" + network.getSenderChestLocations().size());
        sender.sendMessage("§eTrusted: §f" + network.getTrustedPlayers().size());
        sender.sendMessage("§eDescription: §f" + (network.getDescription().isEmpty() ? "(none)" : network.getDescription()));
        sender.sendMessage("§eScan status: §f" + scan.status());
        sender.sendMessage("§eContainers found: §f" + scan.containersFound() + "/" + scan.registeredLocations());
        if (scan.hasAuthoritativeData()) {
            sender.sendMessage("§eStored items: §f" + String.format(java.util.Locale.ROOT, "%,d", scan.totalItems()));
            sender.sendMessage("§eUnique item types: §f" + scan.uniqueTypes());
        } else if (network.hasLastCompleteScan()) {
            sender.sendMessage("§eStored items: §f" + String.format(java.util.Locale.ROOT, "%,d", scan.totalItems()) + " §7(stale last complete scan)");
            sender.sendMessage("§eUnique item types: §f" + scan.uniqueTypes() + " §7(stale last complete scan)");
        } else {
            sender.sendMessage("§eStored items: §6scan pending/incomplete");
            sender.sendMessage("§eUnique item types: §6scan pending/incomplete");
        }
        for (String warning : scan.warnings()) {
            sender.sendMessage("§cWarning: " + warning);
        }
    }

    private void handleAdminRescan(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 2) {
            plugin.getNetworkManager().rescanAllNetworks();
            sender.sendMessage("§7Scheduled scans for all " + plugin.getNetworkManager().getAllNetworks().size() + " network(s).");
            return;
        }

        Network network = com.dermoha.networkstorage.storage.NetworkNames.find(
                plugin.getNetworkManager().getAllNetworks(), args[1]);
        if (network == null) {
            sender.sendMessage("§cNetwork not found: " + args[1]);
            return;
        }
        plugin.getNetworkManager().rescanNetwork(network);
        sender.sendMessage("§7Scheduled a fresh scan for network " + network.getName() + ".");
    }

    private void handleAdminInspect(org.bukkit.command.CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cUsage: /ns admin inspect <network>");
            return;
        }
        Network network = plugin.getNetworkManager().getNetwork(args[1]);
        if (network == null) {
            sender.sendMessage("§cNetwork not found: " + args[1]);
            return;
        }
        sender.sendMessage("§6=== Locations for " + network.getName() + " ===");
        for (org.bukkit.Location loc : network.getChestLocations()) {
            sender.sendMessage("§7Chest: §f" + formatLocation(loc));
        }
        for (org.bukkit.Location loc : network.getTerminalLocations()) {
            sender.sendMessage("§bTerminal: §f" + formatLocation(loc));
        }
        for (org.bukkit.Location loc : network.getSenderChestLocations()) {
            sender.sendMessage("§aSender: §f" + formatLocation(loc));
        }
    }

    private String formatLocation(org.bukkit.Location loc) {
        if (loc == null) {
            return "<invalid-location>";
        }
        String world = loc.getWorld() == null ? "<unavailable-world>" : loc.getWorld().getName();
        return world + " " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
    }

}
