package com.github.beemerwt;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;

import java.util.*;

record Settings(
        SpawnerValues defaults,
        Map<String, SpawnerValues> perWorld,
        Map<EntityType, SpawnerValues> perEntity,
        Set<EntityType> whitelist,
        Set<EntityType> blacklist,
        boolean disableSafetyCaps,
        boolean disableSpawnCaps)
{

    record SpawnerValues(
            int minSpawnDelay,
            int maxSpawnDelay,
            int spawnCount,
            int spawnCap,
            int maxNearbyEntities,
            int requiredPlayerRange,
            int spawnRange)
    { }

    static Settings fromConfig(FileConfiguration cfg) {
        SpawnerValues defs = readValues(cfg.getConfigurationSection("defaults"));

        Map<String, SpawnerValues> worldMap = new HashMap<>();
        ConfigurationSection pw = cfg.getConfigurationSection("perWorld");
        if (pw != null) {
            for (String world : pw.getKeys(false)) {
                worldMap.put(world, readValues(pw.getConfigurationSection(world)));
            }
        }

        Map<EntityType, SpawnerValues> entityMap = new EnumMap<>(EntityType.class);
        ConfigurationSection pe = cfg.getConfigurationSection("perEntity");
        if (pe != null) {
            for (String key : pe.getKeys(false)) {
                EntityType t = parseType(key);
                if (t != null) {
                    entityMap.put(t, readValues(pe.getConfigurationSection(key)));
                }
            }
        }

        Set<EntityType> white = readTypeList(cfg.getStringList("filters.whitelist"));
        Set<EntityType> black = readTypeList(cfg.getStringList("filters.blacklist"));

        boolean disableSafety = cfg.getBoolean("disable-safety-caps", false);
        boolean disableCaps = cfg.getBoolean("disable-spawn-caps", false);
        return new Settings(defs, worldMap, entityMap, white, black, disableSafety, disableCaps);
    }

    // choose first non-negative: per-entity -> per-world -> defaults
    int getSpawnCap(String world, EntityType type) {
        SpawnerValues e = perEntity.get(type);
        if (e != null && e.spawnCap >= 0) return e.spawnCap;

        SpawnerValues w = perWorld.get(world);
        if (w != null && w.spawnCap >= 0) return w.spawnCap;

        return defaults.spawnCap; // may be -1 to disable globally
    }

    boolean isEntityAllowed(EntityType t) {
        if (t == null) return false;
        if (!whitelist.isEmpty() && !whitelist.contains(t)) return false;
        return !blacklist.contains(t);
    }

    SpawnerValues resolve(String worldName, EntityType type) {
        // Merge priority: defaults -> perWorld -> perEntity
        SpawnerValues base = defaults;
        SpawnerValues w = perWorld.get(worldName);
        SpawnerValues e = perEntity.get(type);

        int min = pick(e, w, base, Field.MIN_DELAY);
        int max = pick(e, w, base, Field.MAX_DELAY);
        int cnt = pick(e, w, base, Field.COUNT);
        int cap = pick(e, w, base, Field.CAP);
        int near = pick(e, w, base, Field.NEARBY);
        int pr = pick(e, w, base, Field.PLAYER_RANGE);
        int sr = pick(e, w, base, Field.SPAWN_RANGE);

        return new SpawnerValues(min, max, cnt, cap, near, pr, sr);
    }

    private static int pick(SpawnerValues e, SpawnerValues w, SpawnerValues b, Field f) {
        int ev = value(e, f);
        if (ev >= 0) return ev;
        int wv = value(w, f);
        if (wv >= 0) return wv;
        return value(b, f);
    }

    private enum Field {MIN_DELAY, MAX_DELAY, COUNT, CAP, NEARBY, PLAYER_RANGE, SPAWN_RANGE}

    private static int value(SpawnerValues v, Field f) {
        if (v == null) return -1;
        return switch (f) {
            case MIN_DELAY -> v.minSpawnDelay();
            case MAX_DELAY -> v.maxSpawnDelay();
            case COUNT -> v.spawnCount();
            case CAP -> v.spawnCap();
            case NEARBY -> v.maxNearbyEntities();
            case PLAYER_RANGE -> v.requiredPlayerRange();
            case SPAWN_RANGE -> v.spawnRange();
            default -> -1;
        };
    }

    private static SpawnerValues readValues(ConfigurationSection s) {
        if (s == null) return new SpawnerValues(-1, -1, -1, -1, -1, -1, -1);
        int min = s.getInt("minSpawnDelay", -1);
        int max = s.getInt("maxSpawnDelay", -1);
        int cnt = s.getInt("spawnCount", -1);
        int cap = s.getInt("spawnCap", -1);
        int near = s.getInt("maxNearbyEntities", -1);
        int pr = s.getInt("requiredPlayerRange", -1);
        int sr = s.getInt("spawnRange", -1);
        return new SpawnerValues(min, max, cnt, cap, near, pr, sr);
    }

    private static Set<EntityType> readTypeList(List<String> raw) {
        Set<EntityType> out = EnumSet.noneOf(EntityType.class);
        for (String s : raw) {
            EntityType t = parseType(s);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static EntityType parseType(String s) {
        if (s == null) return null;
        try {
            return EntityType.valueOf(s.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
