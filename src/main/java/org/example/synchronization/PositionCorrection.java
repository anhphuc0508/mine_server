package org.example.synchronization;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.example.validation.MovementValidator;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** Validates a proposed position before Minestom applies it or replicates it to viewers. */
public final class PositionCorrection {
    private final MovementValidator validator = new MovementValidator();
    private final Map<UUID, Tracker> trackers = new ConcurrentHashMap<>();
    private final Consumer<String> log;
    private volatile boolean validationEnabled = true;

    private static final class Tracker {
        private MovementValidator.State state;
        private long lastAcceptLog = Long.MIN_VALUE;
        private boolean teleportPending;
        private CompletableFuture<Void> teleportFuture;

        private Tracker(MovementValidator.State state) {
            this.state = state;
        }
    }

    public PositionCorrection(Consumer<String> log) {
        this.log = log;
    }

    public boolean validationEnabled() {
        return validationEnabled;
    }

    public void setValidationEnabled(boolean enabled) {
        validationEnabled = enabled;
        log.accept("[DEMO1] validation=" + (enabled ? "ON" : "OFF"));
    }

    /** Call after spawn or after a server-authorized teleport completes. */
    public void initialize(Player player) {
        Tracker previous = trackers.put(player.getUuid(),
                new Tracker(validator.initialState(player.getPosition(), System.nanoTime())));
        cancelPending(previous);
    }

    public void forget(Player player) {
        cancelPending(trackers.remove(player.getUuid()));
    }

    public Pos authoritativePosition(Player player) {
        Tracker tracker = trackers.get(player.getUuid());
        if (tracker == null) {
            return player.getPosition();
        }
        synchronized (tracker) {
            return tracker.state.position();
        }
    }

    /**
     * Minestom's packet listener calls this event BEFORE refreshPosition. Cancelling the event
     * keeps the server position and automatically sends the previous position with a new
     * teleport ID; subsequent movement waits for the client's teleport confirmation.
     */
    public MovementValidator.Result handle(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Tracker tracker = tracker(player);
        synchronized (tracker) {
            long now = System.nanoTime();
            MovementValidator.Result result;
            if (tracker.teleportPending) {
                result = pendingResult(tracker);
            } else if (event.isCancelled()) {
                result = new MovementValidator.Result(false, "previous-listener-cancelled", tracker.state.position(),
                        0, tracker.state.horizontalBudget(), tracker.state);
            } else {
                rebaseServerMovement(player, tracker, now);
                result = decide(tracker.state, event.getNewPosition(), now, player.isFlying());
                tracker.state = result.nextState();
            }
            if (!result.accepted()) {
                event.setCancelled(true);
            }
            record(player, tracker, result, event.getNewPosition(), "PACKET", now);
            return result;
        }
    }

    /**
     * Server-side test harness, not a forged movement packet or a local prediction test.
     * Uses the same decision policy, then explicitly teleports because there is no packet
     * listener to apply an accepted position or send a rejected position's correction.
     */
    public MovementValidator.Result simulateMove(Player player, Pos requested) {
        if (player.getInstance() == null) {
            throw new IllegalStateException("The player must be spawned before the movement harness runs");
        }
        Tracker tracker = tracker(player);
        synchronized (tracker) {
            long now = System.nanoTime();
            if (tracker.teleportPending) {
                MovementValidator.Result busy = pendingResult(tracker);
                record(player, tracker, busy, requested, "HARNESS(non-packet)", now);
                return busy;
            }
            rebaseServerMovement(player, tracker, now);
            MovementValidator.State before = tracker.state;
            MovementValidator.Result result = decide(tracker.state, requested, now, player.isFlying());
            MovementValidator.State rollback = result.accepted()
                    ? validator.validate(before, before.position(), now, player.isFlying()).nextState()
                    : result.nextState();
            // Reserve the accepted distance, but the destination is not authoritative until
            // Minestom actually applies the teleport (which may need an asynchronous chunk load).
            tracker.state = atPosition(result.nextState(), before.position());
            tracker.teleportPending = true;
            Pos target = result.authoritativePosition();
            // Vec.ZERO cancels velocity as well, matching Minestom's cancelled-move correction.
            try {
                CompletableFuture<Void> future = player.teleport(target, Vec.ZERO);
                tracker.teleportFuture = future;
                future.whenComplete((ignored, failure) -> completeTeleport(player, tracker, result, rollback, requested, failure));
                if (future.isCompletedExceptionally()) {
                    return failedTeleportResult(tracker);
                }
            } catch (RuntimeException failure) {
                completeTeleport(player, tracker, result, rollback, requested, failure);
                return failedTeleportResult(tracker);
            }
            if (tracker.teleportPending) {
                log.accept("[DEMO1][HARNESS(non-packet)] PENDING player=" + player.getUsername()
                        + " requested=" + format(requested) + " authoritative=" + format(tracker.state.position())
                        + " decision=" + (result.accepted() ? "ACCEPT" : "REJECT")
                        + " reason=" + result.reason());
            }
            return result;
        }
    }

    private void completeTeleport(Player player, Tracker tracker, MovementValidator.Result decision,
                                  MovementValidator.State rollback, Pos requested, Throwable failure) {
        synchronized (tracker) {
            if (trackers.get(player.getUuid()) != tracker) {
                return; // A reset/respawn/disconnect replaced this request.
            }
            tracker.teleportPending = false;
            tracker.teleportFuture = null;
            if (failure == null) {
                tracker.state = atPosition(decision.nextState(), player.getPosition());
                // A rejected correction preserves the refilled/depleted budgets; it is not a reset.
                record(player, tracker, decision, requested, "HARNESS(non-packet)", System.nanoTime());
            } else {
                tracker.state = atPosition(rollback, player.getPosition());
                log.accept("[DEMO1][HARNESS(non-packet)] TELEPORT_FAILED player=" + player.getUsername()
                        + " authoritative=" + format(tracker.state.position())
                        + " reason=" + failure.getClass().getSimpleName());
            }
        }
    }

    private static MovementValidator.State atPosition(MovementValidator.State state, Pos position) {
        return new MovementValidator.State(position, state.updatedAtNanos(), state.horizontalBudget(),
                state.upwardBudget(), state.downwardBudget());
    }

    private static MovementValidator.Result pendingResult(Tracker tracker) {
        return new MovementValidator.Result(false, "harness-teleport-pending", tracker.state.position(),
                0, tracker.state.horizontalBudget(), tracker.state);
    }

    private static MovementValidator.Result failedTeleportResult(Tracker tracker) {
        return new MovementValidator.Result(false, "harness-teleport-failed", tracker.state.position(),
                0, tracker.state.horizontalBudget(), tracker.state);
    }

    private static void cancelPending(Tracker tracker) {
        if (tracker != null) {
            synchronized (tracker) {
                if (tracker.teleportFuture != null) {
                    tracker.teleportFuture.cancel(false);
                }
            }
        }
    }

    private Tracker tracker(Player player) {
        return trackers.computeIfAbsent(player.getUuid(), ignored ->
                new Tracker(validator.initialState(player.getPosition(), System.nanoTime())));
    }

    private MovementValidator.Result decide(MovementValidator.State state, Pos requested, long now, boolean flying) {
        MovementValidator.Result result = validator.validate(state, requested, now, flying);
        if (!validationEnabled && !result.reason().equals("non-finite-position")
                && !result.reason().equals("outside-world-bounds")) {
            return new MovementValidator.Result(true, "validation-disabled", requested,
                    result.horizontalDistance(), result.allowedHorizontalDistance(),
                    validator.initialState(requested, now));
        }
        return result;
    }

    private void rebaseServerMovement(Player player, Tracker tracker, long now) {
        Pos current = player.getPosition();
        if (!current.samePoint(tracker.state.position())) {
            // Client proposals cannot reach getPosition() before this listener. A difference
            // here is a server teleport/respawn, an earlier cancellation, or server movement.
            tracker.state = validator.initialState(current, now);
        }
    }

    private void record(Player player, Tracker tracker, MovementValidator.Result result,
                        Pos requested, String source, long now) {
        if (result.accepted() && source.equals("PACKET") && tracker.lastAcceptLog != Long.MIN_VALUE
                && now - tracker.lastAcceptLog < 1_000_000_000L) {
            return;
        }
        if (result.accepted()) {
            tracker.lastAcceptLog = now;
        }
        log.accept(String.format(Locale.ROOT,
                "[DEMO1][%s] %s player=%s requested=%s authoritative=%s distanceXZ=%.3f allowedXZ=%.3f reason=%s replication=%s correction=%s",
                source, result.accepted() ? "ACCEPT" : "REJECT", player.getUsername(), format(requested),
                format(result.authoritativePosition()), result.horizontalDistance(), result.allowedHorizontalDistance(),
                result.reason(), result.accepted() ? "allowed" : "blocked",
                result.accepted() ? "none" : source.equals("PACKET") ? "Minestom-cancel+confirmation"
                        : result.reason().equals("harness-teleport-pending") ? "already-in-flight" : "server-teleport"));
    }

    private static String format(Pos position) {
        if (position == null) {
            return "null";
        }
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", position.x(), position.y(), position.z());
    }
}
