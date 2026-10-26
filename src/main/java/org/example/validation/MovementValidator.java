package org.example.validation;

import net.minestom.server.coordinate.Pos;

/**
 * A deliberately small movement policy for the demo arena, not a complete physics anti-cheat.
 * A token bucket limits total travelled distance, including several packets in one tick.
 * Separate vertical budgets permit normal jumps and terminal-velocity falling.
 */
public final class MovementValidator {
    public static final double GROUND_HORIZONTAL_SPEED = 8.0;
    public static final double FLYING_HORIZONTAL_SPEED = 32.0;
    public static final double GROUND_UPWARD_SPEED = 12.0;
    public static final double FLYING_UPWARD_SPEED = 40.0;
    public static final double DOWNWARD_SPEED = 85.0;
    public static final double BURST_SECONDS = 0.25;
    public static final double POSITION_TOLERANCE = 0.75;
    public static final double MAX_COORDINATE = 30_000_000.0;
    private static final double EPSILON = 1.0e-9;

    public record State(Pos position, long updatedAtNanos, double horizontalBudget,
                        double upwardBudget, double downwardBudget) {
    }

    public record Result(boolean accepted, String reason, Pos authoritativePosition,
                         double horizontalDistance, double allowedHorizontalDistance,
                         State nextState) {
    }

    public State initialState(Pos authoritativePosition, long nowNanos) {
        return new State(authoritativePosition, nowNanos,
                capacity(GROUND_HORIZONTAL_SPEED), capacity(GROUND_UPWARD_SPEED), capacity(DOWNWARD_SPEED));
    }

    /**
     * Always retain nextState, even after rejection: otherwise the same elapsed time could be
     * credited repeatedly. Rotation-only packets preserve the remaining distance budgets.
     */
    public Result validate(State state, Pos requested, long nowNanos, boolean flying) {
        double horizontalSpeed = flying ? FLYING_HORIZONTAL_SPEED : GROUND_HORIZONTAL_SPEED;
        double upwardSpeed = flying ? FLYING_UPWARD_SPEED : GROUND_UPWARD_SPEED;
        long timestamp = Math.max(nowNanos, state.updatedAtNanos());
        // Capacity, rather than packet frequency, caps the distance banked while idle.
        double seconds = Math.max(0.0, (timestamp - state.updatedAtNanos()) / 1_000_000_000.0);
        double horizontal = refill(state.horizontalBudget(), horizontalSpeed, seconds);
        double upward = refill(state.upwardBudget(), upwardSpeed, seconds);
        double downward = refill(state.downwardBudget(), DOWNWARD_SPEED, seconds);
        State refilled = new State(state.position(), timestamp, horizontal, upward, downward);

        if (!isFinite(requested)) {
            return reject("non-finite-position", refilled, Double.NaN);
        }
        if (Math.abs(requested.x()) > MAX_COORDINATE || Math.abs(requested.y()) > MAX_COORDINATE
                || Math.abs(requested.z()) > MAX_COORDINATE) {
            return reject("outside-world-bounds", refilled, Double.NaN);
        }

        double distance = Math.hypot(requested.x() - state.position().x(), requested.z() - state.position().z());
        double deltaY = requested.y() - state.position().y();
        if (distance > horizontal + EPSILON) {
            return reject("horizontal-speed", refilled, distance);
        }
        if (deltaY > upward + EPSILON) {
            return reject("upward-speed", refilled, distance);
        }
        if (-deltaY > downward + EPSILON) {
            return reject("downward-speed", refilled, distance);
        }

        State next = new State(requested, timestamp, Math.max(0, horizontal - distance),
                Math.max(0, upward - Math.max(0, deltaY)), Math.max(0, downward - Math.max(0, -deltaY)));
        return new Result(true, "within-budget", requested, distance, horizontal, next);
    }

    public static boolean isFinite(Pos position) {
        return position != null && Double.isFinite(position.x()) && Double.isFinite(position.y())
                && Double.isFinite(position.z()) && Float.isFinite(position.yaw()) && Float.isFinite(position.pitch());
    }

    private static Result reject(String reason, State state, double distance) {
        return new Result(false, reason, state.position(), distance, state.horizontalBudget(), state);
    }

    private static double capacity(double speed) {
        return POSITION_TOLERANCE + speed * BURST_SECONDS;
    }

    private static double refill(double remaining, double speed, double seconds) {
        return Math.min(capacity(speed), remaining + speed * seconds);
    }
}
