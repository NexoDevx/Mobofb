package com.mobopt;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.List;

/**
 * Monitora a heap. Usa o uso "pós-GC" (conjunto vivo) para não disparar por lixo
 * que o coletor ainda não limpou. No nível crítico: limpa itens, aperta limites
 * de mobs e descarrega chunks parados, para o GC ter o que recolher.
 */
public final class MemoryGuard {
    private final MobOptimizer plugin;
    private final Settings s;
    private final CleanupManager cleanup;
    private long lastEmergency, lastWarn;

    public MemoryGuard(MobOptimizer plugin, Settings s, CleanupManager cleanup) {
        this.plugin = plugin;
        this.s = s;
        this.cleanup = cleanup;
    }

    public void start() {
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::check, 200L, s.memIntervalTicks);
    }

    /** % da heap máxima ocupada pelo conjunto vivo (último GC). Se indisponível, usa o uso atual. */
    public static double percent() {
        Runtime rt = Runtime.getRuntime();
        long live = 0;
        for (MemoryPoolMXBean p : ManagementFactory.getMemoryPoolMXBeans()) {
            if (p.getType() != MemoryType.HEAP) continue;
            MemoryUsage u = p.getCollectionUsage();
            if (u != null) live += Math.max(0L, u.getUsed());
        }
        if (live == 0) live = rt.totalMemory() - rt.freeMemory();
        return live * 100.0 / rt.maxMemory();
    }

    public static String report() {
        Runtime rt = Runtime.getRuntime();
        long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
        long maxMb = rt.maxMemory() >> 20;
        return String.format("RAM (heap): %.0f%% vivo pós-GC | %d/%d MB em uso agora", percent(), usedMb, maxMb);
    }

    private void check() {
        double pct = percent();
        long now = System.currentTimeMillis();
        if (pct >= s.memCriticalPct) {
            if (now - lastEmergency >= s.memCooldownMs) {
                lastEmergency = now;
                emergency(pct);
            }
        } else if (pct >= s.memWarnPct && now - lastWarn >= s.memCooldownMs) {
            lastWarn = now;
            plugin.getLogger().warning(String.format("Memória em %.0f%% (aviso em %.0f%%).", pct, s.memWarnPct));
        }
    }

    public String emergency(double pct) {
        int items = cleanup.cleanItems(200, Math.max(10, s.itemChunkCap / 2));
        int mobs = cleanup.purgeMobs(0.5);
        int chunks = s.unloadChunks ? unloadStaleChunks() : 0;
        if (s.gcOnCritical) System.gc();
        String msg = String.format("Memória crítica (%.0f%%): %d itens, %d mobs removidos, %d chunks descarregados.",
                pct, items, mobs, chunks);
        plugin.getLogger().warning(msg);
        return msg;
    }

    private int unloadStaleChunks() {
        int unloaded = 0;
        for (World w : Bukkit.getWorlds()) {
            if (s.disabledWorlds.contains(w.getName())) continue;
            int keep = Math.max(w.getViewDistance(), w.getSimulationDistance()) + s.unloadMargin;
            List<Player> players = w.getPlayers();
            for (Chunk c : w.getLoadedChunks()) {
                if (unloaded >= s.maxUnload) return unloaded;
                if (c.isForceLoaded() || !c.getPluginChunkTickets().isEmpty()) continue;
                boolean near = false;
                for (Player p : players) {
                    int pcx = (int) Math.floor(p.getX()) >> 4;
                    int pcz = (int) Math.floor(p.getZ()) >> 4;
                    if (Math.abs(pcx - c.getX()) <= keep && Math.abs(pcz - c.getZ()) <= keep) {
                        near = true;
                        break;
                    }
                }
                if (!near && c.unload(true)) unloaded++;
            }
        }
        return unloaded;
    }
}
