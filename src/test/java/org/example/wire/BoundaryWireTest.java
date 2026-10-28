package org.example.wire;

import net.minestom.server.MinecraftServer;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.AsyncPlayerConfigurationEvent;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.event.player.PlayerSpawnEvent;
import net.minestom.server.instance.block.Block;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import org.example.synchronization.MovementBoundary;
import org.example.synchronization.PositionCorrection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Real loopback TCP and normal server dispatch; this test never calls a movement harness or listener. */
class BoundaryWireTest {
    private static final Pos START = new Pos(0.5, 64, 0.5);
    private static final Pos INSIDE = START.add(1.5, 0, 0);
    private static final Pos OUTSIDE = START.add(2.1, 0, 0);
    private static final Duration WAIT = Duration.ofSeconds(12);

    @Test
    @Timeout(value = 45, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void crossingTheBoundaryReturnsToTheOriginalAnchorOverTcpAndNeverLeaksTheOutsidePosition() throws Exception {
        MinecraftServer server = MinecraftServer.init();
        var process = MinecraftServer.process();
        var logs = new CopyOnWriteArrayList<String>();
        var errors = new CopyOnWriteArrayList<Throwable>();
        process.exception().setExceptionHandler(errors::add);
        var movement = new PositionCorrection(logs::add);
        var boundary = new MovementBoundary(movement, logs::add);
        var instance = process.instance().createInstanceContainer();
        instance.setGenerator(unit -> unit.modifier().fillHeight(63, 64, Block.STONE));
        instance.loadChunk(0, 0).get(5, TimeUnit.SECONDS);
        var walkerRef = new AtomicReference<Player>();
        var observerRef = new AtomicReference<Player>();
        var events = process.eventHandler();
        events.addListener(AsyncPlayerConfigurationEvent.class, event -> {
            event.setSpawningInstance(instance);
            event.getPlayer().setRespawnPoint(event.getPlayer().getUsername().equals("BoundaryA")
                    ? START : START.add(0, 0, 3));
        });
        events.addListener(PlayerSpawnEvent.class, event -> {
            Player player = event.getPlayer();
            player.setNoGravity(true);
            movement.initialize(player);
            if (player.getUsername().equals("BoundaryA")) {
                boundary.enable(player, 2);
                walkerRef.set(player);
            } else {
                observerRef.set(player);
            }
        });
        events.addListener(PlayerMoveEvent.class, event -> {
            if (!boundary.handle(event)) movement.handle(event);
        });

        try {
            server.start("127.0.0.1", 0);
            int port = process.server().getPort();
            try (var walkerClient = new LoopbackMinecraftClient(port, "BoundaryA");
                 var observerClient = new LoopbackMinecraftClient(port, "BoundaryB")) {
                await(() -> ready(walkerRef.get()) && ready(observerRef.get()),
                        "Both clients must finish real login and teleport confirmation", walkerClient, observerClient);
                Player walker = walkerRef.get();
                await(() -> samePoint(START, observerClient.observedPosition(walker.getEntityId())),
                        "Observer must receive the walker's starting position", walkerClient, observerClient);

                walkerClient.send(new ClientPlayerPositionPacket(INSIDE, true, false));
                await(() -> samePoint(INSIDE, walker.getPosition())
                                && samePoint(INSIDE, observerClient.observedPosition(walker.getEntityId())),
                        "An inside movement distinct from the origin must be accepted", walkerClient, observerClient);

                long correctionCheckpoint = walkerClient.checkpoint();
                long observerCheckpoint = observerClient.checkpoint();
                walkerClient.send(new ClientPlayerPositionPacket(OUTSIDE, true, false));
                await(() -> ready(walker) && samePoint(START, walker.getPosition())
                                && samePoint(START, movement.authoritativePosition(walker))
                                && samePoint(START, observerClient.observedPosition(walker.getEntityId()))
                                && walkerClient.receivedSince(correctionCheckpoint).stream().anyMatch(value ->
                                        value.packet() instanceof PlayerPositionAndLookPacket correction
                                                && START.samePoint(correction.position())),
                        "Boundary crossing must return to the original anchor and be acknowledged over TCP",
                        walkerClient, observerClient);

                var corrections = walkerClient.receivedSince(correctionCheckpoint).stream()
                        .map(LoopbackMinecraftClient.Received::packet)
                        .filter(PlayerPositionAndLookPacket.class::isInstance)
                        .map(PlayerPositionAndLookPacket.class::cast).toList();
                assertFalse(corrections.isEmpty());
                for (var correction : corrections) {
                    assertTrue(START.samePoint(correction.position()),
                            "Rollback must use the initial anchor rather than the last inside point");
                    assertEquals(Vec.ZERO, correction.delta());
                    assertEquals(0, correction.flags());
                }
                var observed = observerClient.positionsSince(observerCheckpoint, walker.getEntityId());
                assertTrue(observed.stream().anyMatch(value -> START.samePoint(value.position())));
                assertTrue(observed.stream().allMatch(value ->
                                Math.abs(value.position().x() - START.x()) <= 2.0 + 1.0e-6
                                        && Math.abs(value.position().z() - START.z()) <= 2.0 + 1.0e-6),
                        "An outside position must never reach the other real connection, including quantized updates");

                Pos resumed = START.add(0.25, 0, 0);
                walkerClient.send(new ClientPlayerPositionPacket(resumed, true, false));
                await(() -> samePoint(resumed, walker.getPosition())
                                && samePoint(resumed, observerClient.observedPosition(walker.getEntityId())),
                        "Normal walking must resume after the correction acknowledgement", walkerClient, observerClient);

                // A second excursion must retain the same origin rather than rebasing on the last walk.
                walkerClient.send(new ClientPlayerPositionPacket(INSIDE, true, false));
                await(() -> samePoint(INSIDE, walker.getPosition()),
                        "The next inside step must be accepted", walkerClient, observerClient);
                long repeatedCheckpoint = walkerClient.checkpoint();
                walkerClient.send(new ClientPlayerPositionPacket(OUTSIDE, true, false));
                await(() -> ready(walker) && samePoint(START, walker.getPosition())
                                && walkerClient.receivedSince(repeatedCheckpoint).stream().anyMatch(value ->
                                        value.packet() instanceof PlayerPositionAndLookPacket correction
                                                && START.samePoint(correction.position())),
                        "Repeated exits must return to the same original anchor", walkerClient, observerClient);
                assertEquals(START, boundary.zone(walker).origin());

                nextTick(() -> boundary.disable(walker));
                walkerClient.send(new ClientPlayerPositionPacket(OUTSIDE, true, false));
                await(() -> samePoint(OUTSIDE, walker.getPosition())
                                && samePoint(OUTSIDE, observerClient.observedPosition(walker.getEntityId())),
                        "Disabling the region must allow the same outside walk", walkerClient, observerClient);
                assertTrue(errors.isEmpty(), () -> "Server errors during boundary wire test: " + errors);
            }
        } finally {
            process.stop();
        }
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
