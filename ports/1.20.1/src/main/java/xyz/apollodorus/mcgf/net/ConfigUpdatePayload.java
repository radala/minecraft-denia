package xyz.apollodorus.mcgf.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Client -> server: the LLM + TTS connection settings and behavior toggles edited in the in-game config
 * panel, so players never have to hand-edit config/mcgf.json. The server applies these to the live
 * {@link xyz.apollodorus.mcgf.config.GirlfriendConfig} and persists the file (see {@link MCGirlfriendMod}).
 *
 * <p>The legacy channel uses the same six strings and packed behavior flags as the newer payload.
 */
public record ConfigUpdatePayload(
        String llmBaseURL, String llmApiKey, String llmModel,
        boolean ttsEnabled, String ttsUrl, String ttsRefAudioPath, String ttsPromptText,
        boolean autoGatherWood, boolean autoPickup, boolean autoGatherCrops,
        boolean autoStorage, boolean autoLight, boolean autoFish, boolean autoFarm
) {

    public static final Identifier ID = new Identifier(MCGirlfriendMod.MOD_ID, "config_update");

    public void write(PacketByteBuf buf) {
        buf.writeString(llmBaseURL);
        buf.writeString(llmApiKey);
        buf.writeString(llmModel);
        buf.writeString(ttsUrl);
        buf.writeString(ttsRefAudioPath);
        buf.writeString(ttsPromptText);
        buf.writeVarInt(flags());
    }

    public static ConfigUpdatePayload read(PacketByteBuf buf) {
        return fromWire(buf.readString(), buf.readString(), buf.readString(),
            buf.readString(), buf.readString(), buf.readString(), buf.readVarInt());
    }

    /** Pack the eight booleans into a bitmask for the wire. */
    private int flags() {
        return (ttsEnabled ? 1 : 0)
            | (autoGatherWood ? 2 : 0)
            | (autoPickup ? 4 : 0)
            | (autoGatherCrops ? 8 : 0)
            | (autoStorage ? 16 : 0)
            | (autoLight ? 32 : 0)
            | (autoFish ? 64 : 0)
            | (autoFarm ? 128 : 0);
    }

    /** Rebuild from the six strings + the packed bitmask. */
    private static ConfigUpdatePayload fromWire(String llmBaseURL, String llmApiKey, String llmModel,
                                                String ttsUrl, String ttsRefAudioPath, String ttsPromptText,
                                                int flags) {
        return new ConfigUpdatePayload(
            llmBaseURL, llmApiKey, llmModel,
            (flags & 1) != 0, ttsUrl, ttsRefAudioPath, ttsPromptText,
            (flags & 2) != 0, (flags & 4) != 0, (flags & 8) != 0,
            (flags & 16) != 0, (flags & 32) != 0, (flags & 64) != 0, (flags & 128) != 0);
    }

}
