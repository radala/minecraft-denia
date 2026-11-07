package xyz.apollodorus.mcgf.ai;

import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.TestContext;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public class JourneyGameTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void recordsFirstEpisodeInLegacyJournal(TestContext ctx) throws Exception {
        UUID owner = UUID.randomUUID();
        Path dir = ctx.getWorld().getServer().getSavePath(WorldSavePath.ROOT).resolve("mcgf-memory");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(owner + ".json"), "{\"memory\":\"她喜欢钓鱼\",\"episodes\":[\"一起出发\"]}");
        GirlfriendEntity gf = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, new BlockPos(1, 2, 1));
        gf.setOwnerUuid(owner);
        MemoryStore.record(gf, "钓到了第一条鱼");
        MemoryStore.record(gf, "同一刻的打扰");
        ctx.assertEquals(List.of("一起出发", "钓到了第一条鱼"), MemoryStore.episodes(owner), "first event, then throttle");
        ctx.assertEquals("她喜欢钓鱼", MemoryStore.getMemory(owner), "old summary survives");
        ctx.assertTrue(JourneyMemories.cakeJournalLine(owner) == null, "old save has no cake milestone");
        gf.discard();
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void celebrateAndRememberBirthday(TestContext ctx) throws Exception {
        ServerWorld world = ctx.getWorld();
        BlockPos relative = new BlockPos(3, 2, 3);
        BlockPos pos = ctx.getAbsolutePos(relative);
        ctx.setBlockState(relative.down(), Blocks.STONE);
        ctx.setBlockState(relative, Blocks.RED_CANDLE_CAKE.getDefaultState().with(AbstractCandleBlock.LIT, true));
        GirlfriendEntity gf = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, relative.east());
        PlayerEntity owner = ctx.createMockPlayer(GameMode.SURVIVAL);
        owner.setPosition(Vec3d.ofCenter(pos.south()));
        owner.setSneaking(true);
        ctx.assertEquals(ActionResult.PASS, interact(world, gf, owner), "only the owner gives this gift");
        gf.setOwnerUuid(owner.getUuid());
        owner.setSneaking(false);
        ctx.assertEquals(ActionResult.PASS, interact(world, gf, owner), "ordinary click opens the usual screen");
        owner.setSneaking(true);
        int affection = gf.getAffection();
        ctx.assertEquals(ActionResult.SUCCESS, interact(world, gf, owner), "give lit birthday cake");
        ctx.assertTrue(world.getBlockState(pos).isOf(Blocks.RED_CANDLE_CAKE)
                && !world.getBlockState(pos).get(AbstractCandleBlock.LIT), "candle extinguished, cake preserved");
        ctx.assertEquals(ActionResult.PASS, interact(world, gf, owner), "no second gift");
        ctx.assertEquals(affection + 3, gf.getAffection(), "single affection gain");

        JourneyMemories.Birthday cake = MemoryStore.birthday(owner.getUuid());
        Path dir = world.getServer().getSavePath(WorldSavePath.ROOT).resolve("mcgf-memory");
        UUID reloaded = UUID.randomUUID();
        Files.copy(dir.resolve(owner.getUuid() + ".json"), dir.resolve(reloaded + ".json"));
        ctx.assertEquals(cake, MemoryStore.birthday(reloaded), "milestone reloads from disk");
        ctx.assertFalse(JourneyMemories.recallBirthday(gf, owner), "staying by the cake is not a return");

        owner.setPosition(Vec3d.ofCenter(pos.east(30)));
        JourneyMemories.observeTravel(owner);
        owner.setPosition(Vec3d.ofCenter(pos));
        JourneyMemories.observeTravel(owner);
        ctx.assertFalse(JourneyMemories.recallBirthday(gf, owner), "an immediate return is not a later visit");
        owner.setPosition(Vec3d.ofCenter(pos.east(30)));
        JourneyMemories.observeTravel(owner);
        long startTime = world.getTimeOfDay();
        try {
            world.setTimeOfDay(startTime + 24000L);
            ctx.assertFalse(JourneyMemories.recallBirthday(gf, owner), "both must be near the cake site");
            owner.setPosition(Vec3d.ofCenter(pos));
            ctx.assertTrue(JourneyMemories.recallBirthday(gf, owner), "revisit next day");
            ctx.assertFalse(JourneyMemories.recallBirthday(gf, owner), "only reminisce once");
        } finally {
            world.setTimeOfDay(startTime);
        }
        ctx.assertTrue(JourneyMemories.cakeJournalLine(owner.getUuid()).contains("又一起回到了这里"), "journal keeps the revisit");
        gf.discard();
        ctx.complete();
    }

    private static ActionResult interact(ServerWorld world, GirlfriendEntity gf, PlayerEntity owner) {
        return UseEntityCallback.EVENT.invoker().interact(owner, world, Hand.MAIN_HAND, gf, null);
    }
}
