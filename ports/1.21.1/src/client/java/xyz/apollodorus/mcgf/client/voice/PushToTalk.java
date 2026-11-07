package xyz.apollodorus.mcgf.client.voice;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;

/** Dictates into vanilla chat. Sending the draft still requires the player's normal Enter key. */
public final class PushToTalk {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/dictation");
    private final KeyBinding key = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.mcgf.push_to_talk", GLFW.GLFW_KEY_V, "key.categories.multiplayer"));
    private boolean wasDown;
    private MicrophoneRecording current;
    private ClientWorld world;
    private long nextMeterUpdate;

    public static void register() {
        PushToTalk input = new PushToTalk();
        ClientTickEvents.END_CLIENT_TICK.register(input::tick);
    }

    private void tick(MinecraftClient client) {
        boolean down = key.isPressed();
        boolean canRecord = client.world != null && client.player != null && client.player.isAlive()
                && client.currentScreen == null && client.isWindowFocused();
        if (current != null) {
            if (world != client.world || (!current.released && !canRecord)) {
                current.cancelled = true;
                current = null;
            } else if (!down) {
                current.released = true;
            } else if (!current.released && current.started && System.nanoTime() >= nextMeterUpdate) {
                nextMeterUpdate = System.nanoTime() + 150_000_000L;
                int bars = Math.max(0, Math.min(6, (int) Math.ceil((current.levelDbfs + 60) / 10)));
                client.player.sendMessage(Text.translatable("mcgf.voice.level",
                        "|".repeat(bars) + "·".repeat(6 - bars)), true);
            }
        }
        if (down && !wasDown && canRecord && current == null) {
            if (client.getChatRestriction().allowsChat(client.isInSingleplayer())) start(client);
            else status(client, "chat_disabled");
        }
        wasDown = down;
    }

    private void start(MinecraftClient client) {
        MicrophoneRecording recording = new MicrophoneRecording();
        current = recording;
        world = client.world;
        nextMeterUpdate = 0;
        status(client, "opening");
        Thread worker = new Thread(() -> {
            LocalTranscription.Settings cfg;
            try {
                cfg = LocalTranscription.settings(FabricLoader.getInstance().getConfigDir().resolve("mcgf-voice-input.json"));
            } catch (RuntimeException | IOException | LinkageError invalid) {
                finish(client, recording, "settings", null);
                return;
            }
            try {
                byte[] wav = recording.capture(cfg.inputDevice());
                if (recording.cancelled) return;
                LOGGER.info("[mcgf] Input {}: {} ms, peak frame RMS {} dBFS", recording.deviceName,
                        recording.durationMs, Math.round(recording.levelDbfs));
                if (wav.length == 0) {
                    finish(client, recording, recording.quiet ? "quiet" : "too_short", null);
                    return;
                }
                if (!recording.released) {
                    client.execute(() -> { if (current == recording) status(client, "limit"); });
                    while (!recording.released && !recording.cancelled) Thread.sleep(15);
                }
                if (recording.cancelled) return;
                client.execute(() -> { if (current == recording) status(client, "transcribing"); });
                String text;
                try {
                    text = LocalTranscription.transcribe(cfg, wav);
                } catch (IOException offline) {
                    finish(client, recording, "service", null);
                    return;
                }
                LOGGER.info("[mcgf] Dictation {}", text.isEmpty() ? "no speech" : "draft ready");
                finish(client, recording, text.isEmpty() ? "empty" : null, text);
            } catch (LineUnavailableException | RuntimeException | IOException | LinkageError noMic) {
                finish(client, recording, "microphone", null);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                finish(client, recording, "cancelled", null);
            }
        }, "mcgf-dictation");
        worker.setDaemon(true);
        worker.start();
    }

    private void finish(MinecraftClient client, MicrophoneRecording recording, String error, String text) {
        client.execute(() -> {
            if (current != recording) return;
            current = null;
            if (recording.cancelled || client.player == null || client.world != world) return;
            if (error != null) {
                status(client, error);
                return;
            }
            if (!client.getChatRestriction().allowsChat(client.isInSingleplayer())) return;
            if (client.currentScreen != null || !client.isWindowFocused() || !client.player.isAlive()) {
                suggest(client, text);
                return;
            }
            client.setScreen(new ChatScreen(text));
        });
    }

    private static void suggest(MinecraftClient client, String text) {
        Text suggestion = Text.translatable("mcgf.voice.suggestion", text)
                .styled(style -> style.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, text)));
        client.player.sendMessage(suggestion, false);
    }

    private static void status(MinecraftClient client, String key) {
        if (client.player != null) client.player.sendMessage(Text.translatable("mcgf.voice." + key), true);
    }
}
