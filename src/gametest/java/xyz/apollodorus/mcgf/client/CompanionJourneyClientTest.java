package xyz.apollodorus.mcgf.client;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.AbstractCandleBlock;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Items;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;
import xyz.apollodorus.mcgf.client.screen.CompanionScreen;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;

/** A rendered, single-player journey. Admin commands prepare the clearing and starting supplies. */
public final class CompanionJourneyClientTest implements FabricClientGameTest {
    private static final String COMPANION = "@e[type=mcgf:girlfriend,limit=1,sort=nearest]";

    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1100, 740);
        CompletableFuture<Void> language = context.computeOnClient(mc -> {
            mc.getLanguageManager().setLanguage("zh_cn");
            mc.options.language = "zh_cn";
            return mc.reloadResources();
        });
        context.waitFor(mc -> language.isDone(), 1200);
        language.join();
        AtomicBoolean heardRevisit = new AtomicBoolean();
        ClientReceiveMessageEvents.GAME.register((line, overlay) -> {
            if (line.getString().contains("你点了蜡烛，让我许个愿")) heardRevisit.set(true);
        });
        TestWorldSave save;
        BlockPos center;
        // Coastal grassland near the original spawn. A fixed seed keeps the walking route reproducible.
        try (var game = context.worldBuilder().setUseConsistentSettings(false)
                .adjustSettings(settings -> settings.setSeed("5837041628170146698")).create()) {
            var server = game.getServer();
            save = game.getWorldSave();
            center = server.computeOnServer(s -> new BlockPos(-422,
                s.getOverworld().getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, -422, -118), -118));
            server.runCommand("difficulty normal");
            server.runCommand("gamemode survival @p");
            server.runCommand("time set day");
            server.runCommand("weather clear");
            server.runCommand("fill " + pos(center.add(-4, -1, -4)) + " " + pos(center.add(4, -1, 7)) + " grass_block");
            server.runCommand("fill " + pos(center.add(-4, 0, -4)) + " " + pos(center.add(4, 4, 7)) + " air");
            server.runCommand("fill " + pos(center.add(-1, -1, 8)) + " " + pos(center.add(1, -1, 34)) + " grass_block");
            server.runCommand("fill " + pos(center.add(-1, 0, 8)) + " " + pos(center.add(1, 2, 34)) + " air");
            server.runCommand("tp @p " + feet(center));
            // The item quantities stand in for supplies collected before this outing.
            server.runCommand("item replace entity @p hotbar.8 with oak_planks 55");
            server.runCommand("item replace entity @p hotbar.7 with iron_ingot 3");
            server.runCommand("item replace entity @p hotbar.6 with stick 2");
            BlockPos cake = center.add(3, 0, 1);
            server.runCommand("setblock " + pos(cake) + " candle_cake[lit=true]");
            game.getClientWorld().waitForChunksRender();

            chat(context, "/denia summon");
            context.waitFor(mc -> mc.world.getEntitiesByClass(GirlfriendEntity.class,
                mc.player.getBoundingBox().expand(8), e -> true).size() == 1);
            chat(context, "/denia stop");
            faceCompanion(context, server, center);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            context.waitForScreen(CompanionScreen.class);
            screenshot(context, "01-companion-backpack");
            moveHotbarToBag(context, 8, 1);
            moveHotbarToBag(context, 7, 2);
            moveHotbarToBag(context, 6, 3);
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitForScreen(null);

            context.getInput().pressKey(GLFW.GLFW_KEY_4);
            context.waitFor(mc -> mc.player.getMainHandStack().isEmpty() && mc.player.getOffHandStack().isEmpty());
            faceCompanion(context, server, center);
            context.getInput().holdKey(GLFW.GLFW_KEY_LEFT_SHIFT);
            try {
                context.waitFor(mc -> mc.player.isSneaking());
                context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
                context.waitFor(mc -> mc.world.getBlockState(cake).isOf(Blocks.CANDLE_CAKE)
                    && !mc.world.getBlockState(cake).get(AbstractCandleBlock.LIT));
            } finally {
                context.getInput().releaseKey(GLFW.GLFW_KEY_LEFT_SHIFT);
            }
            faceCompanion(context, server, center);
            context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT);
            context.waitForScreen(CompanionScreen.class);
            clickJournal(context);
            context.waitForScreen(BookScreen.class);
            screenshot(context, "02-birthday-journal");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitForScreen(null);

            chat(context, "/denia obtain minecraft:iron_pickaxe 1");
            context.waitFor(mc -> mc.player.getInventory().count(Items.IRON_PICKAXE) == 1, 500);
            screenshot(context, "03-pickaxe-delivered");
            server.runCommand("tp " + COMPANION + " " + feet(center));
            server.runCommand("tp @p " + feet(center.south(6)) + " 180 0");
            context.waitTicks(4);
            chat(context, "/denia camp");
            context.waitFor(mc -> mc.world.getBlockState(center.up(2)).isOf(Blocks.OAK_PLANKS)
                && mc.world.getBlockState(center.south(2)).isAir()
                && mc.world.getBlockState(center.south(2).east()).isOf(Blocks.OAK_PLANKS));
            screenshot(context, "04-camp-doorway");
            // She stays at camp. Walk out of recognition range, advance the world a day, then save and quit.
            server.runCommand("tp @p " + feet(center.south(6)) + " 0 0");
            context.waitTicks(4);
            context.getInput().holdKey(GLFW.GLFW_KEY_W);
            try {
                context.waitFor(mc -> mc.player.getZ() >= center.getZ() + 30, 300);
            } finally {
                context.getInput().releaseKey(GLFW.GLFW_KEY_W);
            }
            context.waitTicks(30);
            server.runCommand("time add 1d");
        }
        try (var reopened = save.open()) {
            reopened.getClientWorld().waitForChunksRender();
            reopened.getServer().runCommand("execute as @p at @s run tp @s ~ ~ ~ 180 0");
            context.waitTicks(4);
            context.getInput().holdKey(GLFW.GLFW_KEY_W);
            try {
                context.waitFor(mc -> mc.player.getZ() < center.getZ() + 6.5, 300);
            } finally {
                context.getInput().releaseKey(GLFW.GLFW_KEY_W);
            }
            context.waitFor(mc -> heardRevisit.get(), 2600);
            screenshot(context, "05-return-to-camp");
            chat(context, "/denia journal");
            context.waitForScreen(BookScreen.class);
            screenshot(context, "06-journal-after-revisit");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitForScreen(null);
        }
    }

    private static void faceCompanion(ClientGameTestContext context, TestServerContext server, BlockPos center) {
        server.runCommand("tp " + COMPANION + " " + feet(center));
        // Stand south and look north at her torso from the player's actual eye height.
        server.runCommand("tp @p " + feet(center.south(2)) + " 180 12");
        context.waitTicks(4);
        context.waitFor(mc -> mc.crosshairTarget instanceof EntityHitResult hit && hit.getEntity() instanceof GirlfriendEntity);
    }

    private static void chat(ClientGameTestContext context, String command) {
        context.getInput().pressKey(GLFW.GLFW_KEY_T);
        context.waitForScreen(ChatScreen.class);
        context.getInput().typeChars(command);
        context.getInput().pressKey(GLFW.GLFW_KEY_ENTER);
        context.waitFor(mc -> !(mc.currentScreen instanceof ChatScreen));
        context.waitTick();
    }

    private static void screenshot(ClientGameTestContext context, String name) {
        // The starter supplies unlock recipes; let the journal text and HUD remain visible in the evidence.
        context.runOnClient(mc -> mc.getToastManager().clear());
        context.takeScreenshot(name);
    }

    private static void moveHotbarToBag(ClientGameTestContext context, int hotbarIndex, int bagColumn) {
        int[] origin = context.computeOnClient(mc -> new int[]{(mc.currentScreen.width - 176) / 2, (mc.currentScreen.height - 230) / 2});
        click(context, origin[0] + 16 + hotbarIndex * 18, origin[1] + 192);
        click(context, origin[0] + 16 + bagColumn * 18, origin[1] + 72);
    }

    private static void clickJournal(ClientGameTestContext context) {
        int[] center = context.computeOnClient(mc -> {
            for (var child : mc.currentScreen.children()) {
                if (child instanceof ButtonWidget button && button.getMessage().getString().equals("旅行手记")) {
                    return new int[]{button.getX() + button.getWidth() / 2, button.getY() + button.getHeight() / 2};
                }
            }
            throw new AssertionError("Travel journal button missing from the companion's backpack");
        });
        click(context, center[0], center[1]);
    }

    private static void click(ClientGameTestContext context, int x, int y) {
        double[] pixels = context.computeOnClient(mc -> new double[]{
            x * (double) mc.getWindow().getWidth() / mc.currentScreen.width,
            y * (double) mc.getWindow().getHeight() / mc.currentScreen.height});
        context.getInput().setCursorPos(pixels[0], pixels[1]);
        context.getInput().pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
        context.waitTick();
    }

    private static String pos(BlockPos pos) {
        return pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    private static String feet(BlockPos pos) {
        return String.format(Locale.ROOT, "%.1f %d %.1f", pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
    }
}
