package xyz.apollodorus.mcgf.entity.work;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity.Task;
import xyz.apollodorus.mcgf.entity.goal.WorkGoal;

/** One outstanding delivery, replanned from real bag contents whenever supplies or terrain change. */
public final class SupplyOrder {
    private SupplyOrder() {}

    public record Step(Task gather, String description, boolean blocked) {
        static Step gathering(Task task, String subject) { return new Step(task, "寻找" + subject, false); }
        static Step waiting(String reason) { return new Step(null, reason, true); }
        static Step progressed(String description) { return new Step(null, description, false); }
    }

    public static boolean canSupply(GirlfriendEntity gf, Item item) {
        return gf.countItem(item) > 0 || WorkGoal.hasBlockSource(item)
            || (ConfigManager.get().behavior.autoCraft && (SmeltUtil.getRawSourceFor(item) != null
                || !CraftUtil.ingredients(gf, item).isEmpty()));
    }

    /** Satisfy one production step without turning intermediates into separate deliveries. */
    public static Step produce(GirlfriendEntity gf, Item item, int required) {
        return produce(gf, item, required, new HashSet<>());
    }

    private static Step produce(GirlfriendEntity gf, Item item, int required, Set<Item> parents) {
        String name = WorkUtil.displayName(item);
        if (!parents.add(item) || parents.size() > ConfigManager.get().behavior.craftMaxDepth + 3)
            return Step.waiting("需要你提供" + name);
        int deficit = Math.max(1, required - gf.countItem(item));
        if (!ConfigManager.get().behavior.autoCraft)
            return WorkGoal.hasBlockSource(item) ? Step.gathering(Task.obtain(item, deficit), name)
                : Step.waiting("需要开启自动合成或提供" + name);

        Item raw = SmeltUtil.getRawSourceFor(item);
        if (raw != null) {
            int available = SmeltUtil.rawCount(gf, item);
            // A batch shares fuel. Gather the requested inputs before lighting the furnace.
            if (available < Math.min(64, deficit))
                return produce(gf, raw, gf.countItem(raw) + Math.min(64, deficit) - available, parents);
            if (gf.countItem(Items.FURNACE) == 0) return produce(gf, Items.FURNACE, 1, parents);
            SmeltUtil.SmeltResult result = SmeltUtil.smeltItem(gf, item, deficit);
            if ("燃料".equals(result.missing())) return Step.gathering(Task.chop(1), "烧炉用的木头");
            return result.ok() ? Step.progressed("刚烧好" + name) : Step.waiting("需要" + result.missing());
        }

        if (WorkGoal.isRawMaterial(item)) return Step.gathering(Task.obtain(item, deficit), name);
        List<Ingredient> recipe = CraftUtil.ingredients(gf, item);
        if (recipe.isEmpty()) return WorkGoal.hasBlockSource(item)
            ? Step.gathering(Task.obtain(item, deficit), name) : Step.waiting("需要你提供" + name);
        CraftUtil.Result result = CraftUtil.craft(gf, item, Math.min(64, deficit));
        if (result.ok()) return Step.progressed("刚做好" + name);
        if ("背包空间".equals(result.missing())) return Step.waiting("背包满了，需要腾出位置");

        // Count repeated recipe positions, including tag alternatives (e.g. three ingots, two sticks).
        Map<List<Item>, Integer> quantities = new LinkedHashMap<>();
        for (Ingredient ingredient : recipe) {
            List<Item> options = ingredient.getMatchingItems().map(RegistryEntry::value).toList();
            if (!options.isEmpty()) quantities.merge(options, 1, Integer::sum);
        }
        for (var entry : quantities.entrySet()) {
            List<Item> choices = entry.getKey();
            int have = choices.stream().mapToInt(gf::countItem).sum();
            if (have >= entry.getValue()) continue;
            if (choices.size() > 1 && choices.stream().allMatch(i -> new ItemStack(i).isIn(ItemTags.PLANKS))) {
                for (Item wood : choices) {
                    if (CraftUtil.craft(gf, wood, 1).ok()) return Step.progressed("备好木板");
                }
                return Step.gathering(Task.chop(1), "做木板的原木");
            }
            Item chosen = choices.contains(Items.COBBLESTONE) ? Items.COBBLESTONE : choices.getFirst();
            return produce(gf, chosen, gf.countItem(chosen) + entry.getValue() - have, parents);
        }
        return Step.waiting("还缺合适的材料，或者背包需要腾出位置");
    }
}
