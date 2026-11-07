package xyz.apollodorus.mcgf.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Server -> client: play a bundled WAV clip ({@code assets/mcgf/sounds/<clip>.wav}) positioned at
 * {@code (x,y,z)}, attenuated (and panned) by the listener's distance/angle on the client.
 *
 * <p>Used for 达妮娅's custom 形态一 combo-hit sounds. The vanilla sound engine only plays OGG, but her
 * clips are WAV — so, like the bundled voice clips, they go through the mod's own client audio path
 * ({@link xyz.apollodorus.mcgf.client.tts.AudioPlayback}). The position lets hits 3a/4a sound like they
 * come from the target rather than from her.
 */
public record ClipSoundPayload(String clip, double x, double y, double z, float volume) {
    public static final Identifier ID = new Identifier(MCGirlfriendMod.MOD_ID, "clip_sound");

    public static ClipSoundPayload read(PacketByteBuf buf) {
        return new ClipSoundPayload(buf.readString(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat());
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(clip);
        buf.writeDouble(x);
        buf.writeDouble(y);
        buf.writeDouble(z);
        buf.writeFloat(volume);
    }

}
