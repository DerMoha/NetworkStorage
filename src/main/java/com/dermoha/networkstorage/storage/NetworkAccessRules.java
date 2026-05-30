package com.dermoha.networkstorage.storage;

import org.bukkit.command.CommandSender;

public interface NetworkAccessRules {

    boolean isGlobalNetworkMode();

    boolean hasPrivilege(CommandSender sender, String permission);

    boolean isTrustSystemEnabled();
}
