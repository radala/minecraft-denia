package xyz.apollodorus.mcgf.ai;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.UUID;

/** A shared birthday remains part of this world's story after ordinary recent memories roll off. */
public final class JourneyMemories {
    private JourneyMemories() {}

    enum Visit { CELEBRATED, AWAY, REMEMBERED }

    record Birthday(String dimension, int x, int y, int z, long day, Visit visit) {
        Birthday withVisit(Visit next) { return new Birthday(dimension, x, y, z, day, next); }

        double distanceSquared(PlayerEntity player) {
            if (!dimension.equals(player.getEntityWorld().getRegistryKey().getValue().toString())) {
                return Double.POSITIVE_INFINITY;
            }
            return player.squaredDistanceTo(x + 0.5, y + 0.5, z + 0.5);
        }
    }

    public static void register() {
        UseEntityCallback.EVENT.register((player, world, hand, target, hit) -> {
            // Let vanilla send the normal interact packet. Only its position-less server interaction
            // opens the companion screen; handling the earlier interact-at packet would fire twice.
            if (!(world instanceof ServerWorld sw) || hit != null || hand != Hand.MAIN_HAND
                    || !(target instanceof GirlfriendEntity gf) || !gf.isOwner(player) || !gf.isAlive()
                    || !player.isSneaking() || !player.canModifyBlocks() || player.isSpectator()
                    || !player.getMainHandStack().isEmpty() || !player.getOffHandStack().isEmpty()
                    || MemoryStore.birthday(player.getUuid()) != null) return ActionResult.PASS;

            for (BlockPos pos : BlockPos.iterateOutwards(gf.getBlockPos(), 4, 4, 4)) {
                Vec3d center = Vec3d.ofCenter(pos);
                if (player.squaredDistanceTo(center) > 16 || gf.squaredDistanceTo(center) > 16) continue;
                BlockState state = sw.getBlockState(pos);
                if (!(state.getBlock() instanceof CandleCakeBlock) || !state.get(AbstractCandleBlock.LIT)
                        || !sw.canEntityModifyAt(player, pos)) continue;
                Birthday first = new Birthday(sw.getRegistryKey().getValue().toString(), pos.getX(), pos.getY(),
                        pos.getZ(), Math.floorDiv(sw.getTimeOfDay(), 24000L), Visit.CELEBRATED);
                if (!MemoryStore.updateBirthday(player.getUuid(), null, first)) return ActionResult.PASS;
                AbstractCandleBlock.extinguish(player, state, sw, pos);
                gf.addAffection(3);
                MoodManager.notePlayerChatted(gf);
                SpeechBus.speak(gf, "漂泊者，蜡烛让我吹。……好啦。我的第一个生日蛋糕，我们一人一半。");
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });
    }

    /** Track the owner's departure even if the companion was put away for the trip. */
    static void observeTravel(PlayerEntity owner) {
        Birthday cake = MemoryStore.birthday(owner.getUuid());
        if (cake == null || cake.visit == Visit.REMEMBERED || owner.isSpectator()) return;
        double distance = cake.distanceSquared(owner);
        if (cake.visit == Visit.CELEBRATED && distance >= 24 * 24) {
            MemoryStore.updateBirthday(owner.getUuid(), cake, cake.withVisit(Visit.AWAY));
        } else if (cake.visit == Visit.AWAY && distance <= 8 * 8
                && Math.floorDiv(owner.getEntityWorld().getTimeOfDay(), 24000L) <= cake.day) {
            // Coming back the same day and sleeping here is different from a later return.
            MemoryStore.updateBirthday(owner.getUuid(), cake, cake.withVisit(Visit.CELEBRATED));
        }
    }

    static boolean recallBirthday(GirlfriendEntity gf, PlayerEntity owner) {
        Birthday cake = MemoryStore.birthday(owner.getUuid());
        if (cake == null || cake.visit != Visit.AWAY || owner.isSpectator() || gf.isSleeping()
                || gf.getTarget() != null || cake.distanceSquared(owner) > 8 * 8
                || Math.floorDiv(owner.getEntityWorld().getTimeOfDay(), 24000L) <= cake.day
                || gf.squaredDistanceTo(cake.x + 0.5, cake.y + 0.5, cake.z + 0.5) > 8 * 8) return false;
        if (!MemoryStore.updateBirthday(owner.getUuid(), cake, cake.withVisit(Visit.REMEMBERED))) return false;
        SpeechBus.speak(gf, "漂泊者，还记得这里吗？你点了蜡烛，让我许个愿。那个愿望嘛……说出来就不灵了。");
        return true;
    }

    /** Journal coordinates stay in the game; the language model only receives the story. */
    public static String cakeJournalLine(UUID owner) {
        Birthday cake = MemoryStore.birthday(owner);
        if (cake == null) return null;
        String world = switch (cake.dimension) {
            case "minecraft:overworld" -> "主世界";
            case "minecraft:the_nether" -> "下界";
            case "minecraft:the_end" -> "末地";
            default -> cake.dimension;
        };
        return "我的第一个生日蛋糕 · " + world + "（" + cake.x + ", " + cake.y + ", " + cake.z + "）"
                + (cake.visit == Visit.REMEMBERED ? "。我们后来又一起回到了这里。" : "。漂泊者点了蜡烛，我许了个愿。");
    }
}
