package org.example.synchronization;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.listener.PlayerPositionListener;
import net.minestom.server.network.packet.client.play.ClientPlayerPositionPacket;
import net.minestom.server.network.packet.client.play.ClientTeleportConfirmPacket;
import net.minestom.server.network.packet.server.play.EntityPositionAndRotationPacket;
import net.minestom.server.network.packet.server.play.EntityPositionSyncPacket;
import net.minestom.server.network.packet.server.play.EntityTeleportPacket;
import net.minestom.server.network.packet.server.play.PlayerPositionAndLookPacket;
import net.minestom.testing.Env;
import net.minestom.testing.EnvTest;
import org.example.validation.MovementValidator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Invokes Minestom's packet listener in process; no sockets or rendered clients are involved. */
@EnvTest
class PositionCorrectionTest {
    private static final Pos START = new Pos(0, 64, 0);

    @Test
    void rejectsBeforePositionChangesAndSendsCorrectionWithoutReplicatingTheBadPosition(Env env) {
        var instance = env.createEmptyInstance();
        var connection = env.createConnection();
        var player = connection.connect(instance, START);
        var observerConnection = env.createConnection();
        var observer = observerConnection.connect(instance, new Pos(1, 64, 0));
        player.setNoGravity(true);
        observer.setNoGravity(true);
        player.addViewer(observer);
        assertTrue(player.getViewers().contains(observer));
        confirmTeleport(player);
        var correction = new PositionCorrection(ignored -> {});
        correction.initialize(player);
        var positionsBeforeValidation = new ArrayList<Pos>();
        env.process().eventHandler().addListener(PlayerMoveEvent.class, event -> {
            positionsBeforeValidation.add(event.getPlayer().getPosition());
            correction.handle(event);
        });
        var corrections = connection.trackIncoming(PlayerPositionAndLookPacket.class);
        var observerPackets = observerConnection.trackIncoming();

        move(player, new Pos(10, 64, 0));

        assertEquals(1, positionsBeforeValidation.size());
        assertEquals(START, positionsBeforeValidation.getFirst());
        assertEquals(START, player.getPosition());
        assertEquals(START, correction.authoritativePosition(player));
        corrections.assertSingle(packet -> {
            assertTrue(START.samePoint(packet.position()));
            assertEquals(Vec.ZERO, packet.delta());
            assertEquals(0, packet.flags());
            assertEquals(player.getLastSentTeleportId(), packet.teleportId());
        });
        assertNotEquals(player.getLastReceivedTeleportId(), player.getLastSentTeleportId());

        // Force a normal synchronization, then inspect what the observer actually receives.
        player.synchronizeNextTick();
        env.tick();
        var packets = observerPackets.collect();
        int positionPackets = 0;
        for (var packet : packets) {
            if (packet instanceof EntityPositionSyncPacket sync && sync.entityId() == player.getEntityId()) {
                assertTrue(START.samePoint(sync.position()));
                positionPackets++;
            } else if (packet instanceof EntityTeleportPacket teleport && teleport.entityId() == player.getEntityId()) {
                assertTrue(START.samePoint(teleport.position()));
                positionPackets++;
            } else if (packet instanceof EntityPositionAndRotationPacket relative && relative.entityId() == player.getEntityId()) {
                assertEquals(0, relative.deltaX());
                assertEquals(0, relative.deltaY());
                assertEquals(0, relative.deltaZ());
                positionPackets++;
            }
        }
        assertTrue(positionPackets > 0, "Observer must receive a position update for this assertion to be meaningful");
    }

    @Test
    void waitsForCorrectionConfirmationThenResumesNormalMovement(Env env) {
        var player = env.createPlayer(env.createEmptyInstance(), START);
        player.setNoGravity(true);
        confirmTeleport(player);
        var correction = new PositionCorrection(ignored -> {});
        correction.initialize(player);
        var events = new AtomicInteger();
        env.process().eventHandler().addListener(PlayerMoveEvent.class, event -> {
            events.incrementAndGet();
            correction.handle(event);
        });

        move(player, new Pos(10, 64, 0));
        assertEquals(1, events.get());

        move(player, new Pos(0.3, 64, 0));
        assertEquals(1, events.get(), "Minestom should hold movement while confirmation is pending");
        assertEquals(START, player.getPosition());

        confirmTeleport(player);
        move(player, new Pos(0.3, 64, 0));
        assertEquals(2, events.get());
        assertEquals(new Pos(0.3, 64, 0), player.getPosition());
        assertEquals(player.getPosition(), correction.authoritativePosition(player));
    }

    @Test
    void sequentialPacketHandlersShareTheTravelBudgetWithoutRebasingEachProposal(Env env) {
        var player = env.createPlayer(env.createEmptyInstance(), START);
        player.setNoGravity(true);
        confirmTeleport(player);
        var correction = new PositionCorrection(ignored -> {});
        correction.initialize(player);
        env.process().eventHandler().addListener(PlayerMoveEvent.class, correction::handle);

        long started = System.nanoTime();
        for (int packet = 1; packet <= 50; packet++) {
            // Confirm each correction so the next proposal also reaches our policy.
            confirmTeleport(player);
            move(player, new Pos(packet * 0.25, 64, 0));
            assertEquals(player.getPosition(), correction.authoritativePosition(player));
        }
        double elapsedSeconds = (System.nanoTime() - started) / 1_000_000_000.0;
        double maximumTravel = MovementValidator.POSITION_TOLERANCE
                + MovementValidator.GROUND_HORIZONTAL_SPEED * MovementValidator.BURST_SECONDS
                + MovementValidator.GROUND_HORIZONTAL_SPEED * elapsedSeconds;

        assertTrue(player.getPosition().x() <= maximumTravel + 1.0e-6,
                "Packet handling reset the bucket; server accepted " + player.getPosition().x()
                        + " blocks with only " + maximumTravel + " blocks of allowance");
    }

    @Test
    void disablingCustomValidationLetsThePacketListenerApplyTheControlMove(Env env) {
        var player = env.createPlayer(env.createEmptyInstance(), START);
        player.setNoGravity(true);
        confirmTeleport(player);
        var correction = new PositionCorrection(ignored -> {});
        correction.initialize(player);
        correction.setValidationEnabled(false);
        env.process().eventHandler().addListener(PlayerMoveEvent.class, correction::handle);
        var requested = new Pos(10, 64, 0);

        move(player, requested);

        assertEquals(requested, player.getPosition());
        assertEquals(requested, correction.authoritativePosition(player));
        assertEquals(player.getLastSentTeleportId(), player.getLastReceivedTeleportId());
    }

    private static void move(Player player, Pos requested) {
        PlayerPositionListener.playerPositionListener(new ClientPlayerPositionPacket(requested, true, false), player);
    }

    private static void confirmTeleport(Player player) {
        PlayerPositionListener.teleportConfirmListener(new ClientTeleportConfirmPacket(player.getLastSentTeleportId()), player);
    }

}
