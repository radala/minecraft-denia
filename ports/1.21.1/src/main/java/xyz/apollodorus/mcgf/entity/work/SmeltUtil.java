package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.recipe.AbstractCookingRecipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.RecipeType;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.input.SingleStackRecipeInput;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 达妮娅自主烧炼引擎：
 * 支持原版及任何模组的熔炼配方（SmeltingRecipe / AbstractCookingRecipe）。
 * 当需要目标物品（如铁锭、铜锭、熟食、玻璃等）时，自动识别原料、燃料并自主烧制。
 */
public final class SmeltUtil {
    private SmeltUtil() {}

    public record SmeltResult(boolean ok, int smelted, String missing) {}

    private static final Map<Item, Item> RAW_SOURCES = Map.ofEntries(
        Map.entry(Items.IRON_INGOT, Items.RAW_IRON),
        Map.entry(Items.GOLD_INGOT, Items.RAW_GOLD),
        Map.entry(Items.COPPER_INGOT, Items.RAW_COPPER),
        Map.entry(Items.GLASS, Items.SAND),
        Map.entry(Items.COOKED_BEEF, Items.BEEF),
        Map.entry(Items.COOKED_PORKCHOP, Items.PORKCHOP),
        Map.entry(Items.COOKED_MUTTON, Items.MUTTON),
        Map.entry(Items.COOKED_CHICKEN, Items.CHICKEN),
        Map.entry(Items.COOKED_SALMON, Items.SALMON),
        Map.entry(Items.COOKED_COD, Items.COD),
        Map.entry(Items.STONE, Items.COBBLESTONE),
        Map.entry(Items.SMOOTH_STONE, Items.STONE)
    );

    public static Item getRawSourceFor(Item target) {
        return RAW_SOURCES.get(target);
    }

    private static RecipeManager cachedManager;
    private static Map<Item, List<AbstractCookingRecipe>> recipesByOutput;

    private static List<AbstractCookingRecipe> recipes(ServerWorld world, Item output) {
        RecipeManager manager = world.getServer().getRecipeManager();
        if (cachedManager != manager) {
            Map<Item, List<AbstractCookingRecipe>> recipes = new HashMap<>();
            for (RecipeEntry<?> entry : manager.values()) {
                if (entry.value() instanceof AbstractCookingRecipe recipe && recipe.getType() == RecipeType.SMELTING) {
                    ItemStack result = recipe.craft(new SingleStackRecipeInput(ItemStack.EMPTY), world.getRegistryManager());
                    if (!result.isEmpty()) recipes.computeIfAbsent(result.getItem(), k -> new ArrayList<>()).add(recipe);
                }
            }
            recipesByOutput = recipes;
            cachedManager = manager;
        }
        return recipesByOutput.getOrDefault(output, List.of());
    }

    static int rawCount(GirlfriendEntity gf, Item output) {
        ServerWorld world = (ServerWorld) gf.getEntityWorld();
        List<AbstractCookingRecipe> recipes = recipes(world, output);
        int total = 0;
        for (int i = 0; i < gf.getInventory().size(); i++) {
            ItemStack stack = gf.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            SingleStackRecipeInput input = new SingleStackRecipeInput(stack);
            if (recipes.stream().anyMatch(r -> r.matches(input, world))) total += stack.getCount();
        }
        return total;
    }

    /** Run one portable furnace batch with a real recipe, available fuel and a carried furnace. */
    public static SmeltResult smeltItem(GirlfriendEntity gf, Item output, int count) {
        if (!(gf.getEntityWorld() instanceof ServerWorld world) || gf.countItem(Items.FURNACE) == 0)
            return new SmeltResult(false, 0, "熔炉");
        SimpleInventory source = gf.getInventory();
        // Stage changes locally so full output slots or inadequate fuel cannot destroy ingredients.
        SimpleInventory inventory = new SimpleInventory(source.size());
        for (int i = 0; i < source.size(); i++) inventory.setStack(i, source.getStack(i).copy());
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack raw = inventory.getStack(i);
            if (raw.isEmpty()) continue;
            SingleStackRecipeInput input = new SingleStackRecipeInput(raw);
            AbstractCookingRecipe recipe = recipes(world, output).stream().filter(r -> r.matches(input, world))
                .findFirst().orElse(null);
            if (recipe == null) continue;
            ItemStack result = recipe.craft(input, world.getRegistryManager());
            int batches = Math.min(raw.getCount(), Math.max(1, Math.min(64, count) / result.getCount()));
            int ticks = Math.max(1, recipe.getCookingTime());
            int fuelTicks = 0;
            // Prefer coal and wood over items that may be needed as crafting ingredients.
            for (int pass = 0; pass < 3 && fuelTicks < batches * ticks; pass++) {
                for (int j = 0; j < inventory.size() && fuelTicks < batches * ticks; j++) {
                    ItemStack fuel = inventory.getStack(j);
                    if (j == i || fuel.isEmpty()) continue;
                    boolean preferred = fuel.isOf(Items.COAL) || fuel.isOf(Items.CHARCOAL) || fuel.isOf(Items.LAVA_BUCKET);
                    boolean wood = fuel.isIn(net.minecraft.registry.tag.ItemTags.LOGS)
                        || fuel.isIn(net.minecraft.registry.tag.ItemTags.PLANKS);
                    boolean usable = switch (pass) {
                        case 0 -> preferred;
                        case 1 -> wood;
                        default -> fuel.isOf(Items.STICK);
                    };
                    if (!usable) continue;
                    Integer duration = net.fabricmc.fabric.api.registry.FuelRegistry.INSTANCE.get(fuel.getItem());
                    if (duration == null || duration <= 0) continue;
                    int use = Math.min(fuel.getCount(), (batches * ticks - fuelTicks + duration - 1) / duration);
                    Item remainder = fuel.getItem().getRecipeRemainder();
                    inventory.removeStack(j, use);
                    if (remainder != null && !inventory.addStack(new ItemStack(remainder, use)).isEmpty())
                        return new SmeltResult(false, 0, "背包空间");
                    fuelTicks += duration * use;
                }
            }
            batches = Math.min(batches, fuelTicks / ticks);
            if (batches == 0) return new SmeltResult(false, 0, "燃料");
            inventory.removeStack(i, batches);
            int produced = result.getCount() * batches;
            if (!inventory.addStack(result.copyWithCount(produced)).isEmpty())
                return new SmeltResult(false, 0, "背包空间");
            for (int slot = 0; slot < source.size(); slot++) source.setStack(slot, inventory.getStack(slot));
            gf.swingHand(Hand.MAIN_HAND);
            world.playSound(null, gf.getBlockPos(), SoundEvents.BLOCK_FURNACE_FIRE_CRACKLE, SoundCategory.NEUTRAL, 1.0f, 1.0f);
            world.spawnParticles(ParticleTypes.FLAME, gf.getX(), gf.getY() + 0.8, gf.getZ(), 8, 0.2, 0.2, 0.2, 0.02);
            return new SmeltResult(true, produced, null);
        }
        return new SmeltResult(false, 0, "熔炼原料");
    }
}
