package xyz.apollodorus.mcgf;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.network.ConnectedClientData;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.world.GameMode;

import java.util.UUID;

public final class GameTestPlayers {
    private GameTestPlayers() {}

    public static ServerPlayerEntity survivalPlayer(TestContext test, String name) {
        var world = test.getWorld();
        var server = world.getServer();
        var data = ConnectedClientData.createDefault(new GameProfile(UUID.randomUUID(), name), false);
        var player = new ServerPlayerEntity(server, world, data.gameProfile(), data.syncedOptions());
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerManager().onPlayerConnect(connection, player, data);
        player.changeGameMode(GameMode.SURVIVAL);
        return player;
    }
}
