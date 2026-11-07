package xyz.apollodorus.mcgf.verify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.SharedConstants;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.BookScreen;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.resource.DataConfiguration;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Difficulty;
import net.minecraft.world.GameMode;
import net.minecraft.world.GameRules;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.level.LevelInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.client.screen.CompanionScreen;
import xyz.apollodorus.mcgf.client.voice.LoopbackVoiceProbe;
import xyz.apollodorus.mcgf.entity.GirlfriendEntity;
import xyz.apollodorus.mcgf.screen.CompanionScreenHandler;

/** Test-mod only. Runs a rendered single-player session via vanilla's client and integrated server. */
public final class PortClientProbe implements ClientModInitializer {
    private static final Logger LOG = LoggerFactory.getLogger("denia/client-probe");
    private enum Stage { START, WORLD, SUMMONED, STAGED, BACKPACK, TRANSFER, JOURNAL, DOMAIN, DONE }
    private Stage stage = Stage.START;
    private long since = System.nanoTime();
    private int ticks;
    private int companionId;
    private CompletableFuture<Void> prepared;
    private CompletableFuture<Void> voice;
    private Path evidence;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (stage == Stage.DONE) return;
            try {
                if (TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - since) > 180)
                    throw new IllegalStateException("Timed out at " + stage + ", screen " + client.currentScreen);
                tick(client);
            } catch (Exception ex) {
                LOG.error("Rendered client probe failed at {}", stage, ex);
                // A missing passed.txt also signals failure when the VM exits cleanly.
                if (evidence != null) {
                    try { Files.writeString(evidence.resolve("failed.txt"), stage + ": " + ex); }
                    catch (IOException io) { LOG.error("Could not write the failure marker", io); }
                }
                stage = Stage.DONE;
                client.scheduleStop();
            }
        });
    }

    private void tick(MinecraftClient mc) throws IOException {
        ticks++;
        switch (stage) {
            case START -> {
                if (mc.currentScreen == null || mc.getOverlay() != null) return;
                evidence = Files.createDirectories(mc.runDirectory.toPath().resolve("probe-evidence"));
                Files.deleteIfExists(evidence.resolve("passed.txt"));
                Files.deleteIfExists(evidence.resolve("failed.txt"));
                Files.deleteIfExists(evidence.resolve("voice-passed.txt"));
                mc.options.pauseOnLostFocus = false;
                String name = "denia-probe-" + UUID.randomUUID();
                LevelInfo level = new LevelInfo(name, GameMode.SURVIVAL, false, Difficulty.PEACEFUL, true,
                        new GameRules(), DataConfiguration.SAFE_MODE);
                advance(Stage.WORLD);
                mc.createIntegratedServerLoader().createAndStart(name, level, new GeneratorOptions(24L, false, false),
                        registries -> registries.get(RegistryKeys.WORLD_PRESET).getOrThrow(WorldPresets.FLAT)
                                .createDimensionsRegistryHolder());
            }
            case WORLD -> {
                if (mc.player == null || mc.world == null || mc.getServer() == null || mc.currentScreen != null) return;
                if (voice == null) voice = LoopbackVoiceProbe.run();
                if (!voice.isDone()) return;
                voice.join();
                Files.writeString(evidence.resolve("voice-passed.txt"),
                        "Loopback ASR multipart WAV + Chinese draft, Breeze POST fields + PCM playback passed.\n");
                mc.getNetworkHandler().sendChatCommand("denia summon");
                advance(Stage.SUMMONED);
            }
            case SUMMONED -> {
                var companions = mc.world.getEntitiesByClass(GirlfriendEntity.class,
                        mc.player.getBoundingBox().expand(10), e -> true);
                if (companions.size() != 1) return;
                companionId = companions.get(0).getId();
                UUID playerId = mc.player.getUuid();
                IntegratedServer server = mc.getServer();
                prepared = server.submit(() -> {
                    ServerPlayerEntity owner = server.getPlayerManager().getPlayer(playerId);
                    GirlfriendEntity gf = (GirlfriendEntity) owner.getServerWorld().getEntityById(companionId);
                    if (gf == null || !gf.isOwner(owner)) throw new IllegalStateException("Summon did not bind owner");
                    gf.setAiDisabled(true);
                    gf.refreshPositionAndAngles(owner.getX(), owner.getY(), owner.getZ() + 2.7, 180, 0);
                    gf.setBodyYaw(180);
                    gf.setHeadYaw(180);
                    gf.getInventory().setStack(1, new ItemStack(Items.EMERALD, 3));
                    gf.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_PICKAXE));
                });
                mc.options.hudHidden = true;
                advance(Stage.STAGED);
            }
            case STAGED -> {
                if (!prepared.isDone()) return;
                prepared.join();
                GirlfriendEntity gf = companion(mc);
                lookAt(mc, gf);
                if (ticks < 24 || gf.isFormTwo() || !gf.getMainHandStack().isOf(Items.IRON_PICKAXE)
                        || mc.player.squaredDistanceTo(gf) > 12) return;
                capture(mc, "01-first-form-pickaxe");
                mc.options.hudHidden = false;
                mc.interactionManager.interactEntity(mc.player, gf, Hand.MAIN_HAND);
                advance(Stage.BACKPACK);
            }
            case BACKPACK -> {
                if (ticks < 12 || !(mc.currentScreen instanceof CompanionScreen)
                        || !(mc.player.currentScreenHandler instanceof CompanionScreenHandler handler)) return;
                // Six equipment slots, then backpack slot index 1.
                ItemStack sent = handler.getSlot(7).getStack();
                if (!sent.isOf(Items.EMERALD) || sent.getCount() != 3) return;
                capture(mc, "02-companion-backpack");
                mc.interactionManager.clickSlot(handler.syncId, 7, 0, SlotActionType.QUICK_MOVE, mc.player);
                advance(Stage.TRANSFER);
            }
            case TRANSFER -> {
                if (ticks < 12 || mc.player.getInventory().count(Items.EMERALD) != 3) return;
                UUID playerId = mc.player.getUuid();
                var server = mc.getServer();
                if (!server.submit(() -> {
                    var owner = server.getPlayerManager().getPlayer(playerId);
                    GirlfriendEntity gf = (GirlfriendEntity) owner.getServerWorld().getEntityById(companionId);
                    return owner.getInventory().count(Items.EMERALD) == 3 && gf.getInventory().getStack(1).isEmpty();
                }).join()) return;
                mc.player.closeHandledScreen();
                mc.getNetworkHandler().sendChatCommand("denia journal");
                advance(Stage.JOURNAL);
            }
            case JOURNAL -> {
                if (ticks < 12 || !(mc.currentScreen instanceof BookScreen)) return;
                capture(mc, "03-journal-payload");
                mc.currentScreen.close();
                mc.options.hudHidden = true;
                mc.getNetworkHandler().sendChatCommand("denia ult");
                advance(Stage.DOMAIN);
            }
            case DOMAIN -> {
                GirlfriendEntity gf = companion(mc);
                lookAt(mc, gf);
                if (ticks < 30 || !gf.isFormTwo()) return;
                capture(mc, "04-second-form");
                String version = SharedConstants.getGameVersion().getName();
                Files.writeString(evidence.resolve("passed.txt"), "Minecraft " + version
                        + "\nSummon/owner, tracked forms, held pickaxe, backpack transfer, journal payload and screenshots passed.\n");
                LOG.info("DENIA_CLIENT_PROBE_PASS {}", version);
                advance(Stage.DONE);
                mc.scheduleStop();
            }
            case DONE -> { }
        }
    }

    private GirlfriendEntity companion(MinecraftClient mc) {
        return (GirlfriendEntity) mc.world.getEntityById(companionId);
    }

    private static void lookAt(MinecraftClient mc, GirlfriendEntity gf) {
        Vec3d delta = gf.getPos().add(0, gf.getHeight() * .55, 0).subtract(mc.player.getEyePos());
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        mc.player.setPitch((float) -Math.toDegrees(Math.atan2(delta.y, delta.horizontalLength())));
    }

    private void capture(MinecraftClient mc, String name) throws IOException {
        try (NativeImage image = ScreenshotRecorder.takeScreenshot(mc.getFramebuffer())) {
            image.writeTo(evidence.resolve(name + ".png"));
        }
    }

    private void advance(Stage next) {
        LOG.info("Client probe {} -> {}", stage, next);
        stage = next;
        since = System.nanoTime();
        ticks = 0;
    }
}
