package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.NetworkSelectGUI;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class NetworkCommand implements CommandExecutor, TabCompleter {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private static final List<String> SUBCOMMANDS = Arrays.asList("create", "edit", "rename", "select", "list");

    public NetworkCommand(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(lang.getMessage("only_players"));
            return true;
        }

        Player player = (Player) sender;

        if (args.length == 0) {
            player.sendMessage(lang.getMessage("network.help.title"));
            player.sendMessage(lang.getMessage("network.help.create"));
            player.sendMessage(lang.getMessage("network.help.edit"));
            player.sendMessage(lang.getMessage("network.help.rename"));
            player.sendMessage(lang.getMessage("network.help.select"));
            player.sendMessage(lang.getMessage("network.help.list"));
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                if (!plugin.getConfigManager().hasPermission(player, "networkstorage.network.create")) {
                    player.sendMessage(lang.getMessage("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(lang.getMessage("network.create.usage"));
                    return true;
                }
                plugin.getNetworkManager().createNetwork(player, args[1]);
                break;
            case "edit":
                if (!plugin.getConfigManager().hasPermission(player, "networkstorage.network.edit")) {
                    player.sendMessage(lang.getMessage("no-permission"));
                    return true;
                }
                if (args.length < 2) {
                    player.sendMessage(lang.getMessage("network.edit.usage"));
                    return true;
                }
                plugin.getNetworkManager().editNetwork(player, args[1]);
                break;
            case "rename":
                if (!plugin.getConfigManager().hasPermission(player, "networkstorage.network.rename")) {
                    player.sendMessage(lang.getMessage("no-permission"));
                    return true;
                }
                if (args.length < 3) {
                    player.sendMessage(lang.getMessage("network.rename.usage"));
                    return true;
                }
                plugin.getNetworkManager().renameNetwork(player, args[1], args[2]);
                break;
            case "select":
                handleSelectCommand(player, args);
                break;
            case "list":
                handleListCommand(player);
                break;
            default:
                player.sendMessage(String.format(lang.getMessage("unknown_subcommand"), subCommand));
                player.sendMessage(lang.getMessage("network.help.title"));
                player.sendMessage(lang.getMessage("network.help.create"));
                player.sendMessage(lang.getMessage("network.help.edit"));
                player.sendMessage(lang.getMessage("network.help.rename"));
                player.sendMessage(lang.getMessage("network.help.select"));
                player.sendMessage(lang.getMessage("network.help.list"));
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }

        if (args.length == 2 && (args[0].equalsIgnoreCase("select") || args[0].equalsIgnoreCase("edit"))) {
            List<String> networkNames = plugin.getNetworkManager().getOwnedNetworks(player).stream()
                    .map(Network::getName)
                    .toList();
            return StringUtil.copyPartialMatches(args[1], networkNames, new ArrayList<>());
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("rename")) {
            List<String> networkNames = plugin.getNetworkManager().getOwnedNetworks(player).stream()
                    .map(Network::getName)
                    .toList();
            return StringUtil.copyPartialMatches(args[1], networkNames, new ArrayList<>());
        }

        return Collections.emptyList();
    }

    private void handleSelectCommand(Player player, String[] args) {
        if (plugin.getConfigManager().getNetworkMode() == com.dermoha.networkstorage.managers.ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("network.select.global_mode"));
            return;
        }

        List<Network> ownedNetworks = plugin.getNetworkManager().getOwnedNetworks(player);
        if (ownedNetworks.isEmpty()) {
            player.sendMessage(lang.getMessage("network.select.none"));
            return;
        }

        if (args.length < 2) {
            new NetworkSelectGUI(player, ownedNetworks, plugin).open();
            return;
        }

        if (!plugin.getNetworkManager().selectPlayerNetwork(player, args[1])) {
            player.sendMessage(String.format(lang.getMessage("network.select.not_found"), args[1]));
            return;
        }

        player.sendMessage(String.format(lang.getMessage("network.select.success"), plugin.getNetworkManager().getPlayerNetwork(player).getName()));
    }

    private void handleListCommand(Player player) {
        List<Network> ownedNetworks = plugin.getNetworkManager().getOwnedNetworks(player);
        if (ownedNetworks.isEmpty()) {
            player.sendMessage(lang.getMessage("network.select.none"));
            return;
        }

        String activeNetworkName = plugin.getNetworkManager().getPlayerNetwork(player).getName();
        player.sendMessage(lang.getMessage("network.list.title"));
        for (Network network : ownedNetworks) {
            String marker = network.getName().equals(activeNetworkName)
                    ? lang.getMessage("network.list.active_marker")
                    : lang.getMessage("network.list.inactive_marker");
            player.sendMessage(String.format(lang.getMessage("network.list.entry"), marker, network.getName(), network.getChestLocations().size(), network.getTerminalLocations().size(), network.getSenderChestLocations().size()));
        }
    }
}
