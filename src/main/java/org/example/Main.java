package org.example;

import org.example.combat.CombatManager;
import org.example.diagnostics.DemoLogger;
import org.example.player.GameCommand;
import org.example.player.PlayerLifecycle;
import org.example.synchronization.MovementBoundary;
import org.example.synchronization.PositionCorrection;
import org.example.world.WorldManager;
import org.fusesource.jansi.AnsiConsole;

import net.minestom.server.MinecraftServer;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerPacketEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.event.server.ServerListPingEvent;
import net.minestom.server.network.packet.client.play.ClientAttackPacket;
import net.minestom.server.ping.Status;
import net.minestom.server.property.ServerProperties;

public final class Main {
    public static void main(String[] args) throws Exception {
        AnsiConsole.systemInstall();
        MinecraftServer server = MinecraftServer.init();
        DemoLogger logger = new DemoLogger();
        var world = WorldManager.createDemoWorld(logger::log);
        var movement = new PositionCorrection(logger::log);
        var boundary = new MovementBoundary(movement, logger::log);
        var players = new PlayerLifecycle(movement, boundary, logger::log);
        var combat = new CombatManager(entity -> entity instanceof Player player
                ? movement.authoritativePosition(player) : entity.getPosition(), logger::log);
        var events = MinecraftServer.getGlobalEventHandler();

        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(world);
            event.getPlayer().setRespawnPoint(WorldManager.SPAWN);
        });
        events.addListener(PlayerSpawnEvent.class, players::onSpawn);
        events.addListener(PlayerDeathEvent.class, players::onDeath);
        events.addListener(PlayerRespawnEvent.class, players::onRespawn);
        events.addListener(PlayerMoveEvent.class, event -> {
            if (!boundary.handle(event)) movement.handle(event);
        });
        events.addListener(PlayerPacketEvent.class, event -> {
            if (event.getPacket() instanceof ClientAttackPacket packet) {
                logger.log("ATTACK_PACKET phase=RECEIVED player=" + event.getPlayer().getUsername()
                        + " targetId=" + packet.targetId());
            }
        });
        events.addListener(EntityAttackEvent.class, combat::handle);
        events.addListener(PlayerDisconnectEvent.class, players::onDisconnect);
        events.addListener(ServerListPingEvent.class, event -> event.setStatus(Status.builder()
                .description(net.kyori.adventure.text.Component.text("T48 | Movement Boundary + Reach Validation"))
                .playerInfo(MinecraftServer.getConnectionManager().getOnlinePlayerCount(), 10).build()));
        MinecraftServer.getCommandManager().register(new GameCommand(movement, boundary, combat, logger));

        String host = System.getProperty("demo.host", "0.0.0.0");
        int port = Integer.getInteger("demo.port", 25565);
        logger.log("START host=" + host + " port=" + port
                + " minecraft=" + MinecraftServer.VERSION_NAME + " protocol=" + MinecraftServer.PROTOCOL_VERSION
                + " minestomInteractionLimit=" + ServerProperties.ENFORCE_INTERACTION_LIMIT.get()
                + " movementValidation=true reachValidation=true");
        server.start(host, port);
    }
}
