package xyz.apollodorus.mcgf.entity.work;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.entity.goal.WorkGoal;

public class SupplyOrderGameTests {
    @GameTest(maxTicks = 200)
    public void fullPackKeepsPartialOrderUntilSpaceIsAvailable(TestContext ctx) {
        BlockPos start = new BlockPos(3, 2, 3);
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++)
            ctx.setBlockState(new BlockPos(x, 1, z), Blocks.BEDROCK);
        var gf = ctx.spawnEntity(GirlfriendEntities.GIRLFRIEND, start);
        var owner = ctx.createMockCreativeServerPlayerInWorld();
        owner.refreshPositionAndAngles(ctx.getAbsolutePos(start), 0, 0);
        gf.setOwnerUuid(owner.getUuid());
        gf.setFollowing(false);
        gf.setGatherEnabled(false);
        var bag = owner.getInventory();
        for (int i = 0; i < 36; i++) bag.setStack(i, new ItemStack(Items.BEDROCK, 64));
        bag.setStack(0, new ItemStack(Items.COAL, 63));
        gf.getInventory().addStack(new ItemStack(Items.COAL, 5));
        WorkGoal.OrderReceipt receipt = WorkGoal.requestItem(gf, Items.COAL, 3);
        ctx.assertEquals(1, receipt.given(), "One free space in the stack");
        ctx.assertEquals(2, receipt.queued(), "Two still owed");
        ctx.assertEquals(4, gf.countItem(Items.COAL), "Unaccepted items stay with companion");
        ctx.runAtTick(45, () -> {
            ctx.assertEquals(4, gf.countItem(Items.COAL), "Retries against a full pack preserve stock");
            ctx.assertEquals(2, gf.getTasks().getFirst().remaining, "No credit for attempted transfer");
            bag.setStack(1, ItemStack.EMPTY);
        });
        ctx.addInstantFinalTask(() -> {
            ctx.assertEquals(66, bag.count(Items.COAL), "Delivered exactly the requested amount");
            ctx.assertEquals(2, gf.countItem(Items.COAL), "Surplus belongs to companion");
            ctx.assertTrue(gf.getTasks().isEmpty(), "Clear fulfilled order");
        });
    }

    @GameTest(environment = "denia_tests:isolated_commission", maxTicks = 3000)
    public void bootstrapIronPickaxeFromWoodAndExposedStone(TestContext ctx) {
        for (int x = 0; x < 8; x++) for (int z = 0; z < 8; z++)
            ctx.setBlockState(new BlockPos(x, 1, z), Blocks.BEDROCK);
        for (int x = 2; x <= 4; x++) {
            ctx.setBlockState(new BlockPos(x, 2, 1), Blocks.OAK_LOG);
            ctx.setBlockState(new BlockPos(x, 3, 1), Blocks.OAK_LOG);
            ctx.setBlockState(new BlockPos(x, 2, 6), Blocks.IRON_ORE);
        }
        for (int z = 2; z < 6; z++) for (int y = 2; y < 5; y++)
            ctx.setBlockState(new BlockPos(1, y, z), Blocks.STONE);
        var gf = ctx.spawnEntity(GirlfriendEntities.GIRLFRIEND, 3, 2, 3);
        var owner = ctx.createMockCreativeServerPlayerInWorld();
        owner.refreshPositionAndAngles(ctx.getAbsolutePos(new BlockPos(4, 2, 3)), 0, 0);
        gf.setOwnerUuid(owner.getUuid());
        gf.setFollowing(false);
        gf.setGatherEnabled(false);
        WorkGoal.requestItem(gf, Items.IRON_PICKAXE, 1);
        ctx.addInstantFinalTask(() -> {
            ctx.assertEquals(1, owner.getInventory().count(Items.IRON_PICKAXE),
                "Working from an empty bag: " + gf.taskLabels() + " / " + gf.getActivity());
            ctx.assertEquals(0, owner.getInventory().count(Items.COBBLESTONE), "Do not deliver furnace ingredients");
            ctx.assertTrue(gf.getTasks().isEmpty(), "Clear fulfilled order");
        });
    }
}
