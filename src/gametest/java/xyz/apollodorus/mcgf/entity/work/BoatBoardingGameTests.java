package xyz.apollodorus.mcgf.entity.work;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.GameTestPlayers;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

public class BoatBoardingGameTests {
    @GameTest
    public void boardsOpenBoatBeyondTwoVillagers(TestContext ctx) {
        var owner = GameTestPlayers.survivalPlayer(ctx, "DockWalker");
        try {
            var companion = prepareDock(ctx, owner);
            var near = ctx.spawnEntity(EntityType.OAK_BOAT, 3, 2, 3);
            var far = ctx.spawnEntity(EntityType.OAK_BOAT, 6, 2, 3);
            for (int i = 0; i < 2; i++) {
                var villager = ctx.spawnMob(EntityType.VILLAGER, 3, 2, 3);
                ctx.assertTrue(villager.startRiding(near), "Both seats in the near boat must be occupied");
            }
            ctx.assertTrue(companion.distanceTo(near) < companion.distanceTo(far)
                    && companion.distanceTo(far) <= 6, "Both boats must be in boarding range");

            requestBoarding(ctx, owner);
            ctx.assertTrue(companion.getVehicle() == far, "The empty boat behind the full boat should be boarded");
            ctx.assertEquals(2, near.getPassengerList().size(), "Both original villagers must keep their seats");
            ctx.assertEquals(1, far.getPassengerList().size(), "The companion should occupy the empty boat");
        } finally {
            ctx.getWorld().getServer().getPlayerManager().remove(owner);
        }
        ctx.complete();
    }

    @GameTest
    public void preservesDriverAndWaitsForAFreeSeat(TestContext ctx) {
        var owner = GameTestPlayers.survivalPlayer(ctx, "DockDriver");
        try {
            var companion = prepareDock(ctx, owner);
            var boat = ctx.spawnEntity(EntityType.OAK_BOAT, 3, 2, 3);
            var passenger = ctx.spawnMob(EntityType.VILLAGER, 3, 2, 3);
            ctx.assertTrue(owner.startRiding(boat) && passenger.startRiding(boat), "Fill the owner's boat");

            requestBoarding(ctx, owner);
            ctx.assertFalse(companion.hasVehicle(), "The companion must stay ashore if every seat is taken");
            ctx.assertTrue(boat.getControllingPassenger() == owner && passenger.getVehicle() == boat,
                    "A failed boarding request must leave the original occupants seated");

            passenger.stopRiding();
            var farther = ctx.spawnEntity(EntityType.OAK_BOAT, 6, 2, 3);
            requestBoarding(ctx, owner);
            ctx.assertTrue(companion.getVehicle() == boat && farther.getPassengerList().isEmpty(),
                    "The closest boat with a free seat should take priority");
            ctx.assertTrue(boat.getControllingPassenger() == owner, "The owner must keep the driver's seat");
        } finally {
            ctx.getWorld().getServer().getPlayerManager().remove(owner);
        }
        ctx.complete();
    }

    private static GirlfriendEntity prepareDock(TestContext ctx, ServerPlayerEntity owner) {
        for (int x = 0; x < 8; x++) {
            for (int z = 1; z < 6; z++) {
                ctx.setBlockState(new BlockPos(x, 0, z), Blocks.STONE);
                ctx.setBlockState(new BlockPos(x, 1, z), x < 3 ? Blocks.OAK_PLANKS : Blocks.WATER);
            }
        }
        owner.refreshPositionAndAngles(ctx.getAbsolutePos(new BlockPos(1, 2, 2)), 0, 0);
        var companion = ctx.spawnMob(GirlfriendEntities.GIRLFRIEND, 1, 2, 3);
        companion.setOwnerUuid(owner.getUuid());
        companion.setAiDisabled(true);
        return companion;
    }

    private static void requestBoarding(TestContext ctx, ServerPlayerEntity owner) {
        ctx.getWorld().getServer().getCommandManager().parseAndExecute(owner.getCommandSource(), "denia boat");
    }
}
