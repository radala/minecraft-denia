package xyz.apollodorus.mcgf.client.voice;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/** Client-side speech recognition. No credentials or cloud endpoints are used. */
final class LocalTranscription {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY)
            .connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Settings DEFAULT = new Settings("http://127.0.0.1:18765/inference", "whisper-1", "zh");

    record Settings(String endpoint, String model, String language, String inputDevice) {
        Settings(String endpoint, String model, String language) {
            this(endpoint, model, language, "");
        }

        Settings {
            inputDevice = inputDevice == null ? "" : inputDevice.strip();
            if (endpoint == null || model == null || !model.matches("[a-zA-Z0-9/_.:-]{1,128}")
                    || language == null || !language.matches("[a-zA-Z-]{2,20}")) throw new IllegalArgumentException();
            URI uri = URI.create(endpoint);
            String host = uri.getHost();
            if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !List.of("/inference", "/v1/audio/transcriptions").contains(uri.getRawPath())
                    || uri.getPort() == 0 || uri.getPort() > 65535 || host == null) throw new IllegalArgumentException();
            // Normalize localhost to a numeric address; a local hosts/DNS override must not redirect the audio.
            if ("localhost".equalsIgnoreCase(host)) host = "127.0.0.1";
            if (!host.equals("127.0.0.1") && !host.equals("[::1]")) throw new IllegalArgumentException();
            endpoint = "http://" + host + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + uri.getRawPath();
        }
    }

    static Settings settings(Path file) throws IOException {
        if (Files.notExists(file)) {
            Files.createDirectories(file.getParent());
            try {
                Files.writeString(file, GSON.toJson(DEFAULT), StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ignored) {
                // Another client sharing this installation created its settings first.
            }
        }
        Settings settings = GSON.fromJson(Files.readString(file), Settings.class);
        if (settings == null) throw new IllegalArgumentException();
        return settings;
    }

    static String transcribe(Settings cfg, byte[] wav) throws IOException, InterruptedException {
        String boundary = "denia-" + UUID.randomUUID();
        StringBuilder body = new StringBuilder();
        if (cfg.endpoint.endsWith("/inference")) {
            // A button press is an independent utterance. Do not guess again at higher temperatures.
            field(body, boundary, "no_context", "true");
            field(body, boundary, "temperature", "0");
            field(body, boundary, "temperature_inc", "0");
            field(body, boundary, "no_language_probabilities", "true");
            field(body, boundary, "response_format", "verbose_json");
        } else {
            field(body, boundary, "model", cfg.model);
            field(body, boundary, "response_format", "json");
        }
        field(body, boundary, "language", cfg.language);
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"")
                .append("\r\nContent-Type: audio/wav\r\n\r\n");
        HttpRequest request = HttpRequest.newBuilder(URI.create(cfg.endpoint)).timeout(Duration.ofSeconds(90))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(body.toString()),
                        HttpRequest.BodyPublishers.ofByteArray(wav),
                        HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n"))).build();
        var reply = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (reply.statusCode() != 200) throw new IOException("HTTP " + reply.statusCode());
        return readTranscript(reply.body());
    }

    static String readTranscript(String body) throws IOException {
        try {
            JsonObject response = JsonParser.parseString(body).getAsJsonObject();
            var text = response.get("text");
            if (text == null || !text.isJsonPrimitive() || !text.getAsJsonPrimitive().isString()) {
                throw new IOException("Invalid transcription response");
            }
            if (response.has("segments")) {
                var segments = response.getAsJsonArray("segments");
                if (segments.isEmpty()) return "";
                for (JsonElement element : segments) {
                    var segment = element.getAsJsonObject();
                    // Keep a whole sentence or ask for a retry. Dropping a weak segment can change an order.
                    if ((segment.has("no_speech_prob") && segment.get("no_speech_prob").getAsDouble() >= 0.6)
                            || (segment.has("avg_logprob") && segment.get("avg_logprob").getAsDouble() < -0.70)) {
                        return "";
                    }
                }
            }
            String clean = text.getAsString().replaceAll("[\\p{Cntrl}\\s]+", " ").replace("§", "").strip();
            if (clean.equalsIgnoreCase("[BLANK_AUDIO]")) return "";
            // Vanilla treats a leading slash as a command; dictated text always starts as ordinary chat.
            if (clean.startsWith("/")) clean = "达妮娅，" + clean;
            if (clean.length() <= 256) return clean;
            int end = Character.isHighSurrogate(clean.charAt(255)) ? 255 : 256;
            return clean.substring(0, end);
        } catch (RuntimeException malformed) {
            throw new IOException("Invalid transcription response", malformed);
        }
    }

    private static void field(StringBuilder body, String boundary, String name, String value) {
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"")
                .append(name).append("\"\r\n\r\n").append(value).append("\r\n");
    }
}
