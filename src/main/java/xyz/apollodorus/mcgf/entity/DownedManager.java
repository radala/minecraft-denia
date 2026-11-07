package xyz.apollodorus.mcgf.entity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-world companion bond state, keyed by the owner's UUID.
 * <ul>
 *   <li>{@code downed} — she died; the charm can revive her (for an XP cost that
 *       grows by one level each time, via {@code reviveCount}).</li>
 *   <li>{@code stored} — she was captured alive into the charm; the charm releases
 *       her again, free.</li>
 * </ul>
 */
public final class DownedManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/downed");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private DownedManager() {}

    /** Serialized state of a companion, enough to rebuild her (dead or captured-alive). */
    public static final class Snapshot {
        public String belongings; // SNBT preserves component types, including nested container items.
        public String inventory;  // pre-travel update snapshot
        public int affection;
        public double x, y, z;
        public double health;
        public boolean hasHome;
        public int homeX, homeY, homeZ;
        public Map<String, String> equipment; // legacy gear by equipment slot name
    }

    private static final class Store {
        Map<String, Snapshot> downed = new HashMap<>();      // dead → revivable
        Map<String, Integer> reviveCount = new HashMap<>();
        Map<String, Snapshot> stored = new HashMap<>();      // captured alive → releasable

        Store copy() {
            Store next = new Store();
            next.downed.putAll(downed);
            next.reviveCount.putAll(reviveCount);
            next.stored.putAll(stored);
            return next;
        }
    }

    private static volatile Store store = new Store();

    private static Path file;

    public static synchronized void load(MinecraftServer server) {
        Path world = server.getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
        Path legacy = FabricLoader.getInstance().getConfigDir().resolve("mcgf-downed.json");
        load(world, legacy);
    }

    static synchronized void load(Path world, Path legacy) {
        Path target = world.resolve("mcgf-downed.json");
        file = null;
        store = new Store();
        try {
            if (Files.notExists(target) && Files.isRegularFile(legacy)) {
                // The old format has no world identifier. Do not guess between existing worlds.
                boolean soleSave;
                try (var siblings = Files.list(world.getParent())) {
                    soleSave = Files.isRegularFile(world.resolve("level.dat")) &&
                        siblings.noneMatch(p -> !p.equals(world) && Files.isRegularFile(p.resolve("level.dat")));
                }
                if (soleSave) {
                    Files.move(legacy, target);
                    LOGGER.info("[mcgf] Migrated legacy bond store to {}", world);
                } else {
                    LOGGER.warn("[mcgf] Legacy {} has no world identifier. To restore it, move it into its original save directory while the game is closed.", legacy);
                }
            }
            if (Files.exists(target)) {
                Store s = GSON.fromJson(Files.readString(target, StandardCharsets.UTF_8), Store.class);
                if (s == null) throw new IOException("empty bond store");
                if (s.downed == null) s.downed = new HashMap<>();
                if (s.reviveCount == null) s.reviveCount = new HashMap<>();
                if (s.stored == null) s.stored = new HashMap<>();
                store = s;
            }
            file = target;
        } catch (Exception e) {
            LOGGER.warn("[mcgf] failed to load bond store: {}", e.toString());
        }
    }

    private static boolean save(Store next) {
        if (file == null) return false;
        Path temp = file.resolveSibling("mcgf-downed.json.tmp");
        try {
            Files.writeString(temp, GSON.toJson(next), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            store = next;
            return true;
        } catch (Exception e) {
            LOGGER.warn("[mcgf] failed to save bond store: {}", e.toString());
            return false;
        }
    }

    // --- downed (dead → revive) ---

    public static synchronized boolean setDowned(UUID owner, Snapshot snap) {
        Store next = store.copy();
        next.downed.put(owner.toString(), snap);
        return save(next);
    }

    public static Snapshot getDowned(UUID owner) {
        return store.downed.get(owner.toString());
    }

    public static boolean isDowned(UUID owner) {
        return store.downed.containsKey(owner.toString());
    }

    public static synchronized boolean clearDowned(UUID owner) {
        Store next = store.copy();
        return next.downed.remove(owner.toString()) != null && save(next);
    }

    public static int getReviveCount(UUID owner) {
        return store.reviveCount.getOrDefault(owner.toString(), 0);
    }

    public static synchronized void incrementReviveCount(UUID owner) {
        Store next = store.copy();
        next.reviveCount.merge(owner.toString(), 1, Integer::sum);
        save(next);
    }

    // --- stored (captured alive → release) ---

    public static synchronized boolean setStored(UUID owner, Snapshot snap) {
        Store next = store.copy();
        next.stored.put(owner.toString(), snap);
        return save(next);
    }

    public static Snapshot getStored(UUID owner) {
        return store.stored.get(owner.toString());
    }

    public static boolean isStored(UUID owner) {
        return store.stored.containsKey(owner.toString());
    }

    public static synchronized boolean clearStored(UUID owner) {
        Store next = store.copy();
        return next.stored.remove(owner.toString()) != null && save(next);
    }
}
