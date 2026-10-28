package org.example.synchronization;

import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.Player;
import net.minestom.server.event.player.PlayerMoveEvent;
import org.example.validation.MovementValidator;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
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
        log.accept("[MOVEMENT] validation=" + (enabled ? "ON" : "OFF"));
    }

    /** Call after spawn or after a server-authorized teleport completes. */
    public void initialize(Player player) {
        trackers.put(player.getUuid(),
                new Tracker(validator.initialState(player.getPosition(), System.nanoTime())));
    }

    public void forget(Player player) {
        trackers.remove(player.getUuid());
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
            if (event.isCancelled()) {
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
            record(player, tracker, result, event.getNewPosition(), now);
            return result;
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
                        Pos requested, long now) {
        if (result.accepted() && tracker.lastAcceptLog != Long.MIN_VALUE
                && now - tracker.lastAcceptLog < 1_000_000_000L) {
            return;
        }
        if (result.accepted()) {
            tracker.lastAcceptLog = now;
        }
        log.accept(String.format(Locale.ROOT,
                "[MOVEMENT][PACKET] %s player=%s requested=%s authoritative=%s distanceXZ=%.3f allowedXZ=%.3f reason=%s replication=%s correction=%s",
                result.accepted() ? "ACCEPT" : "REJECT", player.getUsername(), format(requested),
                format(result.authoritativePosition()), result.horizontalDistance(), result.allowedHorizontalDistance(),
                result.reason(), result.accepted() ? "allowed" : "blocked",
                result.accepted() ? "none" : "Minestom-cancel+confirmation"));
    }

    private static String format(Pos position) {
        if (position == null) {
            return "null";
        }
        return String.format(Locale.ROOT, "(%.3f,%.3f,%.3f)", position.x(), position.y(), position.z());
    }
}
