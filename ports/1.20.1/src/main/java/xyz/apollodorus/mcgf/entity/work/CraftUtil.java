package xyz.apollodorus.mcgf.entity.work;

import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeManager;
import net.minecraft.recipe.ShapedRecipe;
import net.minecraft.recipe.ShapelessRecipe;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「便携 + 递归」合成引擎——把原版合成表({@link RecipeManager})接进她的行动逻辑。给定目标产物 {@link Item}，
 * 从她 27 格背包({@link GirlfriendEntity#getInventory()})里取料合成；缺中间材料(木板/木棍等)会自动递归先做出来，
 * 缺原始材料(矿石/线等无法合成物)则如实报「缺 X」，交给上层决定去采。不需要工作台(用她「异世界造物」的设定包装)。
 *
 * <p>只处理普通 {@link ShapedRecipe}/{@link ShapelessRecipe}(跳过染甲/复制地图这类特殊动态配方)。产物用
 * {@code recipe.getOutput(reg)} 取得(普通配方的 craft 只是 {@code result.copy()}，与输入无关)，
 * 消耗清单用 {@code ingredients(recipe)}(与网格摆放无关的扁平必需材料表)，正好对上扁平背包。
 */
public final class CraftUtil {
    private CraftUtil() {}

    /** 合成结果：ok=是否至少做出一个；crafted=实际做出的数量；missing=缺的原始材料名(全成功则 null)。 */
    public record Result(boolean ok, int crafted, String missing) {}

    // 产物 Item -> 首选配方(材料最少者)。按 manager 实例缓存一次，重载世界时自动重建。
    private static volatile RecipeManager cachedManager;
    private static volatile Map<Item, CraftingRecipe> byOutput;

    /** 递归上下文，穿过整条合成链。均在服务器线程上单线程使用。 */
    private static final class Ctx {
        DynamicRegistryManager reg;
        final Set<Item> inProgress = new HashSet<>();   // 环路防护：正在合成链上的产物
        String missing;                                  // 最近一次缺料的物品名
        int maxDepth;
    }

    private static List<Ingredient> ingredients(CraftingRecipe recipe) {
        return recipe.getIngredients().stream().filter(i -> !i.isEmpty()).toList();
    }

    static List<Ingredient> ingredients(GirlfriendEntity gf, Item out) {
        World world = gf.getEntityWorld();
        ensureCache(world.getServer().getRecipeManager(), world);
        CraftingRecipe recipe = byOutput.get(out);
        return recipe == null ? List.of() : ingredients(recipe);
    }

    /**
     * 把 {@code out} 合成到她背包里，直到累计做出 {@code count} 个或做不下去为止。递归先补中间材料。
     * 全程用批快照做回滚：一个批次中途失败会撤销该批的消耗；整体一个都没做出来则完全还原背包。
     */
    public static Result craft(GirlfriendEntity gf, Item out, int count) {
        if (out == null) return new Result(false, 0, null);
        if (count <= 0) count = 1;
        World world = gf.getEntityWorld();
        MinecraftServer server = world.getServer();
        if (server == null) return new Result(false, 0, null);
        RecipeManager rm = server.getRecipeManager();
        ensureCache(rm, world);

        SimpleInventory inv = gf.getInventory();
        List<ItemStack> full = snapshot(inv);
        Ctx ctx = new Ctx();
        ctx.reg = world.getRegistryManager();
        ctx.maxDepth = Math.max(1, ConfigManager.get().behavior.craftMaxDepth);

        int have0 = count(inv, out);
        int guard = 0;                                   // 防止病态配方死循环
        while (count(inv, out) - have0 < count && guard++ < 256) {
            if (!craftBatch(inv, out, 0, ctx)) break;
        }
        int made = count(inv, out) - have0;
        if (made <= 0) {
            restore(inv, full);                          // 一个都没做成 → 完全还原，别白吞材料
            return new Result(false, 0, ctx.missing);
        }
        return new Result(true, made, made < count ? ctx.missing : null);
    }

    /** 合成一个批次(产出 = 配方产量，如 4 块木板)；缺料时递归补齐。成功返回 true 并已改动 inv。 */
    private static boolean craftBatch(SimpleInventory inv, Item out, int depth, Ctx ctx) {
        CraftingRecipe recipe = byOutput == null ? null : byOutput.get(out);
        if (recipe == null) { ctx.missing = WorkUtil.displayName(out); return false; }
        List<Ingredient> ings = ingredients(recipe);
        if (ings.isEmpty()) { ctx.missing = WorkUtil.displayName(out); return false; }

        List<ItemStack> batchSnap = snapshot(inv);
        boolean added = ctx.inProgress.add(out);         // 环路防护：本产物入链
        List<ItemStack> remainders = new ArrayList<>();
        try {
            for (Ingredient ing : ings) {
                int slot = findMatch(inv, ing);
                if (slot < 0) {                          // 背包没有这味料 → 尝试递归做出来
                    if (depth >= ctx.maxDepth || !subCraft(inv, ing, depth + 1, ctx)) {
                        if (ctx.missing == null) ctx.missing = representativeName(ing);
                        restore(inv, batchSnap);
                        return false;
                    }
                    slot = findMatch(inv, ing);
                    if (slot < 0) { restore(inv, batchSnap); return false; }
                }
                ItemStack s = inv.getStack(slot);
                Item remainder = s.getItem().getRecipeRemainder();
                if (remainder != null) remainders.add(new ItemStack(remainder));
                s.decrement(1);
                if (s.isEmpty()) inv.setStack(slot, ItemStack.EMPTY);
            }
        } finally {
            if (added) ctx.inProgress.remove(out);
        }

        ItemStack result;
        try { result = recipe.getOutput(ctx.reg).copy(); }
        catch (Exception e) { restore(inv, batchSnap); return false; }
        if (result.isEmpty()) { restore(inv, batchSnap); return false; }
        remainders.add(result);
        for (ItemStack stack : remainders) {
            if (!inv.addStack(stack.copy()).isEmpty()) {
                restore(inv, batchSnap);
                ctx.missing = "背包空间";
                return false;
            }
        }
        return true;
    }

    /** 为某个 Ingredient 递归做出一味可合成的匹配料：逐个候选物尝试(带回滚)，第一个成功即可。 */
    private static boolean subCraft(SimpleInventory inv, Ingredient ing, int depth, Ctx ctx) {
        int tried = 0;
        for (Item cand : matchingItems(ing)) {
            if (tried++ >= 16) break;
            if (ctx.inProgress.contains(cand)) continue;
            if (byOutput == null || !byOutput.containsKey(cand)) continue;
            if (craftBatch(inv, cand, depth, ctx)) return true;
        }
        return false;
    }

    // --- recipe cache ---

    private static void ensureCache(RecipeManager rm, World world) {
        if (byOutput != null && cachedManager == rm) return;
        Map<Item, CraftingRecipe> map = new HashMap<>();
        DynamicRegistryManager reg = world.getRegistryManager();
        for (Recipe<?> r : rm.values().stream()
            .sorted(java.util.Comparator.comparing(e -> e.getId().toString())).toList()) {
            if (!(r instanceof CraftingRecipe cr)) continue;
            if (!(cr instanceof ShapedRecipe) && !(cr instanceof ShapelessRecipe)) continue; // 跳过特殊动态配方
            ItemStack res;
            try {
                res = cr.getOutput(reg);
            } catch (RuntimeException dynamicResult) {
                continue; // A modded recipe requiring actual input is not a portable recipe.
            }
            if (res.isEmpty()) continue;
            Item out = res.getItem();
            CraftingRecipe prev = map.get(out);
            if (prev == null || ingCount(cr) < ingCount(prev)) map.put(out, cr);   // 材料最少者优先(最基础配方)
        }
        byOutput = map;
        cachedManager = rm;
    }

    private static int ingCount(CraftingRecipe cr) {
        return ingredients(cr).size();
    }

    // --- inventory helpers ---

    private static int count(SimpleInventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.isOf(item)) n += s.getCount();
        }
        return n;
    }

    private static int findMatch(SimpleInventory inv, Ingredient ing) {
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty() && s.getCount() > 0 && ing.test(s)) return i;
        }
        return -1;
    }

    private static List<Item> matchingItems(Ingredient ing) {
        List<Item> out = new ArrayList<>();
        for (ItemStack stack : ing.getMatchingStacks()) out.add(stack.getItem());
        return out;
    }

    private static String representativeName(Ingredient ing) {
        ItemStack[] choices = ing.getMatchingStacks();
        return choices.length == 0 ? "材料" : WorkUtil.displayName(choices[0].getItem());
    }

    private static List<ItemStack> snapshot(SimpleInventory inv) {
        List<ItemStack> snap = new ArrayList<>(inv.size());
        for (int i = 0; i < inv.size(); i++) snap.add(inv.getStack(i).copy());
        return snap;
    }

    private static void restore(SimpleInventory inv, List<ItemStack> snap) {
        for (int i = 0; i < inv.size() && i < snap.size(); i++) inv.setStack(i, snap.get(i).copy());
    }

}
