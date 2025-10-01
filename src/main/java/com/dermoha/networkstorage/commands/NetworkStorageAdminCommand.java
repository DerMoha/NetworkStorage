package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import com.dermoha.networkstorage.managers.LanguageManager;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class NetworkStorageAdminCommand implements CommandExecutor {

    private final NetworkStoragePlugin plugin;
    private final LanguageManager lang;

    public NetworkStorageAdminCommand(NetworkStoragePlugin plugin) {
        this.plugin = plugin;
        this.lang = plugin.getLanguageManager();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            handleReloadCommand(sender);
            return true;
        }

        sender.sendMessage("§cUsage: /networkstorage reload");
        return true;
    }

    private void handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("networkstorage.admin")) {
            sender.sendMessage(lang.getMessage("no_permission_reload"));
            return;
        }

        sender.sendMessage(lang.getMessage("reload.start"));
        plugin.reload();
        sender.sendMessage(lang.getMessage("reload.success"));
    }
}
