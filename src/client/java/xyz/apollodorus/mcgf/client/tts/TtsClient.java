package xyz.apollodorus.mcgf.client.tts;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import xyz.apollodorus.mcgf.config.ConfigManager;
import xyz.apollodorus.mcgf.config.GirlfriendConfig;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Synthesizes speech through a configured GPT-SoVITS or OpenAI Speech endpoint
 * and plays the returned WAV on the client.
 */
public final class TtsClient {
    private static final Logger LOGGER = LoggerFactory.getLogger("mcgf/tts");
    private static final String SPEECH_PATH = "/v1/audio/speech";
    // HTTP/1.1 for the same reason as LlmClient: avoid the HTTP/2 h2c upgrade against a
    // cleartext http:// endpoint (matches the python sample, which uses HTTP/1.1).
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    // Single worker so lines play one after another instead of overlapping.
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mcgf-tts");
        t.setDaemon(true);
        return t;
    });

    private TtsClient() {}

    public static void speak(String text) {
        GirlfriendConfig config = ConfigManager.get();
        GirlfriendConfig.Tts cfg = config.tts;
        if (!cfg.enabled || text == null || text.isBlank()) return;
        if (cfg.url == null || (!cfg.url.startsWith("http://") && !cfg.url.startsWith("https://"))) return;
        HttpRequest req;
        try {
            req = buildRequest(config, text);
        } catch (IllegalArgumentException e) {
            LOGGER.warn("[mcgf] Invalid TTS endpoint or request settings");
            return;
        }
        if (req == null) return;
        EXEC.submit(() -> {
            try {
                HttpResponse<byte[]> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() / 100 == 2) {
                    if (AudioPlayback.playWav(resp.body(), 1.0f, 0.0f)) {
                        LOGGER.info("[mcgf] TTS audio playback completed");
                    }
                } else {
                    LOGGER.warn("[mcgf] TTS HTTP {}", resp.statusCode());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.warn("[mcgf] TTS failed: {}", e.getClass().getSimpleName());
            }
        });
    }

    private static HttpRequest buildRequest(GirlfriendConfig config, String text) {
        GirlfriendConfig.Tts cfg = config.tts;
        URI endpoint = URI.create(cfg.url);
        HttpRequest.Builder request = HttpRequest.newBuilder()
            .timeout(Duration.ofSeconds(Math.max(5, cfg.requestTimeoutSeconds)));
        String path = endpoint.getRawPath();
        if (!SPEECH_PATH.equals(path) && !(SPEECH_PATH + "/").equals(path)) {
            return request.uri(URI.create(buildUrl(cfg, text))).GET().build();
        }

        GirlfriendConfig.Llm llm = config.llm;
        if (llm == null || llm.baseURL == null || llm.apiKey == null || llm.apiKey.isBlank()) {
            LOGGER.warn("[mcgf] Speech endpoint requires a configured LLM base URL and API key");
            return null;
        }
        URI llmEndpoint = URI.create(llm.baseURL);
        if (!sameOrigin(endpoint, llmEndpoint)) {
            LOGGER.warn("[mcgf] Speech endpoint must have the same origin as the configured LLM to use its API key");
            return null;
        }
        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.model);
        body.addProperty("input", text);
        body.addProperty("voice", cfg.voice);
        body.addProperty("speed", 1);
        body.addProperty("response_format", "wav");
        return request.uri(endpoint.resolve(SPEECH_PATH))
            .header("Authorization", "Bearer " + llm.apiKey.trim())
            .header("Content-Type", "application/json")
            .header("Accept", "audio/wav")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
            .build();
    }

    private static boolean sameOrigin(URI speech, URI llm) {
        return speech.getHost() != null && speech.getHost().equalsIgnoreCase(llm.getHost())
            && speech.getScheme().equalsIgnoreCase(llm.getScheme())
            && port(speech) == port(llm)
            && speech.getRawUserInfo() == null && llm.getRawUserInfo() == null;
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String buildUrl(GirlfriendConfig.Tts cfg, String text) {
        StringBuilder sb = new StringBuilder(cfg.url);
        sb.append(cfg.url.contains("?") ? '&' : '?');
        sb.append("text=").append(enc(text));
        sb.append("&text_lang=").append(enc(cfg.textLang));
        sb.append("&ref_audio_path=").append(enc(cfg.refAudioPath));
        sb.append("&prompt_text=").append(enc(cfg.promptText));
        sb.append("&prompt_lang=").append(enc(cfg.promptLang));
        if (cfg.extraParams != null) {
            for (Map.Entry<String, String> e : cfg.extraParams.entrySet()) {
                sb.append('&').append(enc(e.getKey())).append('=').append(enc(e.getValue()));
            }
        }
        return sb.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }
}
