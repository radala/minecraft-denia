package xyz.apollodorus.mcgf.net;

import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/** The requesting player's own journal, rendered using their client's font metrics. */
public record JournalPayload(String text) implements CustomPayload {
    public static final Id<JournalPayload> ID = new Id<>(Identifier.of(MCGirlfriendMod.MOD_ID, "travel_journal"));
    public static final PacketCodec<RegistryByteBuf, JournalPayload> CODEC =
            PacketCodec.tuple(PacketCodecs.STRING, JournalPayload::text, JournalPayload::new);

    @Override
    public Id<JournalPayload> getId() { return ID; }
}
