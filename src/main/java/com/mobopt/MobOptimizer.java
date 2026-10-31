package com.mobopt;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class MobOptimizer extends JavaPlugin {
    private Settings settings;
    private SleepManager sleep;
    private GroupPathManager group;
    private CleanupManager cleanup;
    private MemoryGuard memory;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startAll();
    }

    @Override
    public void onDisable() {
        stopAll();
    }

    private void startAll() {
        settings = new Settings(getConfig());
        group = new GroupPathManager(this, settings);
        sleep = new SleepManager(this, settings, group::isFollower);
        cleanup = new CleanupManager(this, settings);
        memory = new MemoryGuard(this, settings, cleanup);

        if (settings.groupEnabled) group.start();
        if (settings.sleepEnabled) sleep.start();
        cleanup.start();
        if (settings.memEnabled) memory.start();
    }

    private void stopAll() {
        getServer().getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        if (sleep != null) sleep.stop();   // acorda todos os mobs
        if (group != null) group.stop();   // devolve o alvo aos seguidores
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        String sub = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "reload" -> {
                stopAll();
                reloadConfig();
                startAll();
                sender.sendMessage("MobOptimizer: configuração recarregada.");
            }
            case "mem" -> sender.sendMessage("MobOptimizer: " + MemoryGuard.report());
            case "clear" -> {
                int items = cleanup.cleanItems(0, settings.itemChunkCap);
                int mobs = cleanup.purgeMobs(1.0);
                sender.sendMessage("MobOptimizer: " + items + " itens e " + mobs + " mobs em excesso removidos.");
            }
            default -> sender.sendMessage(String.format(
                    "MobOptimizer: %d mobs dormindo | %d seguindo líder | TPS %.1f | %s",
                    sleep.count(), group.count(), Bukkit.getTPS()[0], MemoryGuard.report()));
        }
        return true;
    }
}
