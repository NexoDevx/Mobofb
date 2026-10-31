package com.mobopt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Limpa itens no chão e limita a quantidade de mobs por chunk (libera entidades e RAM). */
public final class CleanupManager implements Listener {
    private record Cand(Mob mob, double d2) {}

    private final MobOptimizer plugin;
    private final Settings s;
    /** Contagem de mobs por chunk, atualizada no scan; usada para cancelar spawns baratamente. */
    private final Map<UUID, Map<Long, Integer>> counts = new HashMap<>();

    public CleanupManager(MobOptimizer plugin, Settings s) {
        this.plugin = plugin;
        this.s = s;
    }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        if (s.itemsEnabled) {
            plugin.getServer().getScheduler().runTaskTimer(plugin,
                    () -> cleanItems(s.itemMaxAgeTicks, s.itemChunkCap), 200L, s.itemIntervalTicks);
        }
        if (s.limitsEnabled) {
            plugin.getServer().getScheduler().runTaskTimer(plugin,
                    () -> purgeMobs(1.0), 220L, s.limitIntervalTicks);
        }
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    // ---------------- itens ----------------

    /**
     * Remove itens mais velhos que maxAgeTicks e o excesso por chunk. Retorna quantos foram removidos.
     * Itens recém-caídos (carência) e shulker boxes nunca são removidos.
     */
    public int cleanItems(long maxAgeTicks, int chunkCap) {
        int removed = 0;
        for (World w : Bukkit.getWorlds()) {
            if (s.disabledWorlds.contains(w.getName())) continue;
            Map<Long, List<Item>> byChunk = new HashMap<>();
            for (Item it : w.getEntitiesByClass(Item.class)) {
                if (!it.isValid() || isProtected(it)) continue;
                int age = it.getTicksLived();
                boolean young = age <= graceTicks(it);
                if (!young && age > maxAgeTicks) {
                    it.remove();
                    removed++;
                    continue;
                }
                Location l = it.getLocation();
                byChunk.computeIfAbsent(key(l.getBlockX() >> 4, l.getBlockZ() >> 4), k -> new ArrayList<>()).add(it);
            }
            for (List<Item> list : byChunk.values()) {
                int excess = list.size() - chunkCap;
                if (excess <= 0) continue;
                list.sort((a, b) -> Integer.compare(b.getTicksLived(), a.getTicksLived())); // mais velhos primeiro
                for (Item it : list) {
                    if (excess <= 0) break;
                    if (it.getTicksLived() <= graceTicks(it)) continue; // recém-caído: intocável
                    it.remove();
                    removed++;
                    excess--;
                }
            }
        }
        return removed;
    }

    /** Carência após cair; itens jogados por players ficam protegidos por mais tempo. */
    private int graceTicks(Item it) {
        return it.getThrower() != null ? s.playerGraceTicks : s.itemMinAgeTicks;
    }

    private boolean isShulker(Item it) {
        return it.getItemStack().getType().getKey().getKey().endsWith("shulker_box");
    }

    private boolean isProtected(Item it) {
        if (s.protectShulker && isShulker(it)) return true;
        ItemStack st = it.getItemStack();
        if (s.protectedItems.contains(st.getType().getKey().getKey())) return true;
        return s.protectMeta && st.hasItemMeta();
    }

    /** Impede só o despawn por tempo do vanilla; fogo, lava, cacto etc. continuam destruindo. */
    @EventHandler(ignoreCancelled = true)
    public void onItemDespawn(ItemDespawnEvent e) {
        if (s.shulkerNoDespawn && isShulker(e.getEntity())) e.setCancelled(true);
    }

    // ---------------- mobs ----------------

    /**
     * Remove o excesso de mobs por chunk (total e por tipo), os mais longe de players primeiro.
     * factor < 1 deixa os limites mais apertados (usado na emergência de memória).
     */
    public int purgeMobs(double factor) {
        int removed = 0;
        int maxTotal = Math.max(4, (int) (s.maxMobsPerChunk * factor));
        int maxType = Math.max(2, (int) (s.maxPerTypePerChunk * factor));

        for (World w : Bukkit.getWorlds()) {
            if (s.disabledWorlds.contains(w.getName())) continue;
            Map<Long, List<Mob>> byChunk = new HashMap<>();
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                Location l = m.getLocation();
                byChunk.computeIfAbsent(key(l.getBlockX() >> 4, l.getBlockZ() >> 4), k -> new ArrayList<>()).add(m);
            }

            Map<Long, Integer> cache = new HashMap<>();
            for (Map.Entry<Long, List<Mob>> e : byChunk.entrySet()) {
                List<Mob> list = e.getValue();
                int total = list.size();
                cache.put(e.getKey(), total);
                if (total <= maxType) continue;

                Map<String, Integer> typeCount = new HashMap<>();
                List<Cand> cands = new ArrayList<>();
                for (Mob m : list) {
                    typeCount.merge(typeKey(m), 1, Integer::sum);
                    if (removable(m)) cands.add(new Cand(m, nearestSq(m)));
                }
                cands.sort((a, b) -> Double.compare(b.d2(), a.d2())); // mais longe primeiro

                for (Cand c : cands) {
                    String t = typeKey(c.mob());
                    boolean overType = typeCount.get(t) > maxType;
                    if (!overType && total <= maxTotal) continue;
                    c.mob().remove();
                    typeCount.merge(t, -1, Integer::sum);
                    total--;
                    removed++;
                }
                cache.put(e.getKey(), total);
            }
            counts.put(w.getUID(), cache);
        }
        return removed;
    }

    private static String typeKey(Mob m) {
        return m.getType().getKey().getKey();
    }

    private boolean removable(Mob m) {
        if (!m.getRemoveWhenFarAway()) return false;   // persistente (domado, com item, etc.)
        if (m.customName() != null || m.isLeashed()) return false;
        if (m instanceof Tameable t && t.isTamed()) return false;
        return !m.isInsideVehicle() && m.getPassengers().isEmpty();
    }

    private double nearestSq(Mob mob) {
        double best = Double.MAX_VALUE;
        double mx = mob.getX(), my = mob.getY(), mz = mob.getZ();
        for (Player p : mob.getWorld().getPlayers()) {
            double dx = p.getX() - mx, dy = p.getY() - my, dz = p.getZ() - mz;
            best = Math.min(best, dx * dx + dy * dy + dz * dz);
        }
        return best;
    }

    /** Cancela spawns naturais/de spawner em chunks que já estão no limite. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent e) {
        if (!s.limitsEnabled) return;
        if (!s.limitedReasons.contains(e.getSpawnReason().name())) return;
        Location l = e.getLocation();
        if (l.getWorld() == null) return;
        Map<Long, Integer> m = counts.computeIfAbsent(l.getWorld().getUID(), k -> new HashMap<>());
        long k = key(l.getBlockX() >> 4, l.getBlockZ() >> 4);
        int c = m.getOrDefault(k, 0);
        if (c >= s.maxMobsPerChunk) {
            e.setCancelled(true);
        } else {
            m.put(k, c + 1);
        }
    }
}
