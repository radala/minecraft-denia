package xyz.apollodorus.mcgf.net;

import java.util.function.Consumer;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public final class ModNetworking {
    private ModNetworking() { }

    public static void send(ServerPlayerEntity player, Identifier channel, Consumer<PacketByteBuf> write) {
        PacketByteBuf buf = PacketByteBufs.create();
        write.accept(buf);
        ServerPlayNetworking.send(player, channel, buf);
    }
}
