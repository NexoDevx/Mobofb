package com.mobopt;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class Settings {
    public final Set<String> disabledWorlds;

    // sleep
    public final boolean sleepEnabled;
    public final int sleepInterval;
    public final double wakeRadiusSq, sleepRadiusSq, idleMoveSq;
    public final int idleChecks;
    public final boolean skipNamed, skipTamed, skipLeashed;
    public final Set<String> sleepIgnored;
    public final boolean adaptiveEnabled;
    public final double lowTps, stressWakeSq, stressSleepSq;

    // group pathing
    public final boolean groupEnabled;
    public final int groupInterval, minGroup, lookahead;
    public final double clusterRadiusSq, releaseDistSq, searchRadius, followSpeed;
    public final Set<String> groupTypes;

    // cleanup: items
    public final boolean itemsEnabled;
    public final int itemIntervalTicks, itemMaxAgeTicks, itemChunkCap;
    public final Set<String> protectedItems;
    public final boolean protectMeta, protectShulker, shulkerNoDespawn;
    public final int itemMinAgeTicks, playerGraceTicks;

    // cleanup: mob limits
    public final boolean limitsEnabled;
    public final int limitIntervalTicks, maxMobsPerChunk, maxPerTypePerChunk;
    public final Set<String> limitedReasons;

    // memory
    public final boolean memEnabled;
    public final int memIntervalTicks;
    public final double memWarnPct, memCriticalPct;
    public final long memCooldownMs;
    public final boolean gcOnCritical, unloadChunks;
    public final int maxUnload, unloadMargin;

    public Settings(FileConfiguration c) {
        disabledWorlds = set(c, "disabled-worlds", false);

        sleepEnabled = c.getBoolean("sleep.enabled", true);
        sleepInterval = Math.max(2, c.getInt("sleep.check-interval-ticks", 20));
        double wake = Math.max(8.0, c.getDouble("sleep.wake-radius", 24.0));
        double extra = Math.max(0.0, c.getDouble("sleep.hysteresis", 4.0));
        wakeRadiusSq = wake * wake;
        sleepRadiusSq = (wake + extra) * (wake + extra);
        idleChecks = Math.max(1, c.getInt("sleep.idle-checks-required", 3));
        double mv = c.getDouble("sleep.idle-move-threshold", 0.25);
        idleMoveSq = mv * mv;
        skipNamed = c.getBoolean("sleep.skip-named", true);
        skipTamed = c.getBoolean("sleep.skip-tamed", true);
        skipLeashed = c.getBoolean("sleep.skip-leashed", true);
        sleepIgnored = set(c, "sleep.ignored-types", false);
        adaptiveEnabled = c.getBoolean("sleep.adaptive.enabled", true);
        lowTps = c.getDouble("sleep.adaptive.low-tps", 17.0);
        double sw = Math.max(8.0, c.getDouble("sleep.adaptive.stress-wake-radius", 16.0));
        stressWakeSq = sw * sw;
        stressSleepSq = (sw + extra) * (sw + extra);

        groupEnabled = c.getBoolean("group-pathing.enabled", true);
        groupInterval = Math.max(2, c.getInt("group-pathing.interval-ticks", 10));
        minGroup = Math.max(2, c.getInt("group-pathing.min-group-size", 4));
        double cr = c.getDouble("group-pathing.cluster-radius", 10.0);
        clusterRadiusSq = cr * cr;
        double rd = c.getDouble("group-pathing.release-distance", 10.0);
        releaseDistSq = rd * rd;
        searchRadius = Math.max(16.0, c.getDouble("group-pathing.search-radius", 40.0));
        lookahead = Math.max(0, c.getInt("group-pathing.lookahead-points", 3));
        followSpeed = c.getDouble("group-pathing.follow-speed", 1.0);
        groupTypes = set(c, "group-pathing.types", false);

        itemsEnabled = c.getBoolean("cleanup.items.enabled", true);
        itemIntervalTicks = Math.max(20, c.getInt("cleanup.items.interval-seconds", 15) * 20);
        itemMaxAgeTicks = Math.max(200, c.getInt("cleanup.items.max-age-seconds", 180) * 20);
        itemChunkCap = Math.max(10, c.getInt("cleanup.items.max-per-chunk", 150));
        protectedItems = set(c, "cleanup.items.protected", false);
        protectMeta = c.getBoolean("cleanup.items.protect-items-with-meta", true);
        protectShulker = c.getBoolean("cleanup.items.protect-shulker-boxes", true);
        shulkerNoDespawn = c.getBoolean("cleanup.items.shulker-never-despawn", true);
        itemMinAgeTicks = Math.max(0, c.getInt("cleanup.items.min-age-seconds", 30) * 20);
        playerGraceTicks = Math.max(itemMinAgeTicks, c.getInt("cleanup.items.player-drop-min-age-seconds", 120) * 20);

        limitsEnabled = c.getBoolean("cleanup.mob-limits.enabled", true);
        limitIntervalTicks = Math.max(20, c.getInt("cleanup.mob-limits.interval-seconds", 15) * 20);
        maxMobsPerChunk = Math.max(8, c.getInt("cleanup.mob-limits.max-per-chunk", 40));
        maxPerTypePerChunk = Math.max(4, c.getInt("cleanup.mob-limits.max-per-type-per-chunk", 24));
        limitedReasons = set(c, "cleanup.mob-limits.limited-spawn-reasons", true);

        memEnabled = c.getBoolean("memory.enabled", true);
        memIntervalTicks = Math.max(20, c.getInt("memory.check-interval-seconds", 10) * 20);
        memWarnPct = c.getDouble("memory.warn-percent", 80.0);
        memCriticalPct = c.getDouble("memory.critical-percent", 90.0);
        memCooldownMs = Math.max(5, c.getInt("memory.emergency-cooldown-seconds", 60)) * 1000L;
        gcOnCritical = c.getBoolean("memory.gc-on-critical", false);
        unloadChunks = c.getBoolean("memory.unload-stale-chunks", true);
        maxUnload = Math.max(1, c.getInt("memory.max-chunks-per-run", 150));
        unloadMargin = Math.max(0, c.getInt("memory.unload-margin-chunks", 2));
    }

    private static Set<String> set(FileConfiguration c, String path, boolean upper) {
        Set<String> out = new HashSet<>();
        for (String v : c.getStringList(path)) {
            String x = v.replace("minecraft:", "");
            out.add(upper ? x.toUpperCase(Locale.ROOT) : x.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
