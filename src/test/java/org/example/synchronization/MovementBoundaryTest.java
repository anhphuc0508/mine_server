package org.example.synchronization;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.RelativeFlags;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Chunk;
import net.minestom.server.listener.PlayerPositionListener;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.server.network.player.GameProfile;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import net.minestom.testing.TestConnection;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/** Boundary checks at Minestom's in-process movement listener; BoundaryWireTest covers actual TCP. */
@EnvTest
class MovementBoundaryTest {
    private static final Pos START = new Pos(0.5, 64, 0.5);
    private static final Pos INSIDE = START.add(1.5, 0, 0);
    private static final Pos OUTSIDE = START.add(2.1, 0, 0);

    @Test
    void outsideMovementReturnsToTheOriginalAnchorRatherThanTheLastInsidePosition(Env env) {
        var fixture = fixture(env, false);
        fixture.boundary.enable(fixture.player, 2);
        move(fixture.player, INSIDE);
        assertEquals(INSIDE, fixture.player.getPosition());
        assertEquals(INSIDE, fixture.movement.authoritativePosition(fixture.player));
        var corrections = fixture.connection.trackIncoming(PlayerPositionAndLookPacket.class);
        fixture.player.setVelocity(new Vec(2, 3, 4));

        move(fixture.player, OUTSIDE);

        assertEquals(START, fixture.player.getPosition());
        assertNotEquals(INSIDE, fixture.player.getPosition());
        assertEquals(START, fixture.movement.authoritativePosition(fixture.player));
        assertEquals(START, fixture.boundary.zone(fixture.player).origin());
        assertEquals(Vec.ZERO, fixture.player.getVelocity());
        corrections.assertSingle(packet -> {
            assertTrue(START.samePoint(packet.position()));
            assertEquals(Vec.ZERO, packet.delta());
            assertEquals(0, packet.flags());
            assertEquals(fixture.player.getLastSentTeleportId(), packet.teleportId());
        });
        assertNotEquals(fixture.player.getLastSentTeleportId(), fixture.player.getLastReceivedTeleportId());
    }

    @Test
    void anOutsideRequestFromTheAnchorSendsOnlyOneCorrection(Env env) {
        var fixture = fixture(env, false);
        fixture.boundary.enable(fixture.player, 2);
        var corrections = fixture.connection.trackIncoming(PlayerPositionAndLookPacket.class);
        fixture.player.setVelocity(new Vec(2, 0, 0));

        move(fixture.player, OUTSIDE);

        assertEquals(START, fixture.player.getPosition());
        assertEquals(START, fixture.movement.authoritativePosition(fixture.player));
        assertEquals(Vec.ZERO, fixture.player.getVelocity());
        corrections.assertSingle(packet -> {
            assertTrue(START.samePoint(packet.position()));
            assertEquals(Vec.ZERO, packet.delta());
        });
    }

    @Test
    void theObserverReceivesTheOriginAndNeverTheOutsidePosition(Env env) {
        var fixture = fixture(env, false);
        fixture.boundary.enable(fixture.player, 2);
        var observerConnection = env.createConnection();
        var observer = observerConnection.connect(fixture.player.getInstance(), START.add(0, 0, 3));
        observer.setNoGravity(true);
        fixture.player.addViewer(observer);
        assertTrue(fixture.player.getViewers().contains(observer));
        move(fixture.player, INSIDE);
        fixture.player.synchronizeNextTick();
        env.tick();
        var observerPackets = observerConnection.trackIncoming();

        move(fixture.player, OUTSIDE);
        fixture.player.synchronizeNextTick();
        env.tick();

        Pos observed = INSIDE;
        int positions = 0;
        for (var packet : observerPackets.collect()) {
            if (packet instanceof EntityPositionSyncPacket sync && sync.entityId() == fixture.player.getEntityId()) {
                observed = sync.position().asPos();
            } else if (packet instanceof EntityTeleportPacket teleport && teleport.entityId() == fixture.player.getEntityId()) {
                var position = teleport.position();
                observed = position.add((teleport.flags() & RelativeFlags.X) != 0 ? observed.x() : 0,
                        (teleport.flags() & RelativeFlags.Y) != 0 ? observed.y() : 0,
                        (teleport.flags() & RelativeFlags.Z) != 0 ? observed.z() : 0);
            } else if (packet instanceof EntityPositionPacket relative && relative.entityId() == fixture.player.getEntityId()) {
                observed = observed.add(relative.deltaX() / 4096.0, relative.deltaY() / 4096.0, relative.deltaZ() / 4096.0);
            } else if (packet instanceof EntityPositionAndRotationPacket relative && relative.entityId() == fixture.player.getEntityId()) {
                observed = observed.add(relative.deltaX() / 4096.0, relative.deltaY() / 4096.0, relative.deltaZ() / 4096.0);
            } else {
                continue;
            }
            positions++;
            assertFalse(OUTSIDE.samePoint(observed), "The rejected outside position reached an observer");
            assertTrue(Math.abs(observed.x() - START.x()) <= 2);
            assertTrue(Math.abs(observed.z() - START.z()) <= 2);
        }
        assertTrue(positions > 0, "The observer assertion must include actual position packets");
        assertTrue(START.samePoint(observed));
    }

    @Test
    void correctionWaitsForConfirmationAndRepeatedExitsKeepTheOriginalAnchor(Env env) {
        var fixture = fixture(env, false);
        fixture.boundary.enable(fixture.player, 2);

        for (int excursion = 0; excursion < 3; excursion++) {
            move(fixture.player, INSIDE);
            assertEquals(INSIDE, fixture.player.getPosition());
            move(fixture.player, OUTSIDE);
            assertEquals(START, fixture.player.getPosition());
            assertEquals(START, fixture.boundary.zone(fixture.player).origin());

            Pos resumed = START.add(0.25, 0, 0);
            move(fixture.player, resumed);
            assertEquals(START, fixture.player.getPosition(), "Movement must wait for the teleport ACK");
            confirm(fixture.player);
            move(fixture.player, resumed);
            assertEquals(resumed, fixture.player.getPosition());
            assertEquals(resumed, fixture.movement.authoritativePosition(fixture.player));
        }
    }

    @Test
    void theBoundaryStillAppliesWithSpeedValidationDisabledAndCanBeDisabledIndependently(Env env) {
        var fixture = fixture(env, false);
        fixture.movement.setValidationEnabled(false);
        fixture.boundary.enable(fixture.player, 2);
        move(fixture.player, INSIDE);
        Pos farOutside = START.add(10, 0, 0);

        move(fixture.player, farOutside);

        assertEquals(START, fixture.player.getPosition());
        assertTrue(fixture.boundary.isEnabled(fixture.player));
        confirm(fixture.player);
        fixture.boundary.disable(fixture.player);
        move(fixture.player, farOutside);
        assertEquals(farOutside, fixture.player.getPosition());
        assertFalse(fixture.boundary.isEnabled(fixture.player));
        assertNull(fixture.boundary.zone(fixture.player));
    }

    @Test
    void enablingCapturesTheCurrentPositionAndOnlyAppliesToThatPlayer(Env env) {
        var fixture = fixture(env, false);
        assertFalse(fixture.boundary.isEnabled(fixture.player));
        Pos current = START.add(0.5, 0, 0);
        move(fixture.player, current);
        fixture.boundary.enable(fixture.player, 1);

        assertEquals(current, fixture.boundary.zone(fixture.player).origin());
        var other = env.createPlayer(fixture.player.getInstance(), START);
        other.setNoGravity(true);
        confirm(other);
        assertFalse(fixture.boundary.isEnabled(other));
        move(other, START.add(2, 0, 0));
        assertEquals(START.add(2, 0, 0), other.getPosition());
        assertEquals(current, fixture.boundary.zone(fixture.player).origin());
    }

    @Test
    void invalidRadiusDoesNotCreateARegion(Env env) {
        var fixture = fixture(env, false);
        for (double invalid : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> fixture.boundary.enable(fixture.player, invalid));
            assertFalse(fixture.boundary.isEnabled(fixture.player));
            assertNull(fixture.boundary.zone(fixture.player));
        }
    }

    @Test
    void forgettingADisconnectedPlayerRemovesTheRegion(Env env) {
        var fixture = fixture(env, false);
        fixture.boundary.enable(fixture.player, 1);

        fixture.boundary.forget(fixture.player);

        assertFalse(fixture.boundary.isEnabled(fixture.player));
        assertNull(fixture.boundary.zone(fixture.player));
        move(fixture.player, START.add(1.5, 0, 0));
        assertEquals(START.add(1.5, 0, 0), fixture.player.getPosition());
    }

    @Test
    void pendingRollbackDoesNotStartAnotherTeleportAndCommitsOnlyAfterCompletion(Env env) {
        var fixture = fixture(env, true);
        var player = (ControlledTeleportPlayer) fixture.player;
        fixture.boundary.enable(player, 2);
        move(player, INSIDE);
        player.delayNextTeleport();

        move(player, OUTSIDE);

        assertEquals(INSIDE, player.getPosition());
        assertEquals(INSIDE, fixture.movement.authoritativePosition(player));
        assertEquals(1, player.delayedTeleportCalls);
        confirm(player); // Confirm the interim correction produced by Minestom while the future waits.
        move(player, OUTSIDE.add(0.1, 0, 0));
        assertEquals(1, player.delayedTeleportCalls);
        assertEquals(INSIDE, player.getPosition());

        player.completeDelayedTeleport();

        assertEquals(START, player.getPosition());
        assertEquals(START, fixture.movement.authoritativePosition(player));
        assertEquals(START, fixture.boundary.zone(player).origin());
        confirm(player);
        move(player, START.add(0.25, 0, 0));
        assertEquals(START.add(0.25, 0, 0), player.getPosition());
    }

    @Test
    void failedRollbackKeepsActualAuthorityAndAllowsAnotherAttempt(Env env) {
        var fixture = fixture(env, true);
        var player = (ControlledTeleportPlayer) fixture.player;
        fixture.boundary.enable(player, 2);
        move(player, INSIDE);
        player.delayNextTeleport();
        move(player, OUTSIDE);

        player.failDelayedTeleport();

        assertEquals(INSIDE, player.getPosition());
        assertEquals(INSIDE, fixture.movement.authoritativePosition(player));
        assertEquals(START, fixture.boundary.zone(player).origin());
        confirm(player);
        move(player, OUTSIDE);
        assertEquals(START, player.getPosition());
        assertEquals(START, fixture.movement.authoritativePosition(player));
    }

    @Test
    void disablingDuringRollbackCancelsTheFutureAndDoesNotRestoreTheOldRegion(Env env) {
        var fixture = fixture(env, true);
        var player = (ControlledTeleportPlayer) fixture.player;
        fixture.boundary.enable(player, 2);
        move(player, INSIDE);
        player.delayNextTeleport();
        move(player, OUTSIDE);
        var pending = player.delayedTeleport;

        fixture.boundary.disable(player);

        assertTrue(pending.isCancelled());
        player.completeDelayedTeleport();
        assertEquals(INSIDE, player.getPosition());
        assertFalse(fixture.boundary.isEnabled(player));
        assertNull(fixture.boundary.zone(player));
        confirm(player);
        move(player, OUTSIDE);
        assertEquals(OUTSIDE, player.getPosition());
    }

    @Test
    void disconnectCleanupCancelsAnOutstandingRollback(Env env) {
        var fixture = fixture(env, true);
        var player = (ControlledTeleportPlayer) fixture.player;
        fixture.boundary.enable(player, 2);
        move(player, INSIDE);
        player.delayNextTeleport();
        move(player, OUTSIDE);
        var pending = player.delayedTeleport;

        fixture.boundary.forget(player);

        assertTrue(pending.isCancelled());
        player.completeDelayedTeleport();
        assertEquals(INSIDE, player.getPosition());
        assertFalse(fixture.boundary.isEnabled(player));
        assertNull(fixture.boundary.zone(player));
    }

    private static Fixture fixture(Env env, boolean controlled) {
        if (controlled) env.process().connection().setPlayerProvider(ControlledTeleportPlayer::new);
        var connection = env.createConnection();
        var player = connection.connect(env.createEmptyInstance(), START);
        player.setNoGravity(true);
        confirm(player);
        var logs = new ArrayList<String>();
        var movement = new PositionCorrection(logs::add);
        movement.initialize(player);
        var boundary = new MovementBoundary(movement, logs::add);
        env.process().eventHandler().addListener(PlayerMoveEvent.class, event -> {
            if (!boundary.handle(event)) movement.handle(event);
        });
        return new Fixture(player, movement, boundary, connection, logs);
    }

    private static void move(Player player, Pos requested) {
        PlayerPositionListener.playerPositionListener(new ClientPlayerPositionPacket(requested, true, false), player);
    }

    private static void confirm(Player player) {
        PlayerPositionListener.teleportConfirmListener(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()), player);
    }

    private record Fixture(Player player, PositionCorrection movement, MovementBoundary boundary,
                           TestConnection connection, List<String> logs) {}

    /** Controls the teleport future boundary; it does not claim to exercise asynchronous chunk I/O. */
    private static final class ControlledTeleportPlayer extends Player {
        private CompletableFuture<Void> delayedTeleport;
        private Pos delayedPosition;
        private Vec delayedVelocity;
        private int delayedTeleportCalls;

        private ControlledTeleportPlayer(PlayerConnection connection, GameProfile profile) {
            super(connection, profile);
        }

        @Override
        public void sendChunk(Chunk chunk) {
            sendPacket(chunk.getFullDataPacket());
        }

        private void delayNextTeleport() {
            delayedTeleport = new CompletableFuture<>();
        }

        @Override
        public CompletableFuture<Void> teleport(Pos position, Vec velocity) {
            if (delayedTeleport == null) return super.teleport(position, velocity);
            delayedTeleportCalls++;
            delayedPosition = position;
            delayedVelocity = velocity;
            return delayedTeleport;
        }

        private void completeDelayedTeleport() {
            var completion = delayedTeleport;
            delayedTeleport = null;
            if (completion.isCancelled()) return;
            super.teleport(delayedPosition, delayedVelocity).join();
            completion.complete(null);
        }

        private void failDelayedTeleport() {
            var completion = delayedTeleport;
            delayedTeleport = null;
            completion.completeExceptionally(new IllegalStateException("Controlled rollback failure"));
        }
    }
}
