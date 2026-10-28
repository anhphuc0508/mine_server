package org.example.validation;

import net.minestom.server.coordinate.Pos;

/** A horizontal world-region rule for the demo; this is not a movement anti-cheat. */
public final class BoundaryValidator {
    private BoundaryValidator() {
    }

    public record Result(boolean allowed, String reason, double deltaX, double deltaZ) {
    }

    /** The square includes its edges and corners; finite Y values do not affect membership. */
    public static Result validate(Pos origin, double radius, Pos requested) {
        if (!Double.isFinite(radius) || radius <= 0) {
            return new Result(false, "invalid-radius", Double.NaN, Double.NaN);
        }
        if (!MovementValidator.isFinite(origin)) {
            return new Result(false, "non-finite-origin", Double.NaN, Double.NaN);
        }
        if (!MovementValidator.isFinite(requested)) {
            return new Result(false, "non-finite-position", Double.NaN, Double.NaN);
        }
        double deltaX = requested.x() - origin.x();
        double deltaZ = requested.z() - origin.z();
        boolean allowed = Math.abs(deltaX) <= radius && Math.abs(deltaZ) <= radius;
        return new Result(allowed, allowed ? "inside-boundary" : "outside-boundary", deltaX, deltaZ);
    }
}
