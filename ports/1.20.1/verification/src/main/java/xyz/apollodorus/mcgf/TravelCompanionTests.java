package xyz.apollodorus.mcgf;

import io.netty.buffer.Unpooled;
import net.minecraft.network.PacketByteBuf;
import xyz.apollodorus.mcgf.net.ConfigUpdatePayload;
import static xyz.apollodorus.mcgf.PortAssertions.assertEquals;

import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.block.ModBlocks;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.work.TravelCamp;
import xyz.apollodorus.mcgf.util.GfFinder;

public class TravelCompanionTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void configUpdatePreservesWireFields(TestContext ctx) {
        String[] strings = { "https://llm.invalid/v1", "fake-test-key", "test-model",
                "https://tts.invalid/speech", "sample.wav", "语音测试" };
        var buf = new PacketByteBuf(Unpooled.buffer());
        try {
            for (int flags = 0; flags <= 0xff; flags++) {
                buf.clear();
                var expected = new ConfigUpdatePayload(strings[0], strings[1], strings[2],
                        (flags & 1) != 0, strings[3], strings[4], strings[5],
                        (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
                        (flags & 32) != 0, (flags & 64) != 0, (flags & 128) != 0);
                expected.write(buf);
                // Checking the wire separately catches identical mistakes in the encoder and decoder.
                for (String field : strings) {
                    ctx.assertTrue(field.equals(buf.readString()), "Config string order changed");
                }
                ctx.assertTrue(buf.readVarInt() == flags && !buf.isReadable(), "Config flag layout changed");
                buf.readerIndex(0);
                var decoded = ConfigUpdatePayload.read(buf);
                ctx.assertTrue(expected.equals(decoded) && !buf.isReadable(), "Config round trip failed for " + flags);
            }
        } finally {
            buf.release();
        }
        ctx.complete();
    }

    private static void layFloor(TestContext ctx) {
        for (int x = 0; x < 8; x++)
            for (int z = 0; z < 8; z++) ctx.setBlockState(new BlockPos(x, 1, z), Blocks.STONE);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void finishSharedCampWithPlayerSuppliedWall(TestContext ctx) {
        layFloor(ctx);
        GirlfriendEntity gf = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, 3, 2, 3);
        // The player already placed one wall block. That leaves 54 blocks to supply.
        ctx.setBlockState(new BlockPos(1, 2, 1), Blocks.BRICKS);
        gf.getInventory().addStack(new ItemStack(Items.COBBLESTONE, 30));
        var missing = TravelCamp.build(gf, true);
        ctx.assertFalse(missing.complete(), "Should wait for materials");
        ctx.assertTrue(missing.message().contains("24"), "Must tell the player the actual shortage");
        assertEquals(ctx, 30, gf.countItem(Items.COBBLESTONE), "Preflight must not spend materials");
        gf.getInventory().addStack(new ItemStack(Items.OAK_PLANKS, 24));
        var built = TravelCamp.build(gf, true);
        ctx.assertTrue(built.complete(), built.message());
        ctx.expectBlock(Blocks.BRICKS, new BlockPos(1, 2, 1));
        ctx.expectBlock(Blocks.AIR, new BlockPos(3, 2, 5));
        ctx.expectBlock(Blocks.AIR, new BlockPos(3, 3, 5));
        ctx.assertTrue(!ctx.getBlockState(new BlockPos(3, 4, 3)).isAir(), "Missing roof");
        assertEquals(ctx, 0, gf.countItem(Items.COBBLESTONE) + gf.countItem(Items.OAK_PLANKS), "Resource cost");
        ctx.assertTrue(TravelCamp.build(gf, true).complete(), "Retrying a finished site should be free");
        gf.discard();
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 140)
    public void campBuildsLastingLintelOverTemporaryLight(TestContext ctx) {
        layFloor(ctx);
        var gf = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, 3, 2, 3);
        gf.setAiDisabled(true);
        var world = ctx.getWorld();
        var center = gf.getBlockPos();
        var door = center.south(2);
        var lintel = door.up(2);
        AbilityManager.blocks().place(world, lintel, world.getTime() + 60, null);
        gf.getInventory().addStack(new ItemStack(Items.OAK_PLANKS, 54));
        ctx.assertFalse(TravelCamp.build(gf, true).complete(), "The lamp cannot supply the 55th block");
        assertEquals(ctx, 54, gf.countItem(Items.OAK_PLANKS), "Shortage must preserve all material");
        ctx.assertTrue(world.getBlockState(lintel).isOf(ModBlocks.VOID_BLOCK), "Shortage must preserve the live lamp");
        gf.getInventory().addStack(new ItemStack(Items.OAK_PLANKS));
        ctx.assertTrue(TravelCamp.build(gf, true).complete(), "A single well-supplied order should finish");
        ctx.assertTrue(world.getBlockState(lintel).isOf(Blocks.OAK_PLANKS), "The lintel must be permanent immediately");
        assertEquals(ctx, 0, gf.countItem(Items.OAK_PLANKS), "55 planks must be spent");
        ctx.runAtTick(80, () -> {
            int planks = 0;
            for (var pos : BlockPos.iterate(center.add(-2, 0, -2), center.add(2, 2, 2))) {
                if (world.getBlockState(pos).isOf(Blocks.OAK_PLANKS)) planks++;
            }
            assertEquals(ctx, 55, planks, "All 55 planks must survive the lamp expiry");
            ctx.assertTrue(world.getBlockState(lintel).isOf(Blocks.OAK_PLANKS), "Lintel after expiry");
            ctx.assertTrue(world.isAir(door) && world.isAir(door.up()), "South doorway must remain open");
            ctx.assertTrue(TravelCamp.build(gf, true).complete(), "Retry must succeed with an empty bag");
            gf.discard();
            ctx.complete();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void campPreservesExistingWallsCoveredByVoid(TestContext ctx) {
        layFloor(ctx);
        var gf = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, 3, 2, 3);
        var world = ctx.getWorld();
        var corner = gf.getBlockPos().north(2).east(2);
        var covered = corner.up();
        var coverOwner = new Object();
        world.setBlockState(corner, ModBlocks.VOID_BLOCK.getDefaultState());
        world.setBlockState(covered, Blocks.BRICKS.getDefaultState());
        AbilityManager.blocks().place(world, covered, world.getTime() + 100, coverOwner);
        gf.getInventory().addStack(new ItemStack(Items.OAK_PLANKS, 53));
        ctx.assertTrue(TravelCamp.build(gf, true).complete(), "Two permanent wall blocks should count");
        assertEquals(ctx, 0, gf.countItem(Items.OAK_PLANKS), "Only the 53 empty positions cost materials");
        AbilityManager.blocks().restoreOwned(coverOwner);
        ctx.assertTrue(world.getBlockState(covered).isOf(Blocks.BRICKS), "The covered brick must be restored");
        ctx.assertTrue(world.getBlockState(corner).isOf(ModBlocks.VOID_BLOCK), "Player-placed void must be preserved");
        gf.discard();
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void campProtectsOccupiedDoorAndChest(TestContext ctx) {
        layFloor(ctx);
        GirlfriendEntity gf = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, 3, 2, 3);
        gf.getInventory().addStack(new ItemStack(Items.DIRT, 64));
        BlockPos doorway = new BlockPos(3, 2, 5);
        BlockPos wall = new BlockPos(1, 2, 1);
        ctx.setBlockState(doorway, Blocks.STONE);
        ctx.assertFalse(TravelCamp.build(gf, true).complete(), "Blocked door");
        ctx.setBlockState(doorway, Blocks.AIR);
        ctx.setBlockState(wall, Blocks.CHEST);
        ctx.assertFalse(TravelCamp.build(gf, true).complete(), "Occupied wall");
        ctx.expectBlock(Blocks.CHEST, wall);
        assertEquals(ctx, 64, gf.countItem(Items.DIRT), "Blocked layout must not consume materials");
        gf.discard();
        ctx.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 1000)
    public void deliverIronPickaxeFromExposedOre(TestContext ctx) {
        layFloor(ctx);
        var gf = ctx.spawnEntity(GirlfriendEntities.GIRLFRIEND, 3, 2, 3);
        var owner = ctx.createMockCreativeServerPlayerInWorld();
        owner.refreshPositionAndAngles(ctx.getAbsolutePos(new BlockPos(4, 2, 4)), 0, 0);
        gf.setOwnerUuid(owner.getUuid());
        gf.setFollowing(false);
        gf.setGatherEnabled(false);
        gf.getInventory().addStack(new ItemStack(Items.FURNACE));
        gf.getInventory().addStack(new ItemStack(Items.COAL));
        gf.getInventory().addStack(new ItemStack(Items.STONE_PICKAXE));
        gf.getInventory().addStack(new ItemStack(Items.STICK, 2));
        for (int x = 2; x <= 4; x++) ctx.setBlockState(new BlockPos(x, 2, 2), Blocks.IRON_ORE);
        ctx.getWorld().getServer().getCommandManager().executeWithPrefix(owner.getCommandSource(),
            "denia obtain minecraft:iron_pickaxe 1");
        ctx.assertTrue(!gf.getTasks().isEmpty(), "Player command did not start the order");
        ctx.addInstantFinalTask(() -> {
            assertEquals(ctx, 1, owner.getInventory().count(Items.IRON_PICKAXE),
                "Iron pickaxe has not reached the owner; current task: " + gf.taskLabels() + " / " + gf.getActivity());
            assertEquals(ctx, 0, owner.getInventory().count(Items.RAW_IRON), "Handed out ingredients early");
            ctx.assertTrue(gf.getTasks().isEmpty(), "Order must finish on delivery");
            gf.discard();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void ordersStayWithTheirOwner(TestContext ctx) {
        BlockPos start = new BlockPos(3, 2, 3);
        var player = ctx.createMockCreativeServerPlayerInWorld();
        player.refreshPositionAndAngles(ctx.getAbsolutePos(start), 0, 0);
        var other = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, start);
        other.setOwnerUuid(java.util.UUID.randomUUID());
        ctx.assertTrue(GfFinder.nearest(player, 2) == null, "Another player's companion took the order");
        var own = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, start.east());
        own.setOwnerUuid(player.getUuid());
        ctx.assertTrue(GfFinder.nearest(player, 2) == own, "Could not find owned companion");
        own.discard();
        other.discard();
        ctx.complete();
    }
}
