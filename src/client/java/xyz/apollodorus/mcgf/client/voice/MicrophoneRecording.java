package xyz.apollodorus.mcgf.client.voice;

import javax.sound.sampled.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;

/** A single push-to-talk recording, held in memory only. */
final class MicrophoneRecording {
    private static final int SECONDS = 15;
    private static final AudioFormat WHISPER_PCM = new AudioFormat(16000, 16, 1, true, false);
    private static final double FULL_SCALE = 32768.0;
    private static final double MIN_WINDOW_RMS = 65; // About -54 dBFS for 16-bit PCM.
    volatile boolean released;
    volatile boolean cancelled;
    volatile boolean started;
    volatile boolean quiet;
    volatile double levelDbfs = -100;
    volatile long durationMs;
    volatile String deviceName = "";

    byte[] capture(String inputDevice) throws LineUnavailableException, IOException, InterruptedException {
        if (released || cancelled) return new byte[0];
        try (TargetDataLine mic = openMicrophone(inputDevice)) {
            if (released || cancelled) return new byte[0];
            mic.start();
            started = true;
            AudioFormat source = mic.getFormat();
            int bytesPerSecond = (int) source.getSampleRate() * source.getFrameSize();
            int limit = bytesPerSecond * SECONDS;
            ByteArrayOutputStream pcm = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            long start = System.nanoTime();
            long nextMeterUpdate = start;
            while (!cancelled && pcm.size() < limit
                    && System.nanoTime() - start < Duration.ofSeconds(SECONDS).toNanos()) {
                // On key-up drain only the bytes already waiting in the device.
                boolean ending = released;
                int pending = Math.min(mic.available(), limit - pcm.size());
                pending -= pending % source.getFrameSize();
                while (pending > 0) {
                    int n = mic.read(buffer, 0, Math.min(buffer.length, pending));
                    if (n <= 0) break;
                    pcm.write(buffer, 0, n);
                    durationMs = 1000L * pcm.size() / bytesPerSecond;
                    long now = System.nanoTime();
                    if (now >= nextMeterUpdate) {
                        levelDbfs = decibels(rms(buffer, 0, n));
                        nextMeterUpdate = now + Duration.ofMillis(70).toNanos();
                    }
                    pending -= n;
                }
                if (ending) break;
                Thread.sleep(8);
            }
            mic.stop();
            started = false;
            if (cancelled || pcm.size() < bytesPerSecond / 4) return new byte[0];
            return prepareWav(pcm.toByteArray(), source);
        } finally {
            started = false;
        }
    }

    byte[] capture() throws LineUnavailableException, IOException, InterruptedException {
        return capture("");
    }

    private TargetDataLine openMicrophone(String inputDevice) throws LineUnavailableException {
        Mixer.Info selected = null;
        if (inputDevice != null && !inputDevice.isBlank()) {
            for (Mixer.Info mixer : AudioSystem.getMixerInfo()) {
                if (mixer.getName().equalsIgnoreCase(inputDevice.strip())) {
                    selected = mixer;
                    break;
                }
            }
            if (selected == null) throw new LineUnavailableException("Configured input device is unavailable");
        }
        for (int rate : new int[] { 16000, 48000, 44100 }) {
            for (int channels : new int[] { 1, 2 }) {
                AudioFormat format = new AudioFormat(rate, 16, channels, true, false);
                TargetDataLine line = null;
                try {
                    line = selected == null ? AudioSystem.getTargetDataLine(format)
                            : AudioSystem.getTargetDataLine(format, selected);
                    line.open(format);
                    deviceName = selected != null ? selected.getName() : defaultDeviceName(line);
                    return line;
                } catch (IllegalArgumentException | LineUnavailableException unavailable) {
                    if (line != null) line.close();
                }
            }
        }
        throw new LineUnavailableException("No supported microphone capture format available");
    }

    private static String defaultDeviceName(TargetDataLine line) {
        for (Mixer.Info info : AudioSystem.getMixerInfo()) {
            if (Arrays.asList(AudioSystem.getMixer(info).getTargetLines()).contains(line)) return info.getName();
        }
        return "System default";
    }

    /** Reject key clicks and near-silence before sending to Whisper. Quiet speech can still be amplified. */
    byte[] prepareWav(byte[] pcm, AudioFormat source) throws IOException {
        byte[] samples = monoSamples(pcm, source);
        int windowBytes = 320 * 2; // 20 ms at 16 kHz.
        double[] levels = new double[samples.length / windowBytes];
        int voicedWindows = 0;
        for (int i = 0; i < levels.length; i++) {
            levels[i] = rms(samples, i * windowBytes, windowBytes);
            if (levels[i] >= MIN_WINDOW_RMS) voicedWindows++;
        }
        Arrays.sort(levels);
        double strongest = levels.length == 0 ? 0 : levels[levels.length - 1];
        levelDbfs = decibels(strongest);
        if (voicedWindows < 6) {
            quiet = true;
            return new byte[0];
        }
        // A steady low noise floor also appears on some idle inputs.
        double upper = levels[levels.length * 9 / 10];
        double lower = levels[levels.length / 10];
        if (strongest < 450 && upper < 1.5 * lower) {
            quiet = true;
            return new byte[0];
        }
        // Estimate speech level from active windows; long pauses should not increase the gain.
        double typical = levels[levels.length - 1 - voicedWindows / 5];
        double gain = Math.min(8.0, 2200.0 / typical);
        if (gain > 1.0) {
            for (int i = 0; i < samples.length; i += 2) {
                int boosted = (int) Math.round(sample(samples, i) * gain);
                int clipped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, boosted));
                samples[i] = (byte) clipped;
                samples[i + 1] = (byte) (clipped >> 8);
            }
        }
        return toWav(samples, WHISPER_PCM);
    }

    private static double rms(byte[] bytes, int start, int length) {
        double sum = 0;
        double squares = 0;
        int count = length / 2;
        for (int i = start; i < start + count * 2; i += 2) {
            int value = sample(bytes, i);
            sum += value;
            squares += (double) value * value;
        }
        double mean = sum / count;
        return Math.sqrt(Math.max(0, squares / count - mean * mean));
    }

    private static int sample(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] << 8);
    }

    private static double decibels(double rms) {
        return Math.max(-100, 20 * Math.log10(rms / FULL_SCALE));
    }

    private static byte[] monoSamples(byte[] pcm, AudioFormat source) throws IOException {
        if (WHISPER_PCM.matches(source)) return pcm;
        try (AudioInputStream input = new AudioInputStream(new ByteArrayInputStream(pcm),
                source, pcm.length / source.getFrameSize());
             AudioInputStream mono = AudioSystem.getAudioInputStream(WHISPER_PCM, input)) {
            return mono.readAllBytes();
        }
    }

    static byte[] toWav(byte[] pcm, AudioFormat source) throws IOException {
        // Resamplers can report an unknown frame count; the WAV header needs the actual length.
        byte[] samples = monoSamples(pcm, source);
        try (AudioInputStream frames = new AudioInputStream(new ByteArrayInputStream(samples),
                WHISPER_PCM, samples.length / WHISPER_PCM.getFrameSize())) {
            ByteArrayOutputStream wav = new ByteArrayOutputStream();
            AudioSystem.write(frames, AudioFileFormat.Type.WAVE, wav);
            return wav.toByteArray();
        }
    }
}
