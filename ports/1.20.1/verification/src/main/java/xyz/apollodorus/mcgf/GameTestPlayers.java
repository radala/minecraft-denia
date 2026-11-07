package xyz.apollodorus.mcgf;

import com.mojang.authlib.GameProfile;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.test.TestContext;
import net.minecraft.world.GameMode;

import java.util.UUID;

public final class GameTestPlayers {
    private GameTestPlayers() {}

    public static ServerPlayerEntity survivalPlayer(TestContext test, String name) {
        var world = test.getWorld();
        var server = world.getServer();
        var profile = new GameProfile(UUID.randomUUID(), name);
        var player = new ServerPlayerEntity(server, world, profile);
        var connection = new ClientConnection(NetworkSide.SERVERBOUND);
        server.getPlayerManager().onPlayerConnect(connection, player);
        player.changeGameMode(GameMode.SURVIVAL);
        return player;
    }
}
