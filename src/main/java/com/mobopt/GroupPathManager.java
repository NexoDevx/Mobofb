package com.mobopt;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Agrupa o pathfinding de mobs que perseguem o mesmo player.
 *
 * Em cada cluster, o líder (o mob mais perto do player) mantém a IA vanilla e
 * calcula o caminho longo. Os seguidores ficam sem alvo (a IA vanilla de
 * ataque/perseguição só recalcula rota quando há alvo) e apenas andam até um
 * ponto à frente na rota do líder: caminhos curtos e baratos. Perto do player,
 * ou ao levar dano, o seguidor recebe o alvo de volta.
 */
public final class GroupPathManager implements Listener {
    private static final class Follow {
        final Mob mob;
        final UUID player;

        Follow(Mob mob, UUID player) { this.mob = mob; this.player = player; }
    }

    private static final class Entry {
        final Mob mob;
        final Location loc;
        final double d2;

        Entry(Mob mob, Location playerLoc) {
            this.mob = mob;
            this.loc = mob.getLocation();
            this.d2 = loc.distanceSquared(playerLoc);
        }
    }

    private final MobOptimizer plugin;
    private final Settings s;
    private final Map<UUID, Follow> following = new HashMap<>();
    private final Set<UUID> touched = new HashSet<>();

    public GroupPathManager(MobOptimizer plugin, Settings s) {
        this.plugin = plugin;
        this.s = s;
    }

    public boolean isFollower(UUID id) { return following.containsKey(id); }

    public int count() { return following.size(); }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::run, 5L, s.groupInterval);
    }

    public void stop() {
        for (Follow f : new ArrayList<>(following.values())) release(f.mob);
        following.clear();
    }

    private void run() {
        touched.clear();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) continue;
            if (s.disabledWorlds.contains(p.getWorld().getName())) continue;
            collect(p);
        }
        // seguidores que ninguém "reivindicou" nesta rodada voltam ao normal
        for (Follow f : new ArrayList<>(following.values())) {
            if (!touched.contains(f.mob.getUniqueId())) release(f.mob);
        }
    }

    private void collect(Player p) {
        double r = s.searchRadius;
        Location pl = p.getLocation();
        Map<String, List<Entry>> byType = new HashMap<>();

        for (Entity e : p.getNearbyEntities(r, r, r)) {
            if (!(e instanceof Mob m) || !m.isValid() || !m.isAware()) continue;
            String key = m.getType().getKey().getKey();
            if (!s.groupTypes.contains(key)) continue;

            Follow f = following.get(m.getUniqueId());
            boolean mine = f != null ? f.player.equals(p.getUniqueId()) : p.equals(m.getTarget());
            if (!mine) continue;

            byType.computeIfAbsent(key, k -> new ArrayList<>()).add(new Entry(m, pl));
        }
        for (List<Entry> list : byType.values()) cluster(p, list);
    }

    private void cluster(Player p, List<Entry> entries) {
        entries.sort(Comparator.comparingDouble(en -> en.d2));

        // Clusterização gulosa: o mais perto do player vira líder; quem estiver
        // perto de algum líder vira seguidor dele; senão vira um novo líder.
        Map<Entry, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry en : entries) {
            Entry lead = null;
            for (Entry l : groups.keySet()) {
                if (l.loc.distanceSquared(en.loc) <= s.clusterRadiusSq) { lead = l; break; }
            }
            if (lead == null) groups.put(en, new ArrayList<>());
            else groups.get(lead).add(en);
        }

        for (Map.Entry<Entry, List<Entry>> g : groups.entrySet()) {
            Entry leader = g.getKey();
            List<Entry> members = g.getValue();
            release(leader.mob); // líder sempre usa a IA vanilla

            if (members.size() + 1 < s.minGroup) {
                for (Entry m : members) release(m.mob);
                continue;
            }
            for (Entry m : members) {
                if (m.d2 <= s.releaseDistSq) release(m.mob);
                else follow(m.mob, leader.mob, p);
            }
        }
    }

    private void follow(Mob mob, Mob leader, Player p) {
        UUID id = mob.getUniqueId();
        if (!following.containsKey(id)) {
            following.put(id, new Follow(mob, p.getUniqueId()));
            mob.setTarget(null);
        }
        touched.add(id);

        // destino: alguns pontos à frente na rota atual do líder (ou o próprio líder)
        Location dest = leader.getLocation();
        var path = leader.getPathfinder().getCurrentPath();
        if (path != null) {
            var pts = path.getPoints();
            if (!pts.isEmpty()) {
                int idx = Math.min(pts.size() - 1, Math.max(0, path.getNextPointIndex()) + s.lookahead);
                dest = pts.get(idx);
            }
        }
        if (!mob.getPathfinder().moveTo(dest, s.followSpeed)) {
            release(mob); // sem caminho curto: deixa a IA vanilla resolver
        }
    }

    /** Remove o mob do grupo e devolve o alvo (o player) para a IA vanilla. */
    private void release(Mob mob) {
        Follow f = following.remove(mob.getUniqueId());
        if (f == null) return;
        Player p = Bukkit.getPlayer(f.player);
        if (mob.isValid() && p != null && p.isOnline() && !p.isDead()
                && p.getWorld().equals(mob.getWorld()) && mob.getTarget() == null) {
            mob.setTarget(p);
        }
    }

    /** Seguidores não podem re-adquirir alvo sozinhos (senão voltam a calcular rota longa). */
    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetEvent e) {
        if (e.getTarget() != null && following.containsKey(e.getEntity().getUniqueId())) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Mob m && following.containsKey(m.getUniqueId())) release(m);
    }
}
