package com.dermoha.networkstorage.commands;

import com.dermoha.networkstorage.NetworkStoragePlugin;
import org.bukkit.command.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** One public command surface; the original handlers retain their permission checks. */
public final class UnifiedCommand implements CommandExecutor, TabCompleter {
    private static final List<String> PLAYER = List.of("wand", "wireless", "info", "trust", "untrust", "reset", "confirm-reset", "cancel-reset");
    private static final List<String> NETWORK = List.of("create", "edit", "rename", "select", "list", "delete", "confirm-delete", "description");
    private static final List<String> ADMIN = List.of("reload", "list", "info", "inspect", "rescan", "config", "storage", "update");
    private static final Set<String> PREFIXES = Set.of("network", "storage", "networkstorage", "ns", "netstorage", "net");
    private final CommandExecutor storage, network, admin;
    private final TabCompleter storageTabs, networkTabs;
    private final Consumer<CommandSender> help;
    private final Predicate<CommandSender> isAdmin;

    public UnifiedCommand(NetworkStoragePlugin plugin, StorageCommand storage, NetworkCommand network, CommandExecutor admin) {
        this(storage, network, admin, storage, network,
                sender -> CommandHelp.send(plugin, sender),
                sender -> plugin.getConfigManager().hasPermission(sender, "networkstorage.admin"));
    }

    UnifiedCommand(CommandExecutor storage, CommandExecutor network, CommandExecutor admin,
                   TabCompleter storageTabs, TabCompleter networkTabs,
                   Consumer<CommandSender> help, Predicate<CommandSender> isAdmin) {
        this.storage = storage; this.network = network; this.admin = admin;
        this.storageTabs = storageTabs; this.networkTabs = networkTabs;
        this.help = help; this.isAdmin = isAdmin;
    }

    private static String[] normalize(String[] args) {
        int start = 0;
        while (start < args.length && PREFIXES.contains(args[start].toLowerCase(Locale.ROOT))) start++;
        return Arrays.copyOfRange(args, start, args.length);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] rawArgs) {
        String[] args = normalize(rawArgs);
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            help.accept(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            String[] adminArgs = Arrays.copyOfRange(args, 1, args.length);
            if (adminArgs.length == 0 || adminArgs[0].equalsIgnoreCase("help")) help.accept(sender);
            else admin.onCommand(sender, command, label, adminArgs);
        } else if (PLAYER.contains(sub)) {
            storage.onCommand(sender, command, label, args);
        } else if (NETWORK.contains(sub)) {
            network.onCommand(sender, command, label, args);
        } else if (ADMIN.contains(sub)) {
            // Retain unambiguous legacy admin shortcuts, e.g. /networkstorage reload.
            admin.onCommand(sender, command, label, args);
        } else {
            help.accept(sender);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] rawArgs) {
        String[] args = normalize(rawArgs);
        if (args.length == 0) args = new String[] {""};
        if (args[0].equalsIgnoreCase("admin")) {
            if (!isAdmin.test(sender) || args.length != 2) return List.of();
            return matching(ADMIN, args[1]);
        }
        if (args.length == 1) {
            List<String> options = new ArrayList<>(PLAYER);
            options.addAll(NETWORK); options.add("help");
            if (isAdmin.test(sender)) options.add("admin");
            return matching(options, args[0]);
        }
        if (PLAYER.contains(args[0].toLowerCase(Locale.ROOT))) return storageTabs.onTabComplete(sender, command, alias, args);
        if (NETWORK.contains(args[0].toLowerCase(Locale.ROOT))) return networkTabs.onTabComplete(sender, command, alias, args);
        return List.of();
    }

    private static List<String> matching(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(lower)).sorted().toList();
    }
}
