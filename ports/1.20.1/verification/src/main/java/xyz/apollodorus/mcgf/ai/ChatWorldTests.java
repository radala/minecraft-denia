package xyz.apollodorus.mcgf.ai;

import static xyz.apollodorus.mcgf.PortAssertions.assertEquals;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.test.GameTest;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import xyz.apollodorus.mcgf.entity.GirlfriendEntities;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class ChatWorldTests implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE, tickLimit = 350)
    public void commissionUsesReceiptBeforeDeliveryAndRemembersPartialQuantity(TestContext test) {
        var owner = test.createMockCreativeServerPlayerInWorld();
        var pos = new BlockPos(2, 2, 2);
        test.setBlockState(pos.down(), Blocks.BEDROCK);
        owner.refreshPositionAndAngles(test.getAbsolutePos(pos), 0, 0);
        var gf = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, pos);
        gf.setOwnerUuid(owner.getUuid());
        gf.setGatherEnabled(false);
        gf.getInventory().addStack(new ItemStack(Items.IRON_PICKAXE));
        gf.getInventory().addStack(new ItemStack(Items.IRON_INGOT, 3));
        gf.getInventory().addStack(new ItemStack(Items.STICK, 2));

        var counter = new AtomicInteger();
        var history = new AtomicReference<String>();
        var brain = new ChatBrain(test.getWorld().getServer(), (messages, tools) -> {
            int request = counter.incrementAndGet();
            var assistant = new JsonObject();
            if (request == 1) {
                var fn = new JsonObject();
                fn.addProperty("name", "obtain_item");
                fn.addProperty("arguments", "{\"item\":\"minecraft:iron_pickaxe\",\"count\":2}");
                var call = new JsonObject();
                call.addProperty("id", "order");
                call.add("function", fn);
                var calls = new JsonArray();
                calls.add(call);
                assistant.add("tool_calls", calls);
            } else if (tools != null) {
                history.set(messages.toString());
            }
            assistant.addProperty("content", tools == null ? "还要烧矿石" : "一起走吧");
            return assistant;
        });
        brain.handleChat(owner, gf, "帮我准备两把铁镐交给我");
        var askedAgain = new AtomicBoolean();
        test.addInstantFinalTask(() -> {
            assertEquals(test, 2, owner.getInventory().count(Items.IRON_PICKAXE), "Stock and crafted pickaxe must both arrive");
            if (askedAgain.compareAndSet(false, true)) brain.handleChat(owner, gf, "准备出发了吗");
            test.assertTrue(history.get() != null, "Wait for the next conversation turn");
            brain.shutdown();
            assertEquals(test, 2, counter.get(), "A successful order must not ask the model for a second narration");
            String itemName = Items.IRON_PICKAXE.getName().getString();
            test.assertTrue(history.get().contains("给你 1 个" + itemName) && history.get().contains("另外 1 个" + itemName),
                "History must distinguish the immediate handoff from the one still to make");
            test.assertTrue(!history.get().contains("还要烧矿石"), "A fabricated operation must not enter conversation history");
            gf.discard();
        });
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void lateSummaryDoesNotFollowOwnerIntoNextSave(TestContext test) throws Exception {
        Path root = Files.createTempDirectory("denia-chat-worlds");
        Path first = root.resolve("first");
        Path second = root.resolve("second");
        var server = test.getWorld().getServer();
        var oldBrain = new ChatBrain(server);
        var freshBrain = new ChatBrain(server);
        var owner = test.createMockCreativeServerPlayerInWorld();
        UUID id = owner.getUuid();
        var gf = test.spawnEntity(GirlfriendEntities.GIRLFRIEND, 2, 2, 2);
        gf.setOwnerUuid(id);
        CompletableFuture<Void> resume = new CompletableFuture<>();
        try {
            MemoryStore.init(first);
            oldBrain.persistSummary(id, "first world");
            var late = resume.thenRunAsync(() -> oldBrain.persistSummary(id, "late reply from first world"));
            oldBrain.shutdown();
            MemoryStore.init(second);
            test.assertTrue(MemoryStore.getMemory(id).isEmpty(), "New save must start from its own conversation");
            freshBrain.persistSummary(id, "second world");
            resume.complete(null);
            late.get(5, TimeUnit.SECONDS);
            test.assertTrue(!oldBrain.proactive(gf, "过期发言"), "Stopped brain must never restart its executor");
            test.assertTrue("second world".equals(MemoryStore.getMemory(id)), "Late summary overwrote the new save");
            MemoryStore.init(first);
            test.assertTrue("first world".equals(MemoryStore.getMemory(id)), "Original world's summary changed");
            MemoryStore.init(second);
            test.assertTrue("second world".equals(MemoryStore.getMemory(id)), "Disk summaries were mixed");
        } finally {
            resume.complete(null);
            oldBrain.shutdown();
            freshBrain.shutdown();
            gf.discard();
            MemoryStore.init(server);
            for (Path world : new Path[]{first, second}) {
                Files.deleteIfExists(world.resolve("mcgf-memory").resolve(id + ".json"));
                Files.deleteIfExists(world.resolve("mcgf-memory"));
                Files.deleteIfExists(world);
            }
            Files.deleteIfExists(root);
        }
        test.complete();
    }
}
