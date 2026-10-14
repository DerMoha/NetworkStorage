package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.command.CommandSender;

public final class CommandHelp {
    private CommandHelp() {}

    public static void send(NetworkStoragePlugin plugin, CommandSender sender) {
        var lang = plugin.getLanguageManager();
        for (String key : new String[] {"title", "aliases", "wand", "wireless", "info", "select", "list",
                "create", "rename", "trust", "untrust", "description", "reset", "delete"}) {
            sender.sendMessage(lang.getMessage("commands.help." + key));
        }
        if (plugin.getConfigManager().hasPermission(sender, "networkstorage.admin")) {
            sender.sendMessage(lang.getMessage("commands.help.admin"));
        }
    }
}
