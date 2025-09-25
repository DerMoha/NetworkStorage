package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.StorageNetwork;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class StorageCommand implements CommandExecutor {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

    public StorageCommand(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.get("only_players"));
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
                player.sendMessage(lang.get("unknown_subcommand", subCommand));
                sendHelpMessage(player);
                break;
        }

        return true;
    }

    private void handleWandCommand(Player player) {
        if (!player.hasPermission("networkstorage.wand")) {
            player.sendMessage(lang.get("no_permission_wand"));
            return;
        }

        player.getInventory().addItem(WandListener.createStorageWand());
        player.sendMessage(lang.get("received_wand"));
        player.sendMessage(lang.get("wand_left_click"));
        player.sendMessage(lang.get("wand_right_click"));
    }

    private void handleInfoCommand(Player player) {
        StorageNetwork network = plugin.getNetworkManager().getPlayerNetwork(player);

        if (network == null) {
            player.sendMessage(lang.get("no_network"));
            player.sendMessage(lang.get("get_wand_hint"));
            return;
        }

        player.sendMessage(lang.get("network_info_title"));
        player.sendMessage(lang.get("network_id", network.getNetworkId()));
        player.sendMessage(lang.get("connected_chests", network.getChestLocations().size()));
        player.sendMessage(lang.get("access_terminals", network.getTerminalLocations().size()));

        // Calculate total items
        int totalItems = network.getNetworkItems().values().stream()
                .mapToInt(Integer::intValue).sum();
        int uniqueTypes = network.getNetworkItems().size();

        player.sendMessage(lang.get("total_items", formatNumber(totalItems)));
        player.sendMessage(lang.get("unique_types", uniqueTypes));
    }

    private void handleResetCommand(Player player) {
        if (!player.hasPermission("networkstorage.reset")) {
            player.sendMessage(lang.get("no_permission_reset"));
            return;
        }

        StorageNetwork network = plugin.getNetworkManager().getPlayerNetwork(player);

        if (network == null) {
            player.sendMessage(lang.get("no_network_reset"));
            return;
        }

        player.sendMessage(lang.get("reset_confirm_1"));
        player.sendMessage(lang.get("reset_confirm_2", network.getChestLocations().size(), network.getTerminalLocations().size()));
        player.sendMessage(lang.get("reset_confirm_3"));
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(lang.get("help_title"));
        player.sendMessage(lang.get("help_wand"));
        player.sendMessage(lang.get("help_info"));
        player.sendMessage(lang.get("help_reset"));
        player.sendMessage(lang.get("help_help"));
        player.sendMessage("");
        player.sendMessage(lang.get("help_usage"));
        player.sendMessage(lang.get("help_step1"));
        player.sendMessage(lang.get("help_step2"));
        player.sendMessage(lang.get("help_step3"));
        player.sendMessage(lang.get("help_step4"));
        player.sendMessage(lang.get("help_step5"));
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