package de.damian.metrics;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

public class ReloadCommand implements CommandExecutor {

    private final ServerMetricsPlugin plugin;

    public ReloadCommand(ServerMetricsPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("mcmetrics.reload")) {
            sender.sendMessage("§cYou don't have permission to use this command!");
            return true;
        }

        plugin.reloadConfig();
        plugin.loadConfigValues();
        plugin.restartScheduler();

        sender.sendMessage("§aMC-Metrics config reloaded successfully!");
        return true;
    }
}
