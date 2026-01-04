package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.gui.NetworkSelectorGUI;
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
import java.util.stream.Collectors;

public class NetworkCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = Arrays.asList(
            "create", "delete", "remove", "list", "switch", "select", "gui", "menu", "rename", "help");

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

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
            sendHelpMessage(player);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "create":
                handleCreate(player, args);
                break;
            case "delete":
            case "remove":
                handleDelete(player, args);
                break;
            case "list":
                handleList(player);
                break;
            case "switch":
            case "select":
                handleSwitch(player, args);
                break;
            case "gui":
            case "menu":
                handleGUI(player);
                break;
            case "rename":
                handleRename(player, args);
                break;
            case "help":
                sendHelpMessage(player);
                break;
            default:
                player.sendMessage(lang.getMessage("unknown_subcommand").replace("%s", subCommand));
                sendHelpMessage(player);
                break;
        }

        return true;
    }

    private void handleCreate(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(lang.getMessage("network.create.usage"));
            return;
        }
        plugin.getNetworkManager().createNetwork(player, args[1]);
    }

    private void handleDelete(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(lang.getMessage("network.delete.usage"));
            return;
        }

        String networkName = args[1];
        List<Network> playerNetworks = plugin.getNetworkManager().getPlayerNetworks(player);

        if (playerNetworks.size() <= 1) {
            player.sendMessage(lang.getMessage("network.cannot_delete_last"));
            return;
        }

        Network network = plugin.getNetworkManager().getPlayerNetworkByName(player, networkName);
        if (network == null) {
            player.sendMessage(lang.getMessage("network.not_found").replace("%s", networkName));
            return;
        }

        player.sendMessage(lang.getMessage("network.delete.confirm1").replace("%s", networkName));
        player.sendMessage(lang.getMessage("network.delete.confirm2")
                .replace("%chests%", String.valueOf(network.getChestLocations().size()))
                .replace("%terminals%", String.valueOf(network.getTerminalLocations().size())));
        player.sendMessage(lang.getMessage("network.delete.confirm3").replace("%s", networkName));

        plugin.getSearchManager().startDeletingNetwork(player, networkName);
    }

    private void handleList(Player player) {
        List<Network> playerNetworks = plugin.getNetworkManager().getPlayerNetworks(player);
        Network activeNetwork = plugin.getNetworkManager().getPlayerNetwork(player);

        if (playerNetworks.isEmpty()) {
            player.sendMessage(lang.getMessage("network.no_networks"));
            return;
        }

        player.sendMessage(lang.getMessage("network.list.title"));
        for (Network network : playerNetworks) {
            boolean isActive = activeNetwork != null && activeNetwork.getName().equals(network.getName());
            String prefix = isActive ? lang.getMessage("network.list.active_prefix") : "  ";

            player.sendMessage(prefix + "§e" + network.getName() + " §7- " +
                    network.getChestLocations().size() + " chests, " +
                    network.getTerminalLocations().size() + " terminals");
        }
        player.sendMessage(lang.getMessage("network.list.footer"));
    }

    private void handleSwitch(Player player, String[] args) {
        if (args.length < 2) {
            player.sendMessage(lang.getMessage("network.switch.usage"));
            return;
        }

        String networkName = args[1];
        if (plugin.getNetworkManager().setActiveNetwork(player, networkName)) {
            player.sendMessage(lang.getMessage("network.switched").replace("%s", networkName));
        } else {
            player.sendMessage(lang.getMessage("network.not_found").replace("%s", networkName));
        }
    }

    private void handleGUI(Player player) {
        NetworkSelectorGUI gui = new NetworkSelectorGUI(player, plugin);
        gui.open();
    }

    private void handleRename(Player player, String[] args) {
        if (args.length < 3) {
            player.sendMessage(lang.getMessage("network.rename.usage"));
            return;
        }
        plugin.getNetworkManager().renameNetwork(player, args[1], args[2]);
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(lang.getMessage("network.help.title"));
        player.sendMessage(lang.getMessage("network.help.create"));
        player.sendMessage(lang.getMessage("network.help.delete"));
        player.sendMessage(lang.getMessage("network.help.list"));
        player.sendMessage(lang.getMessage("network.help.switch"));
        player.sendMessage(lang.getMessage("network.help.gui"));
        player.sendMessage(lang.getMessage("network.help.rename"));
        player.sendMessage(lang.getMessage("network.help.help"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }

        // For commands that need a network name as second argument
        if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("delete") || subCommand.equals("remove") ||
                    subCommand.equals("switch") || subCommand.equals("select") ||
                    subCommand.equals("rename")) {
                List<String> networkNames = plugin.getNetworkManager().getPlayerNetworks(player)
                        .stream()
                        .map(Network::getName)
                        .collect(Collectors.toList());
                return StringUtil.copyPartialMatches(args[1], networkNames, new ArrayList<>());
            }
        }

        return Collections.emptyList();
    }
}
