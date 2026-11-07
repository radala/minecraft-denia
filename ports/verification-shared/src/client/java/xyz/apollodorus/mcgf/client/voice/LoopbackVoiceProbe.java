package xyz.apollodorus.mcgf.client.voice;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import xyz.apollodorus.mcgf.client.tts.TtsClient;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;

/** Exercises the actual HTTP clients with generated PCM and a loopback-only service in the test JVM. */
public final class LoopbackVoiceProbe {
    private static final String TRANSCRIPT = "帮我做一把铁镐交给我";
    private static final String ANSWER = "铁镐做好了，给你。";

    private LoopbackVoiceProbe() { }

    public static CompletableFuture<Void> run() {
        return CompletableFuture.runAsync(() -> {
            try { verify(); }
            catch (Exception failure) { throw new CompletionException(failure); }
        });
    }

    private static void verify() throws Exception {
        byte[] audio = wav();
        String token = "fake-denia-probe-" + UUID.randomUUID();
        var speechRequest = new CompletableFuture<Void>();
        var played = new CompletableFuture<Void>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/inference", exchange -> {
            try {
                require("POST".equals(exchange.getRequestMethod()), "ASR verb");
                require(exchange.getRequestHeaders().getFirst("Authorization") == null, "ASR sent unexpected credentials");
                String type = exchange.getRequestHeaders().getFirst("Content-Type");
                require(type != null && type.startsWith("multipart/form-data; boundary="), "ASR content type");
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
                require(body.contains("name=\"language\"\r\n\r\nzh\r\n") && body.contains("filename=\"speech.wav\"")
                        && body.contains(new String(audio, StandardCharsets.ISO_8859_1)), "ASR language or WAV body");
                send(exchange, 200, "application/json", ("{\"text\":\"" + TRANSCRIPT + "\"}").getBytes(StandardCharsets.UTF_8));
            } catch (Exception ex) {
                send(exchange, 400, "text/plain", ex.getMessage().getBytes(StandardCharsets.UTF_8));
            }
        });
        server.createContext("/v1/audio/speech", exchange -> {
            try {
                require("POST".equals(exchange.getRequestMethod()), "Breeze verb");
                require(("Bearer " + token).equals(exchange.getRequestHeaders().getFirst("Authorization")), "Breeze fake token");
                require("application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type")), "Breeze content type");
                var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                require(ANSWER.equals(body.get("input").getAsString()) && "breeze-tts-2".equals(body.get("model").getAsString())
                        && "达妮娅".equals(body.get("voice").getAsString()) && "wav".equals(body.get("response_format").getAsString())
                        && body.get("speed").getAsDouble() == 1.0, "Breeze JSON fields");
                send(exchange, 200, "audio/wav", audio);
                speechRequest.complete(null);
            } catch (Exception ex) {
                speechRequest.completeExceptionally(ex);
                send(exchange, 400, "text/plain", new byte[0]);
            }
        });
        AbstractAppender capture = new AbstractAppender("denia-test-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) {
                String message = event.getMessage().getFormattedMessage();
                if (message.contains("TTS audio playback completed")) played.complete(null);
                else if (message.contains("audio playback failed")) played.completeExceptionally(new IOException("Java Sound playback failed"));
            }
        };
        Logger ttsLog = (Logger) LogManager.getLogger("mcgf/tts");
        Logger audioLog = (Logger) LogManager.getLogger("mcgf/audio");
        try {
            capture.start();
            ttsLog.addAppender(capture);
            audioLog.addAppender(capture);
            server.start();
            String result = LocalTranscription.transcribe(new LocalTranscription.Settings(base + "/inference", "whisper-1", "zh"), audio);
            require(TRANSCRIPT.equals(result), "ASR returned unexpected Chinese text");
            var config = ConfigManager.get();
            var previousTts = config.tts;
            var previousLlm = config.llm;
            try {
                // TtsClient builds its complete immutable HttpRequest synchronously. Restore both
                // settings immediately; nothing is written to disk or to an external service.
                var llm = new GirlfriendConfig.Llm();
                llm.baseURL = base + "/v0";
                llm.apiKey = token;
                var tts = new GirlfriendConfig.Tts();
                tts.enabled = true;
                tts.url = base + "/v1/audio/speech";
                config.llm = llm;
                config.tts = tts;
                TtsClient.speak(ANSWER);
            } finally {
                config.tts = previousTts;
                config.llm = previousLlm;
            }
            speechRequest.get(10, TimeUnit.SECONDS);
            played.get(10, TimeUnit.SECONDS);
        } finally {
            ttsLog.removeAppender(capture);
            audioLog.removeAppender(capture);
            capture.stop();
            server.stop(0);
        }
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static void require(boolean condition, String message) throws IOException {
        if (!condition) throw new IOException(message);
    }

    private static byte[] wav() {
        int samples = 1600;
        var out = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46464952).putInt(36 + samples * 2).putInt(0x45564157).putInt(0x20746d66);
        out.putInt(16).putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        out.putShort((short) 2).putShort((short) 16).putInt(0x61746164).putInt(samples * 2);
        for (int i = 0; i < samples; i++) out.putShort((short) (128 * Math.sin(i * 2 * Math.PI * 330 / 16000)));
        return out.array();
    }
}
