package org.example.synchronization;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import net.minestom.server.instance.Instance;
import org.example.validation.BoundaryValidator;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * An opt-in world-region rule. Leaving the fixed square returns the player to the position
 * captured when it was enabled, rather than the most recently accepted movement position.
 * This module does not claim to detect cheating or to simulate Minecraft's movement physics.
 */
public final class MovementBoundary {
    public record Zone(Pos origin, double radius) {
    }

    private static final class Session {
        private final Zone zone;
        private final Instance instance;
        private boolean rollbackPending;
        private CompletableFuture<Void> rollbackFuture;

        private Session(Zone zone, Instance instance) {
            this.zone = zone;
            this.instance = instance;
        }
    }

    private final PositionCorrection movement;
    private final Consumer<String> log;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public MovementBoundary(PositionCorrection movement, Consumer<String> log) {
        this.movement = Objects.requireNonNull(movement);
        this.log = Objects.requireNonNull(log);
    }

    /** Enables the rule using the player's current server position as its fixed initial origin. */
    public void enable(Player player, double radius) {
        Pos origin = player.getPosition();
        BoundaryValidator.Result validation = BoundaryValidator.validate(origin, radius, origin);
        if (!validation.allowed()) {
            throw new IllegalArgumentException("Cannot enable boundary: " + validation.reason());
        }
        Instance instance = player.getInstance();
        if (instance == null) {
            throw new IllegalStateException("The player must be spawned before enabling the boundary");
        }
        // Enabling establishes a new starting state for movement validation.
        movement.initialize(player);
        Session session = new Session(new Zone(origin, radius), instance);
        cancelPending(sessions.put(player.getUuid(), session));
        log.accept("[BOUNDARY] ENABLE player=" + player.getUsername() + " origin=" + format(origin)
                + " radius=" + radius + " shape=inclusive-square return=INITIAL");
    }

    public void disable(Player player) {
        Session previous = sessions.remove(player.getUuid());
        cancelPending(previous);
        if (previous != null) {
            log.accept("[BOUNDARY] DISABLE player=" + player.getUsername());
        }
    }

    public boolean isEnabled(Player player) {
        return sessions.containsKey(player.getUuid());
    }

    /** Returns null when the rule is disabled for this player. */
    public Zone zone(Player player) {
        Session session = sessions.get(player.getUuid());
        return session == null ? null : session.zone;
    }

    public void forget(Player player) {
        cancelPending(sessions.remove(player.getUuid()));
    }

    /**
     * Returns true if this rule consumed the event. Invoke the normal movement validator only
     * when this returns false, so a boundary rollback cannot be replaced by its last-valid fix.
     */
    public boolean handle(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Session session = sessions.get(player.getUuid());
        if (session == null) {
            return false;
        }
        synchronized (session) {
            if (sessions.get(player.getUuid()) != session) {
                return false;
            }
            if (player.getInstance() != session.instance) {
                sessions.remove(player.getUuid(), session);
                cancelPending(session);
                log.accept("[BOUNDARY] DISABLE player=" + player.getUsername() + " reason=instance-changed");
                return false;
            }
            if (session.rollbackPending) {
                event.setCancelled(true);
                log.accept("[BOUNDARY] REJECT player=" + player.getUsername()
                        + " reason=rollback-pending requested=" + format(event.getNewPosition())
                        + " origin=" + format(session.zone.origin()) + " replication=blocked");
                return true;
            }
            if (event.isCancelled()) {
                return true;
            }
            BoundaryValidator.Result validation = BoundaryValidator.validate(
                    session.zone.origin(), session.zone.radius(), event.getNewPosition());
            if (validation.allowed()) {
                return false;
            }

            event.setCancelled(true);
            log.accept("[BOUNDARY] REJECT player=" + player.getUsername() + " reason=" + validation.reason()
                    + " requested=" + format(event.getNewPosition()) + " lastValid=" + format(player.getPosition())
                    + " origin=" + format(session.zone.origin()) + " return=INITIAL replication=blocked");

            Pos origin = session.zone.origin();
            if (player.getPosition().equals(origin)) {
                // Minestom already corrects a cancelled event to the unchanged current position.
                // Explicitly teleporting to that identical position here would send two fixes.
                player.setVelocity(Vec.ZERO);
                movement.initialize(player);
                log.accept("[BOUNDARY] ROLLBACK player=" + player.getUsername() + " origin=" + format(origin)
                        + " authoritative=" + format(player.getPosition()) + " correction=Minestom-cancel+confirmation");
                return true;
            }

            session.rollbackPending = true;
            try {
                CompletableFuture<Void> future = player.teleport(origin, Vec.ZERO);
                session.rollbackFuture = future;
                future.whenComplete((ignored, failure) -> completeRollback(player, session, failure));
            } catch (RuntimeException failure) {
                completeRollback(player, session, failure);
            }
            if (session.rollbackPending) {
                log.accept("[BOUNDARY] ROLLBACK_PENDING player=" + player.getUsername()
                        + " authoritative=" + format(player.getPosition()) + " origin=" + format(origin));
            }
            return true;
        }
    }

    private void completeRollback(Player player, Session session, Throwable failure) {
        synchronized (session) {
            if (sessions.get(player.getUuid()) != session) {
                return; // Disable/reset/disconnect replaced this request.
            }
            session.rollbackPending = false;
            session.rollbackFuture = null;
            if (player.getInstance() != session.instance) {
                sessions.remove(player.getUuid(), session);
                return;
            }
            movement.initialize(player);
            if (failure == null) {
                log.accept("[BOUNDARY] ROLLBACK player=" + player.getUsername()
                        + " origin=" + format(session.zone.origin()) + " authoritative=" + format(player.getPosition())
                        + " correction=initial-origin-teleport+confirmation");
            } else {
                log.accept("[BOUNDARY] ROLLBACK_FAILED player=" + player.getUsername()
                        + " authoritative=" + format(player.getPosition())
                        + " reason=" + failure.getClass().getSimpleName());
            }
        }
    }

    private static void cancelPending(Session session) {
        if (session != null) {
            synchronized (session) {
                if (session.rollbackFuture != null) {
                    session.rollbackFuture.cancel(false);
                }
            }
        }
    }

    private static String format(Pos position) {
        return position == null ? "null"
                : String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", position.x(), position.y(), position.z());
    }
}
