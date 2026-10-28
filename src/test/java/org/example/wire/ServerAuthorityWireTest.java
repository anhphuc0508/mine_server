package org.example.wire;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import net.minestom.server.entity.Player;
import net.minestom.server.event.entity.EntityAttackEvent;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerDeathEvent;
import net.minestom.server.event.player.PlayerDisconnectEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerRespawnEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.client.play.ClientAttackPacket;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.client.play.ClientStatusPacket;
import net.minestom.server.network.packet.server.play.ChangeGameStatePacket;
import net.minestom.server.network.packet.server.play.DeathCombatEventPacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.packet.server.play.RespawnPacket;
import net.minestom.server.network.packet.server.play.UpdateHealthPacket;
import net.minestom.server.property.ServerProperties;
import org.example.combat.CombatManager;
import org.example.player.PlayerLifecycle;
import org.example.synchronization.MovementBoundary;
import org.example.synchronization.PositionCorrection;
import org.example.world.WorldManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end server checks over real loopback TCP, including the vanilla protocol codecs and
 * Minestom's unmodified packet dispatcher. Requests arrive through the client connections.
 * Internal player state and logs supplement assertions on packets actually received by clients.
 */
class ServerAuthorityWireTest {
    private static final Pos START = new Pos(0.5, 64, 0.5);
    private static final Pos TARGET = new Pos(4.5, 64, 0.5);
    private static final Duration WAIT = Duration.ofSeconds(12);

    @Test
    @Timeout(value = 60, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void realSocketClientsObserveMovementCorrectionReachAuthorityAndRespawn() throws Exception {
        MinecraftServer server = MinecraftServer.init();
        var process = MinecraftServer.process();
        var logs = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        process.exception().setExceptionHandler(errors::add);
        var movement = new PositionCorrection(logs::add);
        var boundary = new MovementBoundary(movement, logs::add);
        var players = new PlayerLifecycle(movement, boundary, logs::add);
        var combat = new CombatManager(entity -> entity instanceof Player player
                ? movement.authoritativePosition(player) : entity.getPosition(), logs::add);
        var instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(63, 64, Block.STONE));
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS);
        instance.loadChunk(1, 0).get(5, TimeUnit.SECONDS);
        var attackerRef = new AtomicReference<Player>();
        var observerRef = new AtomicReference<Player>();
        var events = process.eventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(event.getPlayer().getUsername().equals("WireA") ? START : TARGET);
        });
        events.addListener(PlayerSpawnEvent.class, event -> {
            players.onSpawn(event);
            Player player = event.getPlayer();
            player.setNoGravity(true);
            (player.getUsername().equals("WireA") ? attackerRef : observerRef).set(player);
        });
        events.addListener(PlayerDeathEvent.class, players::onDeath);
        events.addListener(PlayerRespawnEvent.class, players::onRespawn);
        events.addListener(PlayerDisconnectEvent.class, players::onDisconnect);
        events.addListener(PlayerMoveEvent.class, event -> {
            if (!boundary.handle(event)) movement.handle(event);
        });
        events.addListener(EntityAttackEvent.class, combat::handle);

        try {
            server.start("127.0.0.1", 0);
            int port = process.server().getPort();
            assertTrue(ServerProperties.ENFORCE_INTERACTION_LIMIT.get(),
                    "This test must preserve Minestom's earlier interaction filter");
            try (var attackerClient = new LoopbackMinecraftClient(port, "WireA");
                 var observerClient = new LoopbackMinecraftClient(port, "WireB")) {
                await(() -> ready(attackerRef.get()) && ready(observerRef.get()),
                        "Both real socket clients must finish configuration, spawn and confirm teleport",
                        attackerClient, observerClient);
                Player attacker = attackerRef.get();
                Player observer = observerRef.get();
                await(() -> attacker.getViewers().contains(observer) && observer.getViewers().contains(attacker)
                                && samePoint(START, observerClient.observedPosition(attacker.getEntityId())),
                        "Observer must receive the attacker's spawn over TCP", attackerClient, observerClient);

                // A legitimate movement request traverses TCP and is replicated to the other socket.
                Pos legitimate = START.add(0.5, 0, 0);
                attackerClient.send(new ClientPlayerPositionPacket(legitimate, true, false));
                await(() -> samePoint(legitimate, attacker.getPosition())
                                && samePoint(legitimate, observerClient.observedPosition(attacker.getEntityId())),
                        "Legitimate socket movement must be accepted and replicated", attackerClient, observerClient);
                assertTrue(legitimate.samePoint(movement.authoritativePosition(attacker)));

                // A forged +20-block movement must produce a correction on the attacking connection.
                long attackCheckpoint = attackerClient.checkpoint();
                long observerCheckpoint = observerClient.checkpoint();
                Pos cheated = legitimate.add(20, 0, 0);
                attackerClient.send(new ClientPlayerPositionPacket(cheated, true, false));
                await(() -> attackerClient.receivedSince(attackCheckpoint).stream().anyMatch(
                                value -> value.packet() instanceof PlayerPositionAndLookPacket packet
                                        && legitimate.samePoint(packet.position()))
                                && logs.stream().anyMatch(line -> line.contains("[PACKET] REJECT")
                                        && line.contains("reason=horizontal-speed")),
                        "The real forged packet must reach custom validation and return a correction",
                        attackerClient, observerClient);
                await(() -> ready(attacker), "Correction must be acknowledged over TCP", attackerClient, observerClient);
                PlayerPositionAndLookPacket correction = attackerClient.receivedSince(attackCheckpoint).stream()
                        .map(LoopbackMinecraftClient.Received::packet)
                        .filter(PlayerPositionAndLookPacket.class::isInstance)
                        .map(PlayerPositionAndLookPacket.class::cast).findFirst().orElseThrow();
                assertEquals(0, correction.flags());
                assertEquals(Vec.ZERO, correction.delta());
                assertTrue(legitimate.samePoint(attacker.getPosition()));
                assertTrue(legitimate.samePoint(movement.authoritativePosition(attacker)));

                // Force ordinary server synchronization so the observer assertion is non-vacuous.
                nextTick(attacker::synchronizeNextTick);
                await(() -> !observerClient.positionsSince(observerCheckpoint, attacker.getEntityId()).isEmpty(),
                        "Observer must receive a normal position synchronization after rejection",
                        attackerClient, observerClient);
                for (var observed : observerClient.positionsSince(observerCheckpoint, attacker.getEntityId())) {
                    assertTrue(legitimate.samePoint(observed.position()),
                            "Rejected movement leaked to another real connection: " + observed);
                }

                // Identical real packet with the custom movement rule disabled is accepted.
                movement.setValidationEnabled(false);
                attackerClient.send(new ClientPlayerPositionPacket(cheated, true, false));
                await(() -> samePoint(cheated, attacker.getPosition())
                                && samePoint(cheated, observerClient.observedPosition(attacker.getEntityId())),
                        "Disabled-rule control must allow the same forged movement and replicate it",
                        attackerClient, observerClient);

                // Server-authorized fixture reset; attack requests themselves still cross TCP.
                attacker.teleport(START).get(5, TimeUnit.SECONDS);
                observer.teleport(TARGET).get(5, TimeUnit.SECONDS);
                movement.setValidationEnabled(true);
                movement.initialize(attacker);
                movement.initialize(observer);
                await(() -> ready(attacker) && ready(observer), "Reset teleports must be confirmed",
                        attackerClient, observerClient);
                assertEquals(20.0f, observer.getHealth());

                // Feet distance 4, eye-to-box 3.7: custom range 3 rejects, Minestom limit 4 permits.
                int beforeAttackLogs = logs.size();
                attackerClient.send(new ClientAttackPacket(observer.getEntityId()));
                await(() -> attackLogs(logs, beforeAttackLogs).stream().anyMatch(line ->
                                line.contains("source=CLIENT_EVENT") && line.contains("decision=REJECT")
                                        && line.contains("reason=OUT_OF_REACH") && line.contains("distance=3.700")),
                        "The wire attack must pass the built-in filter and be rejected by custom reach",
                        attackerClient, observerClient);
                assertEquals(20.0f, observer.getHealth(), "Rejected wire reach attack must not deal damage");

                long healthCheckpoint = observerClient.checkpoint();
                int beforeControlLogs = logs.size();
                combat.setReachValidationEnabled(false);
                attackerClient.send(new ClientAttackPacket(observer.getEntityId()));
                await(() -> observer.getHealth() == 16.0f
                                && observerClient.receivedSince(healthCheckpoint).stream().anyMatch(value ->
                                        value.packet() instanceof UpdateHealthPacket health && health.health() == 16.0f)
                                && attackLogs(logs, beforeControlLogs).stream().anyMatch(line ->
                                        line.contains("source=CLIENT_EVENT") && line.contains("decision=ACCEPT")
                                                && line.contains("reason=CUSTOM_REACH_DISABLED")),
                        "The identical wire attack must deal damage and send target HP with custom reach disabled",
                        attackerClient, observerClient);

                // Lethal damage must use the native death screen and wait for the Respawn button.
                nextTick(() -> {
                    observer.setHealth(4);
                    observer.setFood(3);
                    observer.setFoodSaturation(0);
                    boundary.enable(observer, 1);
                });
                long deathCheckpoint = observerClient.checkpoint();
                attackerClient.send(new ClientAttackPacket(observer.getEntityId()));
                await(() -> observer.isDead() && observer.getHealth() == 0
                                && observerClient.receivedSince(deathCheckpoint).stream().anyMatch(value ->
                                        value.packet() instanceof DeathCombatEventPacket death
                                                && death.playerId() == observer.getEntityId())
                                && observerClient.receivedSince(deathCheckpoint).stream().anyMatch(value ->
                                        value.packet() instanceof UpdateHealthPacket health && health.health() == 0),
                        "A lethal wire attack must send native death-screen and zero-health packets",
                        attackerClient, observerClient);
                assertTrue(observer.isEnableRespawnScreen());
                assertTrue(observerClient.receivedSince(0).stream().anyMatch(value ->
                                value.packet() instanceof ChangeGameStatePacket state
                                        && state.reason() == ChangeGameStatePacket.Reason.ENABLE_RESPAWN_SCREEN
                                        && state.value() == 0),
                        "The client must be explicitly configured to show the Respawn screen");
                assertFalse(boundary.isEnabled(observer), "Death must clear the previous life's boundary");
                nextTick(() -> assertTrue(observer.isDead(), "The server must wait for the player's respawn request"));
                assertTrue(observerClient.receivedSince(deathCheckpoint).stream()
                        .noneMatch(value -> value.packet() instanceof RespawnPacket));

                // This is the same protocol request sent when clicking Respawn in Minecraft.
                long respawnCheckpoint = observerClient.checkpoint();
                observerClient.send(new ClientStatusPacket(ClientStatusPacket.Action.PERFORM_RESPAWN));
                await(() -> !observer.isDead() && observer.getHealth() == 20 && ready(observer)
                                && samePoint(WorldManager.SPAWN, observer.getPosition())
                                && observerClient.receivedSince(respawnCheckpoint).stream().anyMatch(value ->
                                        value.packet() instanceof RespawnPacket)
                                && observerClient.receivedSince(respawnCheckpoint).stream().anyMatch(value ->
                                        value.packet() instanceof PlayerPositionAndLookPacket position
                                                && WorldManager.SPAWN.samePoint(position.position()))
                                && samePoint(WorldManager.SPAWN,
                                        attackerClient.observedPosition(observer.getEntityId())),
                        "Clicking Respawn must restore health, teleport to the original spawn and update the other client",
                        attackerClient, observerClient);
                assertEquals(20, observer.getFood());
                assertEquals(20, observer.getFoodSaturation());
                assertEquals(Vec.ZERO, observer.getVelocity());
                assertEquals(GameMode.SURVIVAL, observer.getGameMode());
                assertTrue(WorldManager.SPAWN.samePoint(movement.authoritativePosition(observer)));
                assertFalse(boundary.isEnabled(observer));
                assertFalse(combat.isReachValidationEnabled(), "Respawn must preserve the server's validation settings");

                Pos afterRespawn = WorldManager.SPAWN.add(0.25, 0, 0);
                observerClient.send(new ClientPlayerPositionPacket(afterRespawn, true, false));
                await(() -> samePoint(afterRespawn, observer.getPosition())
                                && samePoint(afterRespawn, movement.authoritativePosition(observer))
                                && samePoint(afterRespawn, attackerClient.observedPosition(observer.getEntityId())),
                        "Movement after respawn must not roll back to the previous death or boundary position",
                        attackerClient, observerClient);
                assertTrue(errors.isEmpty(), () -> "Server exceptions during real socket test: " + errors);
            }
        } finally {
            process.stop();
        }
    }

    private static List<String> attackLogs(List<String> logs, int checkpoint) {
        return logs.stream().skip(checkpoint).filter(line -> line.startsWith("ATTACK ")).toList();
    }

    private static boolean ready(Player player) {
        return player != null && player.getInstance() != null
                && player.getLastSentTeleportId() == player.getLastReceivedTeleportId();
    }

    private static boolean samePoint(Pos expected, Pos actual) {
        return actual != null && expected.distance(actual) < 1.0e-6;
    }

    private static void nextTick(Runnable action) throws Exception {
        var completion = new CompletableFuture<Void>();
        MinecraftServer.getSchedulerManager().scheduleNextTick(() -> {
            try {
                action.run();
                completion.complete(null);
            } catch (Throwable failure) {
                completion.completeExceptionally(failure);
            }
        });
        completion.get(5, TimeUnit.SECONDS);
    }

    private static void await(BooleanSupplier condition, String message,
                              LoopbackMinecraftClient... clients) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        do {
            for (var client : clients) client.checkHealthy();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        fail(message + " (timed out after " + WAIT.toSeconds() + " seconds)");
    }
}
