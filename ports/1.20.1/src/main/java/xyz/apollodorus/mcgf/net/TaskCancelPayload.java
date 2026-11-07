package xyz.apollodorus.mcgf.net;

import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;
import xyz.apollodorus.mcgf.MCGirlfriendMod;

/**
 * Client -> server: cancel the queued job at {@code index} of the girlfriend {@code entityId}
 * (the panel "取消" button). The entity id pins it to the companion whose panel was open, so it
 * works even with more than one companion around.
 */
public record TaskCancelPayload(int entityId, int index) {

    public static final Identifier ID = new Identifier(MCGirlfriendMod.MOD_ID, "task_cancel");

    public static TaskCancelPayload read(PacketByteBuf buf) {
        return new TaskCancelPayload(buf.readVarInt(), buf.readVarInt());
    }

    public void write(PacketByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(index);
    }

}
