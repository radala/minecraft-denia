package xyz.apollodorus.mcgf.screen;

import net.minecraft.network.PacketByteBuf;

/**
 * Opening payload for the companion panel: enough for the client to render the
 * affection bar / intimacy tier / current activity without looking the entity up.
 * Sent by Fabric's {@link net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType}
 * when the owner right-clicks her.
 */
public record CompanionScreenData(int entityId, int affection, String name, String activity, String tasksData) {
    public static CompanionScreenData read(PacketByteBuf buf) {
        return new CompanionScreenData(buf.readVarInt(), buf.readVarInt(), buf.readString(), buf.readString(), buf.readString());
    }

    public void write(PacketByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeVarInt(affection);
        buf.writeString(name);
        buf.writeString(activity);
        buf.writeString(tasksData);
    }

}
