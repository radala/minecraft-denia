package xyz.apollodorus.mcgf.entity.work;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import xyz.apollodorus.mcgf.ai.MemoryStore;
import xyz.apollodorus.mcgf.combat.AbilityManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/** A south-facing camp centered on the companion. Existing walls count towards the build. */
public final class TravelCamp {
    private TravelCamp() {}

    public record Result(boolean complete, String message) {}
    private record Placement(BlockPos pos, BlockState before) {}

    public static Result build(GirlfriendEntity companion, boolean shared) {
        ServerWorld world = (ServerWorld) companion.getEntityWorld();
        BlockPos center = companion.getBlockPos();
        int radius = shared ? 2 : 1;
        int roofHeight = shared ? 2 : 3;
        var transientBlocks = AbilityManager.blocks();
        List<Placement> missing = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                BlockPos floor = center.add(x, -1, z);
                if (!transientBlocks.stateAfterExpiry(world, floor).isSideSolidFullSquare(world, floor, Direction.UP)) {
                    return new Result(false, "这里地面不平。我们换块平地，或者一起把地面补齐吧。");
                }
                for (int y = 0; y <= roofHeight; y++) {
                    BlockPos pos = center.add(x, y, z);
                    if (!world.isInBuildLimit(pos) || !world.getWorldBorder().contains(pos)) {
                        return new Result(false, "这里太靠近世界边界了，我们换个地方吧。");
                    }
                    BlockState existing = world.getBlockState(pos);
                    boolean edge = Math.abs(x) == radius || Math.abs(z) == radius;
                    boolean doorway = x == 0 && z == radius && y < 2;
                    if (y == roofHeight || (edge && !doorway)) {
                        BlockState permanent = transientBlocks.stateAfterExpiry(world, pos);
                        if (permanent.isFullCube(world, pos) && !permanent.hasBlockEntity()) continue;
                        if (!permanent.isAir() || !world.getEntitiesByClass(LivingEntity.class,
                                new Box(pos), entity -> entity.isAlive() && !entity.isSpectator()).isEmpty()) {
                            return new Result(false, "墙或屋顶的位置被占了，等那里腾出来再搭吧。");
                        }
                        missing.add(new Placement(pos, existing));
                    } else if (!existing.getCollisionShape(world, pos).isEmpty() || !existing.getFluidState().isEmpty()) {
                        return new Result(false, "屋里或南边的门口被挡住了，我们换块空地吧。");
                    }
                }
            }
        }
        BlockPos entrance = center.south(radius + 1);
        if (!transientBlocks.stateAfterExpiry(world, entrance.down()).isSideSolidFullSquare(world, entrance.down(), Direction.UP)
                || !world.getBlockState(entrance).isAir() || !world.getBlockState(entrance.up()).isAir()) {
            return new Result(false, "南边门外还没有能走出去的路，先清出两格高的出口吧。");
        }
        if (missing.isEmpty()) return new Result(true, "墙和屋顶都在，南边的门也通着，我们可以歇脚了。");

        Inventory bag = companion.getInventory();
        List<Integer> materialSlots = new ArrayList<>();
        for (int slot = 0; slot < bag.size() && materialSlots.size() < missing.size(); slot++) {
            ItemStack stack = bag.getStack(slot);
            if (!(stack.getItem() instanceof BlockItem block) || !stack.getComponentChanges().isEmpty()
                    || !buildingMaterial(block.getBlock().getDefaultState())) continue;
            for (int count = 0; count < stack.getCount() && materialSlots.size() < missing.size(); count++) {
                materialSlots.add(slot);
            }
        }
        if (materialSlots.size() < missing.size()) {
            return new Result(false, "还差 " + (missing.size() - materialSlots.size())
                + " 块建材。你可以把木板、圆石或泥土放进我的背包，也可以先帮我搭一面墙。");
        }
        // All edits run on the server thread. Charge the bag only after the complete layout is in place.
        for (int i = 0; i < missing.size(); i++) {
            Placement edit = missing.get(i);
            BlockItem item = (BlockItem) bag.getStack(materialSlots.get(i)).getItem();
            if (world.getBlockState(edit.pos()) != edit.before()
                    || !world.setBlockState(edit.pos(), item.getBlock().getDefaultState())) {
                for (int j = i - 1; j >= 0; j--) {
                    Placement placed = missing.get(j);
                    world.setBlockState(placed.pos(), placed.before());
                }
                return new Result(false, "这里刚刚变了，我收回了建材。我们重新看看位置吧。");
            }
        }
        for (Placement edit : missing) {
            if (!edit.before().isAir()) transientBlocks.forgetReplaced(world, edit.pos());
        }
        for (int slot : materialSlots) bag.removeStack(slot, 1);
        MemoryStore.record(companion, shared ? "我们一起搭了个小营地，把门留在南边。" : "路上搭了间应急避难棚。");
        return new Result(true, shared ? "营地搭好了，门在南边。进来吧，里面放得下我们两张床。"
                                       : "避难棚搭好了，南边留了门。");
    }

    private static boolean buildingMaterial(BlockState state) {
        return state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.LOGS)
            || state.isIn(BlockTags.BASE_STONE_OVERWORLD) || state.isIn(BlockTags.STONE_BRICKS)
            || state.isOf(Blocks.BRICKS) || state.isOf(Blocks.COBBLESTONE)
            || state.isOf(Blocks.COBBLED_DEEPSLATE) || state.isOf(Blocks.DIRT);
    }
}
