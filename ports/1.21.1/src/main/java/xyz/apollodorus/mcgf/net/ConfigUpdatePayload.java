package xyz.apollodorus.mcgf.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Client -> server: the LLM + TTS connection settings and behavior toggles edited in the in-game config
 * panel, so players never have to hand-edit config/mcgf.json. The server applies these to the live
 * {@link xyz.apollodorus.mcgf.config.GirlfriendConfig} and persists the file (see {@link MCGirlfriendMod}).
 *
 * <p>1.21.1's tuple codecs stop at six fields. Use the original six-string and VarInt wire layout.
 */
public record ConfigUpdatePayload(
        String llmBaseURL, String llmApiKey, String llmModel,
        boolean ttsEnabled, String ttsUrl, String ttsRefAudioPath, String ttsPromptText,
        boolean autoGatherWood, boolean autoPickup, boolean autoGatherCrops,
        boolean autoStorage, boolean autoLight, boolean autoFish, boolean autoFarm
) implements CustomPayload {

    public static final CustomPayload.Id<ConfigUpdatePayload> ID =
        new CustomPayload.Id<>(Identifier.of(MCGirlfriendMod.MOD_ID, "config_update"));

    public static final PacketCodec<RegistryByteBuf, ConfigUpdatePayload> CODEC =
        PacketCodec.of(ConfigUpdatePayload::write, ConfigUpdatePayload::read);

    private void write(RegistryByteBuf buf) {
        buf.writeString(llmBaseURL);
        buf.writeString(llmApiKey);
        buf.writeString(llmModel);
        buf.writeString(ttsUrl);
        buf.writeString(ttsRefAudioPath);
        buf.writeString(ttsPromptText);
        buf.writeVarInt(flags());
    }

    private static ConfigUpdatePayload read(RegistryByteBuf buf) {
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

    @Override
    public CustomPayload.Id<ConfigUpdatePayload> getId() {
        return ID;
    }
}
