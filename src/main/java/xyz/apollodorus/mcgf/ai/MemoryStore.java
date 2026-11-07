package xyz.apollodorus.mcgf.ai;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 持久化记忆存储：按 <b>owner UUID</b> 把达妮娅对每位玩家的长期记忆存到<b>存档目录</b>
 * ({@code <world>/mcgf-memory/<uuid>.json})，按世界隔离、游戏重启不忘。每位玩家两块内容：
 * <ul>
 *   <li>{@code memory} —— {@link ChatBrain} 压缩出来的一段第一人称长期记忆(名字/喜好/关系氛围)。</li>
 *   <li>{@code episodes} —— 「你们一起经历过的事」情景事件环形缓冲(钓到鱼/挖到钻石/去了下界/你死过一次…)，
 *       只记录、绝不主动念出来，靠它注入上下文让她能自然回忆共同经历(活人感的主来源)。</li>
 * </ul>
 * 线程安全：{@link ChatBrain} 在 brain 线程读写 memory，情景埋点在服务器线程写 episodes，均走
 * {@link ConcurrentHashMap} + 每条目 synchronized。落盘在变更时进行(情景本身已节流)，服务器停止时统一 flush。
 */
public final class MemoryStore {
    private MemoryStore() {}

    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/memory");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int EPISODE_MAX = 18;       // 情景事件环形缓冲容量
    private static final long RECORD_MIN_GAP = 40L;  // 同一玩家两条情景至少间隔 2s，防抖

    private static volatile Path dir;                // <world>/mcgf-memory；未初始化时不落盘(仅内存)
    private static final Map<UUID, Entry> CACHE = new ConcurrentHashMap<>();

    /** One owner's on-disk record. {@code episodes} kept newest-last. */
    private static final class Entry {
        volatile String memory = "";
        final Deque<String> episodes = new ArrayDeque<>();
        JourneyMemories.Birthday birthday;
        long lastRecordTick = Long.MIN_VALUE;
    }

    /** JSON shape written to disk. */
    private static final class Data {
        String memory = "";
        List<String> episodes = new ArrayList<>();
        JourneyMemories.Birthday birthday;
    }

    /** Bind to the running server's save directory. Call on server start; safe to call again. */
    public static void init(MinecraftServer server) {
        init(server.getSavePath(WorldSavePath.ROOT));
    }

    static void init(Path world) {
        dir = null;
        try {
            dir = Files.createDirectories(world.resolve("mcgf-memory"));
        } catch (Exception e) {
            LOGGER.warn("[mcgf] memory dir init failed: {}", e.toString());
        }
        CACHE.clear();   // 换存档/重开：丢掉上个世界的内存缓存，按新存档重新懒加载
    }

    // --- long-term memory string ---

    public static String getMemory(UUID owner) {
        return owner == null ? "" : entry(owner).memory;
    }

    public static void setMemory(UUID owner, String memory) {
        if (owner == null) return;
        Entry e = entry(owner);
        synchronized (e) {
            e.memory = memory == null ? "" : memory;
            save(owner, e);
        }
    }

    static JourneyMemories.Birthday birthday(UUID owner) {
        if (owner == null) return null;
        Entry e = entry(owner);
        synchronized (e) { return e.birthday; }
    }

    static boolean updateBirthday(UUID owner, JourneyMemories.Birthday expected, JourneyMemories.Birthday next) {
        Entry e = entry(owner);
        synchronized (e) {
            if (!Objects.equals(expected, e.birthday)) return false;
            e.birthday = next;
            if (save(owner, e)) return true;
            e.birthday = expected;
            return false;
        }
    }

    // --- episodic "shared experiences" ---

    /** A read-only snapshot of the owner's recent shared-experience lines (oldest first). */
    public static List<String> episodes(UUID owner) {
        if (owner == null) return List.of();
        Entry e = entry(owner);
        synchronized (e) { return new ArrayList<>(e.episodes); }
    }

    /**
     * Record one short shared-experience line for {@code gf}'s owner. De-dupes against the last few and
     * throttles to {@link #RECORD_MIN_GAP}, so repeated triggers don't bloat the log. Silent — never spoken.
     */
    public static void record(GirlfriendEntity gf, String line) {
        if (gf == null || line == null || line.isBlank()) return;
        UUID owner = gf.getOwnerUuid();
        if (owner == null) return;
        long now = gf.getEntityWorld().getTime();
        Entry e = entry(owner);
        synchronized (e) {
            if (e.lastRecordTick != Long.MIN_VALUE && now >= e.lastRecordTick
                    && now - e.lastRecordTick < RECORD_MIN_GAP) return;
            int seen = 0;
            for (var it = e.episodes.descendingIterator(); it.hasNext() && seen < 3; seen++) {
                if (line.equals(it.next())) return;                     // 和最近几条重复 → 跳过
            }
            e.episodes.addLast(line);
            while (e.episodes.size() > EPISODE_MAX) e.episodes.removeFirst();
            e.lastRecordTick = now;
            save(owner, e);
        }
    }

    /** Flush every cached owner to disk (call on server stop). */
    public static void flush() {
        for (Map.Entry<UUID, Entry> en : CACHE.entrySet()) {
            synchronized (en.getValue()) { save(en.getKey(), en.getValue()); }
        }
    }

    // --- internals ---

    private static Entry entry(UUID owner) {
        return CACHE.computeIfAbsent(owner, MemoryStore::load);
    }

    private static Entry load(UUID owner) {
        Entry e = new Entry();
        Path f = fileOf(owner);
        if (f == null || Files.notExists(f)) return e;
        try {
            Data d = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), Data.class);
            if (d != null) {
                e.memory = d.memory == null ? "" : d.memory;
                if (d.birthday != null && d.birthday.dimension() != null && d.birthday.visit() != null) {
                    e.birthday = d.birthday;
                }
                if (d.episodes != null) {
                    for (String s : d.episodes) if (s != null && !s.isBlank()) e.episodes.addLast(s);
                    while (e.episodes.size() > EPISODE_MAX) e.episodes.removeFirst();
                }
            }
        } catch (Exception ex) {
            LOGGER.warn("[mcgf] failed to read memory for {}: {}", owner, ex.toString());
        }
        return e;
    }

    private static boolean save(UUID owner, Entry e) {
        Path f = fileOf(owner);
        if (f == null) return false;
        Data d = new Data();
        d.memory = e.memory;
        d.episodes = new ArrayList<>(e.episodes);
        d.birthday = e.birthday;
        try {
            Files.createDirectories(f.getParent());
            Path tmp = Files.createTempFile(f.getParent(), owner.toString(), ".tmp");
            try {
                Files.writeString(tmp, GSON.toJson(d), StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ex) {
                    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(tmp);
            }
            return true;
        } catch (Exception ex) {
            LOGGER.warn("[mcgf] failed to save memory for {}: {}", owner, ex.toString());
            return false;
        }
    }

    private static Path fileOf(UUID owner) {
        Path d = dir;
        return d == null ? null : d.resolve(owner + ".json");
    }
}
