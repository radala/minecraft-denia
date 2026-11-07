package xyz.apollodorus.mcgf.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Item;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import xyz.apollodorus.mcgf.ai.JourneyMemories;
import xyz.apollodorus.mcgf.ai.MemoryStore;
import xyz.apollodorus.mcgf.ai.SpeechBus;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity.Task;
import xyz.apollodorus.mcgf.entity.goal.WorkGoal;
import xyz.apollodorus.mcgf.entity.work.BoatUtil;
import xyz.apollodorus.mcgf.entity.work.TravelCamp;
import xyz.apollodorus.mcgf.entity.work.WorkUtil;
import xyz.apollodorus.mcgf.net.JournalPayload;
import xyz.apollodorus.mcgf.net.ModNetworking;
import xyz.apollodorus.mcgf.util.GfFinder;

/** /gf (alias /mcgf) summon|come|follow|stop|reload|mine|chop|harvest|obtain|give|home|guard|gather|wood|boat|dismount|store|release|revive|status|debug|say */
public final class GfCommands {
    private GfCommands() {}

    public static void register() {
        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> {
                var root = dispatcher.register(CommandManager.literal("denia")
                    .then(CommandManager.literal("journal").executes(GfCommands::journal))
                    .then(CommandManager.literal("camp").executes(GfCommands::camp))
                    .then(CommandManager.literal("summon").executes(GfCommands::summon))
                    .then(CommandManager.literal("come").executes(GfCommands::come))
                    .then(CommandManager.literal("follow").executes(GfCommands::follow))
                    .then(CommandManager.literal("stop").executes(GfCommands::stop))
                    .then(CommandManager.literal("reload").executes(GfCommands::reload))
                    .then(CommandManager.literal("harvest").executes(ctx -> setTask(ctx, Task.harvest(), "去收庄稼啦~")))
                    .then(CommandManager.literal("home").executes(GfCommands::home))
                    .then(CommandManager.literal("garrison").executes(GfCommands::garrison))
                    .then(CommandManager.literal("revive").executes(GfCommands::revive))
                    .then(CommandManager.literal("status").executes(GfCommands::status))
                    .then(CommandManager.literal("debug").executes(GfCommands::debug))
                    .then(CommandManager.literal("boat").executes(GfCommands::boat))
                    .then(CommandManager.literal("dismount").executes(GfCommands::dismount))
                    .then(CommandManager.literal("store").executes(GfCommands::store))
                    .then(CommandManager.literal("release").executes(GfCommands::release))
                    .then(CommandManager.literal("mine")
                        .executes(ctx -> setTask(ctx, Task.mine(defCount()), "去挖矿啦~"))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 256))
                            .executes(ctx -> setTask(ctx, Task.mine(IntegerArgumentType.getInteger(ctx, "count")), "去挖矿啦~"))))
                    .then(CommandManager.literal("chop")
                        .executes(ctx -> setTask(ctx, Task.chop(defCount()), "去砍树啦~"))
                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 256))
                            .executes(ctx -> setTask(ctx, Task.chop(IntegerArgumentType.getInteger(ctx, "count")), "去砍树啦~"))))
                    .then(CommandManager.literal("guard")
                        .then(CommandManager.literal("on").executes(ctx -> toggleGuard(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> toggleGuard(ctx, false))))
                    .then(CommandManager.literal("gather")
                        .then(CommandManager.literal("on").executes(ctx -> toggleGather(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> toggleGather(ctx, false))))
                    .then(CommandManager.literal("wood")
                        .then(CommandManager.literal("on").executes(ctx -> toggleWood(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> toggleWood(ctx, false))))
                    .then(CommandManager.literal("pickup")
                        .then(CommandManager.literal("on").executes(ctx -> togglePickup(ctx, true)))
                        .then(CommandManager.literal("off").executes(ctx -> togglePickup(ctx, false))))
                    .then(CommandManager.literal("ult").executes(GfCommands::ult))
                    .then(CommandManager.literal("beacon").executes(GfCommands::beacon))
                    .then(CommandManager.literal("obtain")
                        .then(CommandManager.argument("itemAndCount", StringArgumentType.greedyString())
                            .executes(ctx -> itemCommand(ctx, true))))
                    .then(CommandManager.literal("give")
                        .then(CommandManager.argument("itemAndCount", StringArgumentType.greedyString())
                            .executes(ctx -> itemCommand(ctx, false))))
                    .then(CommandManager.literal("say")
                        .then(CommandManager.argument("text", StringArgumentType.greedyString())
                            .executes(GfCommands::say))));
                // 别名：/gf 与 /mcgf 都重定向到 /denia（保持旧习惯可用）。
                dispatcher.register(CommandManager.literal("gf").redirect(root));
                dispatcher.register(CommandManager.literal("mcgf").redirect(root));
            });
    }

    private static int defCount() {
        return ConfigManager.get().behavior.defaultGatherCount;
    }

    private static int journal(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        var events = MemoryStore.episodes(player.getUuid());
        String birthday = JourneyMemories.cakeJournalLine(player.getUuid());
        StringBuilder page = new StringBuilder("我和达妮娅的旅行手记");
        if (birthday != null) page.append("\n★ ").append(birthday);
        for (String event : events) page.append("\n· ").append(event);
        if (birthday == null && events.isEmpty()) page.append("\n还没有记录。我们一起去走走吧。");
        if (page.length() <= 32767 && ServerPlayNetworking.canSend(player, JournalPayload.ID)) {
            ModNetworking.send(player, JournalPayload.ID, new JournalPayload(page.toString())::write);
        } else {
            player.sendMessage(Text.literal(page.toString()), false);
        }
        return 1;
    }

    private static int camp(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        GirlfriendEntity gf = GfFinder.nearest(player, 8);
        if (gf == null || !gf.isOwner(player)) {
            ctx.getSource().sendError(Text.literal("先和自己的达妮娅一起走到营地选址旁。"));
            return 0;
        }
        TravelCamp.Result result = TravelCamp.build(gf, true);
        SpeechBus.speak(gf, result.message());
        return result.complete() ? 1 : 0;
    }

    private static int summon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        GirlfriendEntity gf = GirlfriendEntities.GIRLFRIEND.spawn(world, player.getBlockPos(), SpawnReason.COMMAND);
        if (gf == null) {
            ctx.getSource().sendError(Text.literal("召唤失败。"));
            return 0;
        }
        gf.setOwnerUuid(player.getUuid());
        gf.setFollowing(true);
        gf.getInventory().addStack(new net.minecraft.item.ItemStack(xyz.apollodorus.mcgf.item.ModItems.BUBBLE_WAND));
        gf.equipSignatureWeapon();
        String name = ConfigManager.get().persona.displayName;
        ctx.getSource().sendFeedback(() -> Text.translatable("commands.mcgf.summoned", name), false);
        return 1;
    }

    private static int come(CommandContext<ServerCommandSource> ctx) {
        return claimAndFollow(ctx, "commands.mcgf.come", true);
    }

    private static int follow(CommandContext<ServerCommandSource> ctx) {
        return claimAndFollow(ctx, "commands.mcgf.follow", true);
    }

    private static int stop(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        gf.setFollowing(false);
        gf.clearTask();
        gf.getNavigation().stop();
        ctx.getSource().sendFeedback(() -> Text.translatable("commands.mcgf.stop"), false);
        return 1;
    }

    private static int setTask(CommandContext<ServerCommandSource> ctx, Task task, String line) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        boolean busy = gf.getTask() != null;
        gf.enqueueTask(task);
        SpeechBus.speak(gf, busy ? "好，我先记下了，忙完手上的就去~" : line);
        return 1;
    }

    /** Brigadier's word parser stops at a colon or a Chinese character; both are valid item input here. */
    private static int itemCommand(CommandContext<ServerCommandSource> ctx, boolean obtain)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var reader = new com.mojang.brigadier.StringReader(StringArgumentType.getString(ctx, "itemAndCount").trim());
        String name;
        if (reader.canRead() && com.mojang.brigadier.StringReader.isQuotedStringStart(reader.peek())) {
            name = reader.readQuotedString();
        } else {
            while (reader.canRead() && !Character.isWhitespace(reader.peek())) reader.skip();
            name = reader.getRead();
        }
        reader.skipWhitespace();
        int count = reader.canRead() ? IntegerArgumentType.integer(1, 256).parse(reader) : (obtain ? 1 : Integer.MAX_VALUE);
        reader.skipWhitespace();
        if (reader.canRead()) {
            ctx.getSource().sendError(Text.literal("用法：物品名 [1 到 256 的数量]"));
            return 0;
        }
        Item item = WorkUtil.resolveItem(name);
        if (item == null) {
            ctx.getSource().sendError(Text.literal("不认识这个物品。"));
            return 0;
        }
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        if (!obtain) {
            int given = gf.giveToOwner(item, count);
            SpeechBus.speak(gf, given > 0 ? "给你~" : "我没有可以交给你的，或是你的背包满了。");
            return 1;
        }
        WorkGoal.OrderReceipt order = WorkGoal.requestItem(gf, item, count);
        String itemName = WorkUtil.displayName(item);
        if (order.queued() > 0) SpeechBus.speak(gf, "记下了，剩下的 " + order.queued() + " 个" + itemName + "备好就给你。");
        else if (order.supported()) SpeechBus.speak(gf, "你要的" + itemName + "，给你。");
        else SpeechBus.speak(gf, "剩下的" + itemName + "我暂时没法弄到。");
        return 1;
    }

    private static int home(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        gf.setHomePos(gf.getBlockPos());
        ctx.getSource().sendFeedback(() -> Text.literal("已把这里记为家。"), false);
        return 1;
    }

    private static int garrison(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        gf.setGarrison(gf.getBlockPos());
        gf.setFollowing(false);
        gf.getNavigation().stop();
        int r = (int) ConfigManager.get().behavior.garrisonRadius;
        ctx.getSource().sendFeedback(() -> Text.literal("达妮娅开始驻守这一带（约 " + r + " 格内，让她跟上/过来即解除）。"), false);
        return 1;
    }

    private static int boat(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        boolean ok = BoatUtil.boardNearest(gf, 6.0);
        SpeechBus.speak(gf, ok ? "上船咯~" : "附近没看到船呢…");
        return ok ? 1 : 0;
    }

    private static int dismount(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        boolean ok = BoatUtil.disembark(gf);
        SpeechBus.speak(gf, ok ? "我下来啦~" : "我现在没在船上喔。");
        return ok ? 1 : 0;
    }

    private static int store(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        GirlfriendEntity gf = GfFinder.nearest(player, 64.0);
        if (gf == null || !gf.isOwner(player)) {
            ctx.getSource().sendError(Text.literal("附近没有你的达妮娅。"));
            return 0;
        }
        if (!xyz.apollodorus.mcgf.entity.ReviveService.capture(player, gf)) {
            ctx.getSource().sendError(Text.literal("这次没能保存行囊，达妮娅还在你身边。"));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.literal("已把达妮娅收进重逢符。"), false);
        return 1;
    }

    private static int release(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        if (!xyz.apollodorus.mcgf.entity.DownedManager.isStored(player.getUuid())) {
            ctx.getSource().sendError(Text.literal("重逢符里现在没有她。"));
            return 0;
        }
        GirlfriendEntity gf = xyz.apollodorus.mcgf.entity.ReviveService.release(
            (ServerWorld) player.getEntityWorld(), player);
        if (gf == null) {
            ctx.getSource().sendError(Text.literal("放出失败。"));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.literal("达妮娅回到了你身边。"), false);
        return 1;
    }

    private static int toggleGuard(CommandContext<ServerCommandSource> ctx, boolean on) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        gf.setCombatEnabled(on);
        ctx.getSource().sendFeedback(() -> Text.literal("战斗护卫已" + (on ? "开启" : "关闭") + "。"), false);
        return 1;
    }

    private static int toggleGather(CommandContext<ServerCommandSource> ctx, boolean on) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        gf.setGatherEnabled(on);
        ctx.getSource().sendFeedback(() -> Text.literal("顺手采集已" + (on ? "开启" : "关闭") + "。"), false);
        return 1;
    }

    private static int toggleWood(CommandContext<ServerCommandSource> ctx, boolean on) {
        ConfigManager.get().behavior.autoGatherWood = on;
        ConfigManager.save(ConfigManager.get());
        ctx.getSource().sendFeedback(() -> Text.literal("自动收集木头已" + (on ? "开启" : "关闭") + "（全局配置）。"), false);
        return 1;
    }

    private static int togglePickup(CommandContext<ServerCommandSource> ctx, boolean on) {
        ConfigManager.get().behavior.autoPickup = on;
        ConfigManager.save(ConfigManager.get());
        ctx.getSource().sendFeedback(() -> Text.literal("自动拾取已" + (on ? "开启" : "关闭") + "（全局配置）。"), false);
        return 1;
    }

    private static int beacon(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        player.giveItemStack(new net.minecraft.item.ItemStack(xyz.apollodorus.mcgf.item.ModItems.PATH_BEACON, 4));
        ctx.getSource().sendFeedback(() -> Text.literal("给你 4 个寻路信标，丢出去她就会带你过去~"), false);
        return 1;
    }

    private static int ult(CommandContext<ServerCommandSource> ctx) {        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        if (gf.tryDeployDomain()) {                       // tryDeployDomain fires the in-character AI line
            ctx.getSource().sendFeedback(() -> Text.literal("达妮娅认真起来了！"), false);
            return 1;
        }
        ctx.getSource().sendError(Text.literal("现在用不出来——她已经在状态里了。"));
        return 0;
    }

    private static int revive(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return 0;
        if (!xyz.apollodorus.mcgf.entity.DownedManager.isDowned(player.getUuid())) {
            ctx.getSource().sendError(Text.literal("她现在好好的呀，不用复活。"));
            return 0;
        }
        GirlfriendEntity gf = xyz.apollodorus.mcgf.entity.ReviveService.revive(
            (ServerWorld) player.getEntityWorld(), player);
        if (gf == null) {
            ctx.getSource().sendError(Text.literal("复活失败。"));
            return 0;
        }
        ctx.getSource().sendFeedback(() -> Text.literal("她回来了。"), false);
        return 1;
    }

    private static int status(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        String info = ConfigManager.get().persona.displayName
            + "  好感度 ♥" + gf.getAffection() + "/100"
            + "  生命 " + (int) gf.getHealth() + "/" + (int) gf.getMaxHealth()
            + "  " + (gf.isFollowing() ? "跟随" : "待命")
            + "  护卫" + (gf.isCombatEnabled() ? "开" : "关")
            + "  采集" + (gf.isGatherEnabled() ? "开" : "关")
            + "  木头" + (ConfigManager.get().behavior.autoGatherWood ? "开" : "关")
            + "  拾取" + (ConfigManager.get().behavior.autoPickup ? "开" : "关")
            + "  能量" + gf.getEnergy() + "/" + ConfigManager.get().behavior.domainEnergyCost
            + "  正在「" + gf.getActivity() + "」";
        ctx.getSource().sendFeedback(() -> Text.literal(info), false);
        return 1;
    }

    private static int debug(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        String info = "模式=" + (gf.isFollowing() ? "跟随" : "待命")
            + " 护卫=" + gf.isCombatEnabled()
            + " 采集=" + gf.isGatherEnabled()
            + " 活动=" + gf.getActivity()
            + " 任务=" + gf.getTasks().size() + (gf.getTask() == null ? "" : "(" + gf.getTask().kind + ")")
            + " 需求=" + gf.getNeeds().size()
            + " 家=" + (gf.getHomePos() == null ? "无" : gf.getHomePos().toShortString());
        ctx.getSource().sendFeedback(() -> Text.literal(info), false);
        return 1;
    }

    private static int say(CommandContext<ServerCommandSource> ctx) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        String text = StringArgumentType.getString(ctx, "text");
        SpeechBus.speak(gf, text);
        return 1;
    }

    private static int reload(CommandContext<ServerCommandSource> ctx) {
        try {
            ConfigManager.load();
            ctx.getSource().sendFeedback(() -> Text.translatable("commands.mcgf.reloaded"), true);
            return 1;
        } catch (Exception e) {
            ctx.getSource().sendError(Text.translatable("commands.mcgf.reload_failed"));
            return 0;
        }
    }

    private static int claimAndFollow(CommandContext<ServerCommandSource> ctx, String feedbackKey, boolean follow) {
        GirlfriendEntity gf = require(ctx);
        if (gf == null) return 0;
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player != null) gf.setOwnerUuid(player.getUuid());
        gf.setFollowing(follow);
        gf.clearTask();
        gf.clearGarrison();   // 跟上/过来 → 自动解除驻守
        ctx.getSource().sendFeedback(() -> Text.translatable(feedbackKey), false);
        return 1;
    }

    /** Resolve the player's nearby girlfriend or report none. */
    private static GirlfriendEntity require(CommandContext<ServerCommandSource> ctx) {
        ServerPlayerEntity player = ctx.getSource().getPlayer();
        if (player == null) return null;
        GirlfriendEntity gf = GfFinder.nearest(player, 64.0);
        if (gf == null) {
            ctx.getSource().sendError(Text.translatable("commands.mcgf.none"));
        }
        return gf;
    }
}
