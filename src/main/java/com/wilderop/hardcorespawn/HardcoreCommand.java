package com.wilderop.hardcorespawn;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

/** /hardcore [confirm|quit|status] */
public final class HardcoreCommand implements CommandExecutor, TabCompleter {
    private final SessionManager sessions;

    public HardcoreCommand(SessionManager sessions) {
        this.sessions = sessions;
    }

    private HardcoreConfig config() {
        return sessions.getConfig();
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(config().prefix() + config().message("only-players"));
            return true;
        }
        if (args.length == 0) {
            sessions.requestStart(player);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "confirm" -> sessions.confirmStart(player);
            case "quit" -> {
                if (sessions.hasSession(player.getUniqueId())) {
                    sessions.endRun(player.getUniqueId(), ExitCause.QUIT);
                } else {
                    player.sendMessage(config().format("no-session", Map.of()));
                }
            }
            case "status" -> sessions.sendStatus(player);
            default -> player.sendMessage("§cUsage: " + command.getUsage());
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("confirm", "quit", "status").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}
