package org.example;

import net.minestom.server.MinecraftServer;
import net.minestom.server.property.ServerProperties;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.ping.Status;
import org.example.combat.CombatManager;
import org.example.diagnostics.DemoLogger;
import org.example.player.DemoCommand;
import org.example.synchronization.PositionCorrection;
import org.example.world.WorldManager;

public final class Main {
    public static void main(String[] args) throws Exception {
        MinecraftServer server = MinecraftServer.init();
        DemoLogger logger = new DemoLogger();
        var world = WorldManager.createDemoWorld(logger::log);
        var movement = new PositionCorrection(logger::log);
        var combat = new CombatManager(entity -> entity instanceof Player player
                ? movement.authoritativePosition(player) : entity.getPosition(), logger::log);
        var events = MinecraftServer.getGlobalEventHandler();

        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(world);
            event.getPlayer().setRespawnPoint(WorldManager.SPAWN);
        });
        events.addListener(PlayerSpawnEvent.class, event -> {
            var player = event.getPlayer();
            movement.initialize(player);
            if (event.isFirstSpawn()) {
                DemoCommand.preparePlayer(player);
                player.sendMessage("T48 | Hai demo san sang. Go /demo help de xem lenh.");
                logger.log("JOIN player=" + player.getUsername() + " position=" + player.getPosition());
            }
        });
        events.addListener(PlayerMoveEvent.class, movement::handle);
        events.addListener(EntityAttackEvent.class, combat::handle);
        events.addListener(PlayerDisconnectEvent.class, event -> movement.forget(event.getPlayer()));
        events.addListener(ServerListPingEvent.class, event -> event.setStatus(Status.builder()
                .description(net.kyori.adventure.text.Component.text("T48 | Position Correction + Reach Validation"))
                .playerInfo(MinecraftServer.getConnectionManager().getOnlinePlayerCount(), 10).build()));
        MinecraftServer.getCommandManager().register(new DemoCommand(movement, combat, logger));

        String host = System.getProperty("demo.host", "0.0.0.0");
        int port = Integer.getInteger("demo.port", 25565);
        logger.log("START host=" + host + " port=" + port
                + " minecraft=" + MinecraftServer.VERSION_NAME + " protocol=" + MinecraftServer.PROTOCOL_VERSION
                + " minestomInteractionLimit=" + ServerProperties.ENFORCE_INTERACTION_LIMIT.get()
                + " movementValidation=true reachValidation=true");
        logger.log("HARNESS commands call validation services; they do not inject network packets.");
        server.start(host, port);
    }
}
