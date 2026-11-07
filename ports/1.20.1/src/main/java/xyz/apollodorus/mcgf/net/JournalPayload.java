package xyz.apollodorus.mcgf.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/** The requesting player's own journal, rendered using their client's font metrics. */
public record JournalPayload(String text) {
    public static final Identifier ID = new Identifier(MCGirlfriendMod.MOD_ID, "travel_journal");

    public static JournalPayload read(PacketByteBuf buf) {
        return new JournalPayload(buf.readString());
    }

    public void write(PacketByteBuf buf) {
        buf.writeString(text);
    }

}
