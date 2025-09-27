package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class NetworkCommand implements CommandExecutor {

    private final NetworkStoragePlugin plugin;

    public NetworkCommand(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            // TODO: Send help message
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                if (args.length < 2) {
                    player.sendMessage("Usage: /network create <name>");
                    return true;
                }
                plugin.getNetworkManager().createNetwork(player, args[1]);
                break;
            case "edit":
                if (args.length < 2) {
                    player.sendMessage("Usage: /network edit <name>");
                    return true;
                }
                plugin.getNetworkManager().editNetwork(player, args[1]);
                break;
            case "rename":
                if (args.length < 3) {
                    player.sendMessage("Usage: /network rename <oldName> <newName>");
                    return true;
                }
                plugin.getNetworkManager().renameNetwork(player, args[1], args[2]);
                break;
            default:
                // TODO: Send help message
                break;
        }

        return true;
    }
}
