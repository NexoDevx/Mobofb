package com.mobopt;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesUnloadEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Desativa a IA (setAware(false)) de mobs parados e longe de players.
 * Gravidade/física continuam funcionando. Acorda quando um player chega perto,
 * quando o mob leva dano ou quando alguém interage com ele.
 */
public final class SleepManager implements Listener {
    private static final class State {
        double x, y, z;
        int idle;

        State(Location l) { set(l); }

        void set(Location l) { x = l.getX(); y = l.getY(); z = l.getZ(); }

        double distSq(Location l) {
            double dx = l.getX() - x, dy = l.getY() - y, dz = l.getZ() - z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final MobOptimizer plugin;
    private final Settings s;
    private final Predicate<UUID> isBusy; // seguidores do agrupamento não dormem

    private final Map<UUID, State> tracking = new HashMap<>();
    private final Map<UUID, Mob> sleeping = new HashMap<>();
    private final List<Mob> queue = new ArrayList<>();
    private int cursor;
    private int phaseTick;
    private boolean stressed; // TPS baixo: dorme mais rápido e acorda só bem perto

    public SleepManager(MobOptimizer plugin, Settings s, Predicate<UUID> isBusy) {
        this.plugin = plugin;
        this.s = s;
        this.isBusy = isBusy;
    }

    public int count() { return sleeping.size(); }

    public void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        plugin.getServer().getScheduler().runTaskTimer(plugin, this::run, 1L, 1L);
    }

    public void stop() {
        for (Mob m : new ArrayList<>(sleeping.values())) {
            if (m.isValid()) m.setAware(true);
        }
        sleeping.clear();
        tracking.clear();
        queue.clear();
    }

    /** Roda todo tick, mas processa só uma fatia dos mobs: o custo fica espalhado. */
    private void run() {
        if (phaseTick == 0) rebuild();
        int end = (int) Math.ceil(queue.size() * (phaseTick + 1) / (double) s.sleepInterval);
        while (cursor < end && cursor < queue.size()) {
            process(queue.get(cursor++));
        }
        phaseTick = (phaseTick + 1) % s.sleepInterval;
    }

    private void rebuild() {
        stressed = s.adaptiveEnabled && Bukkit.getTPS()[0] < s.lowTps;
        queue.clear();
        cursor = 0;
        Set<UUID> alive = new HashSet<>();
        for (World w : Bukkit.getWorlds()) {
            if (s.disabledWorlds.contains(w.getName())) continue;
            for (Mob m : w.getEntitiesByClass(Mob.class)) {
                queue.add(m);
                alive.add(m.getUniqueId());
            }
        }
        tracking.keySet().retainAll(alive);
        sleeping.entrySet().removeIf(e -> !e.getValue().isValid());
    }

    private void process(Mob mob) {
        if (!mob.isValid() || mob.isDead()) return;
        UUID id = mob.getUniqueId();

        if (sleeping.containsKey(id)) {
            if (nearestSq(mob) <= (stressed ? s.stressWakeSq : s.wakeRadiusSq)) wake(mob);
            return;
        }
        if (!mob.isAware()) return;                 // desativado por outro plugin: não mexe
        if (isBusy.test(id) || ignored(mob)) {
            tracking.remove(id);
            return;
        }

        Location l = mob.getLocation();
        State st = tracking.get(id);
        if (st == null) {
            tracking.put(id, new State(l));
            return;
        }

        boolean moved = st.distSq(l) > s.idleMoveSq;
        st.set(l);
        if (moved || mob.getTarget() != null || mob.getPathfinder().getCurrentPath() != null) {
            st.idle = 0;
            return;
        }
        st.idle++;
        if (st.idle >= (stressed ? 1 : s.idleChecks)
                && nearestSq(mob) > (stressed ? s.stressSleepSq : s.sleepRadiusSq)) {
            mob.setAware(false);
            sleeping.put(id, mob);
            tracking.remove(id);
        }
    }

    private boolean ignored(Mob mob) {
        if (s.sleepIgnored.contains(mob.getType().getKey().getKey())) return true;
        if (s.skipNamed && mob.customName() != null) return true;
        if (s.skipTamed && mob instanceof Tameable t && t.isTamed()) return true;
        if (s.skipLeashed && mob.isLeashed()) return true;
        return mob.isInsideVehicle() || !mob.getPassengers().isEmpty();
    }

    private double nearestSq(Mob mob) {
        double best = Double.MAX_VALUE;
        double mx = mob.getX(), my = mob.getY(), mz = mob.getZ();
        for (Player p : mob.getWorld().getPlayers()) {
            double dx = p.getX() - mx, dy = p.getY() - my, dz = p.getZ() - mz;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < best) best = d;
        }
        return best;
    }

    private void wake(Mob mob) {
        mob.setAware(true);
        sleeping.remove(mob.getUniqueId());
        tracking.remove(mob.getUniqueId());
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Mob m && sleeping.containsKey(m.getUniqueId())) wake(m);
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        if (e.getRightClicked() instanceof Mob m && sleeping.containsKey(m.getUniqueId())) wake(m);
    }

    /** Garante que nenhum mob seja salvo/descarregado no estado "dormindo". */
    @EventHandler
    public void onUnload(EntitiesUnloadEvent e) {
        for (Entity en : e.getEntities()) {
            if (en instanceof Mob m && sleeping.containsKey(m.getUniqueId())) wake(m);
        }
    }
}
