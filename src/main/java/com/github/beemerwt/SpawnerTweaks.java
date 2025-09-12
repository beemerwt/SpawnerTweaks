package com.github.beemerwt;

import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.logging.Logger;
import static com.github.beemerwt.Settings.SpawnerValues;

public final class SpawnerTweaks extends JavaPlugin implements Listener, TabCompleter {

    private static final int HARD_MIN_SPAWN_DELAY   = 10;   // ticks
    private static final int HARD_MAX_SPAWN_DELAY   = 20;   // ticks
    private static final int HARD_MAX_SPAWN_COUNT   = 32;
    private static final int HARD_MAX_NEARBY        = 64;
    private static final int HARD_MAX_PLAYER_RANGE  = 64;
    private static final int HARD_MAX_SPAWN_RANGE   = 32;
    private static final int CHUNKS_PER_TICK        = 150; // adjust if you want faster/slower sweeps

    private Logger log;
    private Settings settings;

    @Override
    public void onEnable() {
        this.log = getLogger();
        saveDefaultConfig();
        reloadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("spawnertweaks")).setTabCompleter(this);
        log.info("SpawnerTweaks enabled.");
    }

    @Override
    public void onDisable() {
        log.info("SpawnerTweaks disabled.");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, Command cmd, @NotNull String label, String @NotNull [] args) {
        if (!cmd.getName().equalsIgnoreCase("spawnertweaks")) return false;
        if (!sender.hasPermission("spawnertweaks.admin")) {
            sender.sendMessage("You do not have permission.");
            return true;
        }

        if (args.length == 0 || (args.length == 1 && args[0].equalsIgnoreCase("help"))) {
            sendHelp(sender);
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            reloadSettings();
            sender.sendMessage("SpawnerTweaks config reloaded.");
            return true;
        }

        if (args.length >= 1 && args[0].equalsIgnoreCase("applyall")) {
            if (args.length == 2) {
                World w = Bukkit.getWorld(args[1]);
                if (w == null) {
                    sender.sendMessage("Unknown world: " + args[1]);
                    return true;
                }
                sender.sendMessage("SpawnerTweaks: re-applying to loaded chunks in world '" + w.getName() + "'...");
                applyAllLoadedAsync(sender, Collections.singletonList(w));
                return true;
            } else {
                sender.sendMessage("SpawnerTweaks: re-applying to all loaded chunks in all worlds...");
                applyAllLoadedAsync(sender, Bukkit.getWorlds());
                return true;
            }
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("info")) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage("Run this in-game while looking at a spawner.");
                return true;
            }

            CreatureSpawner cs = getTargetedSpawner(p, 8);
            if (cs == null) {
                p.sendMessage("Look at a spawner within 8 blocks and try again.");
                return true;
            }

            // Effective config (merged)
            SpawnerValues eff = settings.resolve(cs.getWorld().getName(), cs.getSpawnedType());

            p.sendMessage("--- SpawnerTweaks: info ---");
            p.sendMessage("Location: " + cs.getLocation().getBlockX() + "," +
                    cs.getLocation().getBlockY() + "," +
                    cs.getLocation().getBlockZ() + " in " + cs.getWorld().getName());
            p.sendMessage("EntityType: " + String.valueOf(cs.getSpawnedType()));
            p.sendMessage("Safety caps active: " + (settings.disableSafetyCaps() ? "false (DISABLED)" : "true"));

            p.sendMessage("Current (live from block):");
            p.sendMessage("  minSpawnDelay=" + cs.getMinSpawnDelay() +
                    " maxSpawnDelay=" + cs.getMaxSpawnDelay() +
                    " spawnCount=" + cs.getSpawnCount());
            p.sendMessage("  maxNearbyEntities=" + cs.getMaxNearbyEntities() +
                    " requiredPlayerRange=" + cs.getRequiredPlayerRange() +
                    " spawnRange=" + cs.getSpawnRange());

            p.sendMessage("Effective config (before safety caps; -1 means unchanged):");
            p.sendMessage("  minSpawnDelay=" + eff.minSpawnDelay() +
                    " maxSpawnDelay=" + eff.maxSpawnDelay() +
                    " spawnCount=" + eff.spawnCount());
            p.sendMessage("  maxNearbyEntities=" + eff.maxNearbyEntities() +
                    " requiredPlayerRange=" + eff.requiredPlayerRange() +
                    " spawnRange=" + eff.spawnRange());

            return true;
        }

        sendHelp(sender);
        return true;
    }

    private void reloadSettings() {
        this.settings = Settings.fromConfig(getConfig());
    }

    // ========== Event hooks ==========

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        applyToChunk(e.getChunk());
    }

    @EventHandler
    public void onSpawnerPlace(BlockPlaceEvent e) {
        Block b = e.getBlockPlaced();
        if (b.getType() != Material.SPAWNER) return;
        tweakSpawner(b.getState(), "place");
    }

    // ========== Core logic ==========

    private void applyToChunk(Chunk chunk) {
        try {
            // Paper/Spigot still expose tile entities via chunk.getTileEntities() in 1.20/1.21.
            // If your API ever drops it, swap to Paper's chunk.getBlockEntities().
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof CreatureSpawner) {
                    tweakSpawner(state, "chunk");
                }
            }
        } catch (Throwable t) {
            log.warning("Failed to iterate tile entities in chunk " +
                    chunk.getX() + "," + chunk.getZ() + " (" + t.getClass().getSimpleName() + "): " + t.getMessage());
        }
    }

    private void tweakSpawner(BlockState state, String reason) {
        if (!(state instanceof CreatureSpawner)) return;
        CreatureSpawner cs = (CreatureSpawner) state;

        EntityType type = cs.getSpawnedType();
        World world = cs.getWorld();

        if (!settings.isEntityAllowed(type)) return;

        SpawnerValues values = settings.resolve(world.getName(), type);

        // Apply safety caps and skip unchanged (-1 means do not touch).
        boolean changed = false;

        // Must set the max spawn delay first so the min spawn delay doesn't error out
        if (values.maxSpawnDelay() >= 0) {
            int v = settings.disableSafetyCaps() ? values.maxSpawnDelay() :
                    Math.max(values.maxSpawnDelay(), HARD_MAX_SPAWN_DELAY);
            if (cs.getMaxSpawnDelay() != v) {
                cs.setMaxSpawnDelay(v);
                changed = true;
            }
        }
        if (values.minSpawnDelay() >= 0) {
            int v = settings.disableSafetyCaps() ? values.minSpawnDelay() :
                    Math.max(values.minSpawnDelay(), HARD_MIN_SPAWN_DELAY);
            if (cs.getMinSpawnDelay() != v) {
                cs.setMinSpawnDelay(v);
                changed = true;
            }
        }

        // Same with the spawn count and nearby entities
        if (values.maxNearbyEntities() >= 0) {
            int v = settings.disableSafetyCaps() ? values.maxNearbyEntities() :
                    Math.min(values.maxNearbyEntities(), HARD_MAX_NEARBY);
            if (cs.getMaxNearbyEntities() != v) {
                cs.setMaxNearbyEntities(v);
                changed = true;
            }
        }
        if (values.spawnCount() >= 0) {
            int v = settings.disableSafetyCaps() ? values.spawnCount() :
                    Math.min(values.spawnCount(), HARD_MAX_SPAWN_COUNT);
            if (cs.getSpawnCount() != v) {
                cs.setSpawnCount(v);
                changed = true;
            }
        }

        if (values.requiredPlayerRange() >= 0) {
            int v = settings.disableSafetyCaps() ? values.requiredPlayerRange() :
                    Math.min(values.requiredPlayerRange(), HARD_MAX_PLAYER_RANGE);
            if (cs.getRequiredPlayerRange() != v) {
                cs.setRequiredPlayerRange(v);
                changed = true;
            }
        }
        if (values.spawnRange() >= 0) {
            int v = settings.disableSafetyCaps() ? values.spawnRange() :
                    Math.min(values.spawnRange(), HARD_MAX_SPAWN_RANGE);
            if (cs.getSpawnRange() != v) {
                cs.setSpawnRange(v);
                changed = true;
            }
        }

        if (changed) {
            cs.update(); // push to world
            if (getServer().getPluginManager().isPluginEnabled("Paper")) {
                // Optional: On Paper, reset internal immediate delay to honor new range sooner.
                // Not strictly necessary; Bukkit API does not expose direct reset beyond setDelay().
            }
            debug("Tweaked spawner at " + state.getLocation() +
                    " [" + type + "] via " + reason);
        }
    }

    private void applyAllLoadedAsync(CommandSender feedback) {
        applyAllLoadedAsync(feedback, Bukkit.getWorlds());
    }

    private void applyAllLoadedAsync(CommandSender feedback, List<World> worlds) {
        List<Chunk> worklist = new ArrayList<>();
        for (World w : worlds) {
            Collections.addAll(worklist, w.getLoadedChunks());
        }

        final int total = worklist.size();
        if (total == 0) {
            if (feedback != null) feedback.sendMessage("SpawnerTweaks: no loaded chunks to update.");
            return;
        }

        if (feedback != null) {
            if (worlds.size() == 1) {
                feedback.sendMessage("SpawnerTweaks: updating " + total + " loaded chunks in '" + worlds.get(0).getName() + "'...");
            } else {
                feedback.sendMessage("SpawnerTweaks: updating " + total + " loaded chunks across " + worlds.size() + " worlds...");
            }
        }

        new org.bukkit.scheduler.BukkitRunnable() {
            int processed = 0;

            @Override
            public void run() {
                int n = Math.min(CHUNKS_PER_TICK, worklist.size());
                for (int i = 0; i < n; i++) {
                    Chunk c = worklist.remove(worklist.size() - 1);
                    try {
                        applyToChunk(c);
                    } catch (Throwable t) {
                        getLogger().warning("ApplyAll: failed on chunk " + c.getX() + "," + c.getZ()
                                + " in " + c.getWorld().getName() + ": " + t.getClass().getSimpleName()
                                + ": " + t.getMessage());
                    }
                }
                processed += n;

                if (feedback != null && processed % 2000 == 0 && processed < total) {
                    feedback.sendMessage("SpawnerTweaks: " + processed + " / " + total + " chunks updated...");
                }

                if (worklist.isEmpty()) {
                    if (feedback != null) {
                        feedback.sendMessage("SpawnerTweaks: finished updating " + processed + " chunks.");
                    }
                    cancel();
                }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private CreatureSpawner getTargetedSpawner(org.bukkit.entity.Player p, int maxDist) {
        // Works on Spigot/Paper 1.20–1.21 without NMS:
        org.bukkit.block.Block b = p.getTargetBlockExact(maxDist);
        if (b == null || b.getType() != org.bukkit.Material.SPAWNER) return null;
        org.bukkit.block.BlockState st = b.getState();
        return (st instanceof CreatureSpawner) ? (CreatureSpawner) st : null;
    }

    // Tab completion
    @Override
    public List<String> onTabComplete(
            @NotNull CommandSender sender,
            Command cmd, @NotNull String alias,
            String @NotNull [] args)
    {
        if (!cmd.getName().equalsIgnoreCase("spawnertweaks")) return Collections.emptyList();
        if (!sender.hasPermission("spawnertweaks.admin")) return Collections.emptyList();

        // Subcommands available
        List<String> subs = Arrays.asList("help", "reload", "applyall", "info");

        if (args.length == 1) {
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], subs, out);
            Collections.sort(out);
            return out;
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("applyall")) {
            List<String> worlds = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) worlds.add(w.getName());
            List<String> out = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], worlds, out);
            Collections.sort(out);
            return out;
        }

        // No further arguments for current subcommands
        return Collections.emptyList();
    }

    private void sendHelp(CommandSender sender) {
        if (!sender.hasPermission("spawnertweaks.admin")) {
            sender.sendMessage("You do not have permission.");
            return;
        }
        sender.sendMessage("§aSpawnerTweaks §7- commands:");
        sender.sendMessage("§e/spawnertweaks help §7- Show this help.");
        sender.sendMessage("§e/spawnertweaks reload §7- Reload config, new values will be applied to new chunks.");
        sender.sendMessage("§e/spawnertweaks applyall §7- Re-apply current config to all loaded spawners (batched).");
        sender.sendMessage("§e/spawnertweaks applyall <world> §7- Re-apply only in the specified world.");
        sender.sendMessage("§e/spawnertweaks info §7- While looking at a spawner, show live values and effective config.");
        // If you later add applyall or toggles, list them here too.
    }

    private void debug(String msg) {
        log.fine(msg);
    }
}
