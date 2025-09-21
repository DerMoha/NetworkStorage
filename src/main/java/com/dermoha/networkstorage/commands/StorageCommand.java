package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.storage.StorageNetwork;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class StorageCommand implements CommandExecutor {

    private final NetworkStoragePlugin plugin;

    public StorageCommand(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "This command can only be used by players!");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            sendHelpMessage(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "wand":
                handleWandCommand(player);
                break;

            case "info":
                handleInfoCommand(player);
                break;

            case "reset":
                handleResetCommand(player);
                break;

            case "help":
                sendHelpMessage(player);
                break;

            default:
                player.sendMessage(ChatColor.RED + "Unknown subcommand: " + subCommand);
                sendHelpMessage(player);
                break;
        }

        return true;
    }

    private void handleWandCommand(Player player) {
        if (!player.hasPermission("networkstorage.wand")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to get a storage wand!");
            return;
        }

        player.getInventory().addItem(WandListener.createStorageWand());
        player.sendMessage(ChatColor.GREEN + "You received a Storage Network Wand!");
        player.sendMessage(ChatColor.GRAY + "Left click chests to add them to your network.");
        player.sendMessage(ChatColor.GRAY + "Right click to remove, Shift+Right click to add terminals.");
    }

    private void handleInfoCommand(Player player) {
        StorageNetwork network = plugin.getNetworkManager().getPlayerNetwork(player);

        if (network == null) {
            player.sendMessage(ChatColor.YELLOW + "You don't have a storage network yet!");
            player.sendMessage(ChatColor.GRAY + "Use " + ChatColor.WHITE + "/storage wand" +
                    ChatColor.GRAY + " to get a wand and start building your network.");
            return;
        }

        player.sendMessage(ChatColor.GOLD + "=== Your Storage Network ===");
        player.sendMessage(ChatColor.YELLOW + "Network ID: " + ChatColor.WHITE + network.getNetworkId());
        player.sendMessage(ChatColor.YELLOW + "Connected Chests: " + ChatColor.WHITE +
                network.getChestLocations().size());
        player.sendMessage(ChatColor.YELLOW + "Access Terminals: " + ChatColor.WHITE +
                network.getTerminalLocations().size());

        // Calculate total items
        int totalItems = network.getNetworkItems().values().stream()
                .mapToInt(Integer::intValue).sum();
        int uniqueTypes = network.getNetworkItems().size();

        player.sendMessage(ChatColor.YELLOW + "Total Items: " + ChatColor.WHITE + formatNumber(totalItems));
        player.sendMessage(ChatColor.YELLOW + "Unique Types: " + ChatColor.WHITE + uniqueTypes);
    }

    private void handleResetCommand(Player player) {
        if (!player.hasPermission("networkstorage.reset")) {
            player.sendMessage(ChatColor.RED + "You don't have permission to reset your network!");
            return;
        }

        StorageNetwork network = plugin.getNetworkManager().getPlayerNetwork(player);

        if (network == null) {
            player.sendMessage(ChatColor.YELLOW + "You don't have a storage network to reset!");
            return;
        }

        player.sendMessage(ChatColor.RED + "Are you sure you want to reset your storage network?");
        player.sendMessage(ChatColor.RED + "This will remove all " + network.getChestLocations().size() +
                " chests and " + network.getTerminalLocations().size() + " terminals!");
        player.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.WHITE + "/storage confirm-reset" +
                ChatColor.YELLOW + " to confirm.");
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(ChatColor.GOLD + "=== NetworkStorage Commands ===");
        player.sendMessage(ChatColor.YELLOW + "/storage wand" + ChatColor.GRAY + " - Get a storage network wand");
        player.sendMessage(ChatColor.YELLOW + "/storage info" + ChatColor.GRAY + " - View your network information");
        player.sendMessage(ChatColor.YELLOW + "/storage reset" + ChatColor.GRAY + " - Reset your storage network");
        player.sendMessage(ChatColor.YELLOW + "/storage help" + ChatColor.GRAY + " - Show this help message");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "How to use:");
        player.sendMessage(ChatColor.GRAY + "1. Get a wand with /storage wand");
        player.sendMessage(ChatColor.GRAY + "2. Left-click chests to add them to your network");
        player.sendMessage(ChatColor.GRAY + "3. Shift+Right-click chests to make them terminals");
        player.sendMessage(ChatColor.GRAY + "4. Right-click terminals to access your storage!");
        player.sendMessage(ChatColor.GRAY + "5. Everyone can access any network terminal!");
    }

    private String formatNumber(int number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }
}