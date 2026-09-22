package com.wilderop.hardcorespawn;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** /hardcoreadmin <reset <player>|reload> */
public final class HardcoreAdminCommand implements CommandExecutor, TabCompleter {
    private final HardcoreSpawn plugin;
    private final SessionManager sessions;

    public HardcoreAdminCommand(HardcoreSpawn plugin, SessionManager sessions) {
        this.plugin = plugin;
        this.sessions = sessions;
    }

    private HardcoreConfig config() {
        return sessions.getConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage("§cUsage: " + command.getUsage());
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reset" -> {
                if (args.length < 2) {
                    sender.sendMessage("§cUsage: /hardcoreadmin reset <player>");
                    return true;
                }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target != null) {
                    // Online: unchanged behavior — the run ends immediately
                    // and the player is restored on the spot.
                    if (!sessions.hasSession(target.getUniqueId())) {
                        sender.sendMessage(config().format("admin-no-player", Map.of("player", args[1])));
                        return true;
                    }
                    sessions.endRun(target.getUniqueId(), ExitCause.ADMIN_RESET);
                    sender.sendMessage(config().format("admin-reset", Map.of("player", target.getName())));
                    return true;
                }
                // Offline: resolve by name; the restore is queued for next join.
                OfflinePlayer offline = Bukkit.getOfflinePlayer(args[1]);
                if (!offline.hasPlayedBefore()) {
                    sender.sendMessage(config().format("admin-unknown-player", Map.of("player", args[1])));
                    return true;
                }
                UUID id = offline.getUniqueId();
                String name = offline.getName() != null ? offline.getName() : args[1];
                if (sessions.resetOfflinePlayer(id)) {
                    sender.sendMessage(config().format("admin-reset", Map.of("player", name)));
                } else {
                    sender.sendMessage(config().format("admin-nothing-to-reset", Map.of("player", name)));
                }
            }
            case "reload" -> {
                plugin.reloadHardcoreConfig();
                sender.sendMessage(config().format("admin-reload", Map.of()));
            }
            default -> sender.sendMessage("§cUsage: " + command.getUsage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reset", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
