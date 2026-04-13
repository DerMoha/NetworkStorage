package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.listeners.WandListener;
import com.dermoha.networkstorage.listeners.WirelessTerminalListener;
import com.dermoha.networkstorage.managers.ConfigManager;
import com.dermoha.networkstorage.managers.LanguageManager;
import com.dermoha.networkstorage.storage.Network;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.StringUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

public class StorageCommand implements CommandExecutor, TabCompleter {

    private static final long RESET_CONFIRMATION_WINDOW_MS = 30_000L;

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;
    private final Map<UUID, PendingReset> pendingResets = new HashMap<>();
    private static final List<String> SUBCOMMANDS = Arrays.asList("wand", "info", "reset", "confirm-reset", "cancel-reset", "help", "trust", "untrust", "wireless");

    public StorageCommand(NetworkStoragePlugin plugin) {
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
            case "wand":
                handleWandCommand(player);
                break;
            case "info":
                handleInfoCommand(player);
                break;
            case "reset":
                handleResetCommand(player);
                break;
            case "confirm-reset":
                handleConfirmResetCommand(player);
                break;
            case "cancel-reset":
                handleCancelResetCommand(player);
                break;
            case "trust":
                handleTrustCommand(player, args);
                break;
            case "untrust":
                handleUntrustCommand(player, args);
                break;
            case "wireless":
                handleWirelessCommand(player);
                break;
            case "help":
            default:
                sendHelpMessage(player);
                break;
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("trust") || args[0].equalsIgnoreCase("untrust"))) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> StringUtil.startsWithIgnoreCase(name, args[1]))
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private void handleTrustCommand(Player player, String[] args) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("trust.global_mode_disabled"));
            return;
        }
        if (!plugin.getConfigManager().isTrustSystemEnabled()) {
            player.sendMessage(lang.getMessage("trust.system_disabled"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(lang.getMessage("trust.usage"));
            return;
        }

        Network network = plugin.getNetworkManager().getPlayerNetwork(player);
        if (network == null) {
            player.sendMessage(lang.getMessage("no_network"));
            return;
        }

        if (!network.getOwner().equals(player.getUniqueId())) {
            player.sendMessage(lang.getMessage("trust.not_owner"));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(String.format(lang.getMessage("trust.player_not_found"), args[1]));
            return;
        }

        if (target.getUniqueId().equals(player.getUniqueId())) {
            player.sendMessage(lang.getMessage("trust.self"));
            return;
        }

        if (network.isTrusted(target.getUniqueId())) {
            player.sendMessage(String.format(lang.getMessage("trust.already_trusted"), target.getName()));
            return;
        }

        network.addTrustedPlayer(target.getUniqueId());
        player.sendMessage(String.format(lang.getMessage("trust.success"), target.getName()));

        if (target.isOnline()) {
            ((Player) target).sendMessage(String.format(lang.getMessage("trust.notification"), player.getName()));
        }
    }

    private void handleUntrustCommand(Player player, String[] args) {
        if (plugin.getConfigManager().getNetworkMode() == ConfigManager.NetworkMode.GLOBAL) {
            player.sendMessage(lang.getMessage("trust.global_mode_disabled"));
            return;
        }
        if (!plugin.getConfigManager().isTrustSystemEnabled()) {
            player.sendMessage(lang.getMessage("trust.system_disabled"));
            return;
        }
        if (args.length < 2) {
            player.sendMessage(lang.getMessage("untrust.usage"));
            return;
        }

        Network network = plugin.getNetworkManager().getPlayerNetwork(player);
        if (network == null) {
            player.sendMessage(lang.getMessage("no_network"));
            return;
        }

        if (!network.getOwner().equals(player.getUniqueId())) {
            player.sendMessage(lang.getMessage("trust.not_owner"));
            return;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        if (!target.hasPlayedBefore() && !target.isOnline()) {
            player.sendMessage(String.format(lang.getMessage("trust.player_not_found"), args[1]));
            return;
        }

        if (!network.isTrusted(target.getUniqueId())) {
            player.sendMessage(String.format(lang.getMessage("untrust.not_trusted"), target.getName()));
            return;
        }

        network.removeTrustedPlayer(target.getUniqueId());
        player.sendMessage(String.format(lang.getMessage("untrust.success"), target.getName()));

        if (target.isOnline()) {
            ((Player) target).sendMessage(String.format(lang.getMessage("untrust.notification"), player.getName()));
        }
    }

    private void handleWandCommand(Player player) {
        if (!player.hasPermission("networkstorage.wand")) {
            player.sendMessage(lang.getMessage("no_permission_wand"));
            return;
        }

        ItemStack wand = WandListener.createStorageWand(plugin);
        String wandName = wand.getItemMeta().getDisplayName();

        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack slot = player.getInventory().getItem(i);
            if (slot != null && slot.getType() == Material.BLAZE_ROD) {
                ItemMeta meta = slot.getItemMeta();
                if (meta != null && meta.hasDisplayName() && meta.getDisplayName().equals(wandName)) {
                    player.getInventory().setItem(i, wand);
                    player.sendMessage(lang.getMessage("received_wand"));
                    player.sendMessage(lang.getMessage("wand_left_click"));
                    player.sendMessage(lang.getMessage("wand_right_click"));
                    return;
                }
            }
        }

        player.getInventory().addItem(wand);
        player.sendMessage(lang.getMessage("received_wand"));
        player.sendMessage(lang.getMessage("wand_left_click"));
        player.sendMessage(lang.getMessage("wand_right_click"));
    }

    private void handleWirelessCommand(Player player) {
        if (!player.hasPermission("networkstorage.wireless")) {
            player.sendMessage(lang.getMessage("no_permission_wireless"));
            return;
        }

        player.getInventory().addItem(WirelessTerminalListener.createWirelessTerminal(plugin));
        player.sendMessage(lang.getMessage("received_wireless_terminal"));
    }

    private void handleInfoCommand(Player player) {
        Network network = plugin.getNetworkManager().getPlayerNetwork(player);

        if (network == null) {
            player.sendMessage(lang.getMessage("no_network"));
            player.sendMessage(lang.getMessage("get_wand_hint"));
            return;
        }

        String activeWirelessNetwork = plugin.getNetworkManager().getSelectedWirelessNetworkName(player);

        player.sendMessage(lang.getMessage("network_info_title"));
        player.sendMessage(String.format(lang.getMessage("active_network"), network.getName()));
        player.sendMessage(String.format(lang.getMessage("network_id"), network.getName()));
        player.sendMessage(String.format(lang.getMessage("connected_chests"), network.getChestLocations().size()));
        player.sendMessage(String.format(lang.getMessage("access_terminals"), network.getTerminalLocations().size()));
        player.sendMessage(String.format(lang.getMessage("connected_sender_chests"), network.getSenderChestLocations().size()));
        if (activeWirelessNetwork != null && !activeWirelessNetwork.equals(network.getName())) {
            player.sendMessage(String.format(lang.getMessage("wireless_active_network"), activeWirelessNetwork));
        }

        long totalItems = network.getNetworkItems().values().stream().mapToLong(Integer::longValue).sum();
        int uniqueTypes = network.getNetworkItems().size();
        double capacity = network.getCapacityPercent();

        player.sendMessage(String.format(lang.getMessage("total_items"), formatNumber(totalItems)));
        player.sendMessage(String.format(lang.getMessage("unique_types"), uniqueTypes));
        player.sendMessage(String.format(lang.getMessage("terminal.info.capacity"), String.format("%.1f%%", capacity)));
    }

    private void handleResetCommand(Player player) {
        if (!player.hasPermission("networkstorage.reset")) {
            player.sendMessage(lang.getMessage("no_permission_reset"));
            return;
        }

        Network network = plugin.getNetworkManager().getPlayerNetwork(player);

        if (network == null) {
            player.sendMessage(lang.getMessage("no_network_reset"));
            return;
        }

        if (!network.getOwner().equals(player.getUniqueId())) {
            player.sendMessage(lang.getMessage("trust.not_owner"));
            return;
        }

        pendingResets.put(player.getUniqueId(), new PendingReset(network.getName(), System.currentTimeMillis() + RESET_CONFIRMATION_WINDOW_MS));

        player.sendMessage(lang.getMessage("reset_confirm_1"));
        player.sendMessage(String.format(lang.getMessage("reset_confirm_2"), network.getChestLocations().size(), network.getTerminalLocations().size(), network.getSenderChestLocations().size()));
        player.sendMessage(lang.getMessage("reset_confirm_3"));
    }

    private void handleConfirmResetCommand(Player player) {
        PendingReset pendingReset = pendingResets.get(player.getUniqueId());
        if (pendingReset == null) {
            player.sendMessage(lang.getMessage("reset.no_pending"));
            return;
        }

        if (pendingReset.expiresAt() < System.currentTimeMillis()) {
            pendingResets.remove(player.getUniqueId());
            player.sendMessage(lang.getMessage("reset.expired"));
            return;
        }

        Network network = plugin.getNetworkManager().findOwnedNetwork(player, pendingReset.networkName());
        if (network == null) {
            pendingResets.remove(player.getUniqueId());
            player.sendMessage(lang.getMessage("reset.not_found"));
            return;
        }

        int chestCount = network.getChestLocations().size();
        int terminalCount = network.getTerminalLocations().size();
        int senderChestCount = network.getSenderChestLocations().size();

        plugin.getNetworkManager().resetNetwork(network);
        pendingResets.remove(player.getUniqueId());
        player.sendMessage(String.format(lang.getMessage("reset.success"), chestCount, terminalCount, senderChestCount, network.getName()));
    }

    private void handleCancelResetCommand(Player player) {
        if (pendingResets.remove(player.getUniqueId()) == null) {
            player.sendMessage(lang.getMessage("reset.no_pending"));
            return;
        }

        player.sendMessage(lang.getMessage("reset.cancelled"));
    }

    private void sendHelpMessage(Player player) {
        player.sendMessage(lang.getMessage("help_title"));
        player.sendMessage(lang.getMessage("help_wand"));
        player.sendMessage(lang.getMessage("help_info"));
        player.sendMessage(lang.getMessage("help_reset"));
        player.sendMessage(lang.getMessage("help_confirm_reset"));
        player.sendMessage(lang.getMessage("help_cancel_reset"));
        player.sendMessage(lang.getMessage("help_trust"));
        player.sendMessage(lang.getMessage("help_untrust"));
        player.sendMessage(lang.getMessage("help_wireless"));
        player.sendMessage(lang.getMessage("help_help"));
        player.sendMessage("");
        player.sendMessage(lang.getMessage("help_usage"));
        player.sendMessage(lang.getMessage("help_step1"));
        player.sendMessage(lang.getMessage("help_step2"));
        player.sendMessage(lang.getMessage("help_step3"));
        player.sendMessage(lang.getMessage("help_step4"));
        player.sendMessage(lang.getMessage("help_step5"));
    }

    private String formatNumber(long number) {
        if (number >= 1000000) {
            return String.format("%.1fM", number / 1000000.0);
        } else if (number >= 1000) {
            return String.format("%.1fK", number / 1000.0);
        } else {
            return String.valueOf(number);
        }
    }

    private record PendingReset(String networkName, long expiresAt) {
    }
}
