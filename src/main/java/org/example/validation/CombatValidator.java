package org.example.validation;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.entity.GameMode;

/** Pure reach calculation, separate from Minestom's packet interaction filter. */
public final class CombatValidator {
    private static final double DISTANCE_EPSILON = 1.0e-6;

    private CombatValidator() {
    }

    public static Result validate(Pos attackerPosition, double eyeHeight,
                                  Pos targetPosition, BoundingBox targetBox,
                                  GameMode gameMode, double interactionRange,
                                  boolean enforceReach) {
        if (attackerPosition == null || targetPosition == null || targetBox == null || gameMode == null) {
            return new Result(false, "MISSING_STATE", Double.NaN, interactionRange);
        }
        if (!finite(attackerPosition.x(), attackerPosition.y(), attackerPosition.z(),
                targetPosition.x(), targetPosition.y(), targetPosition.z(), eyeHeight, interactionRange,
                targetBox.minX(), targetBox.minY(), targetBox.minZ(),
                targetBox.maxX(), targetBox.maxY(), targetBox.maxZ())
                || eyeHeight < 0 || interactionRange < 0
                || targetBox.minX() > targetBox.maxX()
                || targetBox.minY() > targetBox.maxY()
                || targetBox.minZ() > targetBox.maxZ()) {
            return new Result(false, "INVALID_GEOMETRY", Double.NaN, interactionRange);
        }

        double eyeX = attackerPosition.x();
        double eyeY = attackerPosition.y() + eyeHeight;
        double eyeZ = attackerPosition.z();
        // Use the actual box offsets and depth, including boxes which are not centered.
        double nearestX = clamp(eyeX, targetPosition.x() + targetBox.minX(), targetPosition.x() + targetBox.maxX());
        double nearestY = clamp(eyeY, targetPosition.y() + targetBox.minY(), targetPosition.y() + targetBox.maxY());
        double nearestZ = clamp(eyeZ, targetPosition.z() + targetBox.minZ(), targetPosition.z() + targetBox.maxZ());
        double dx = eyeX - nearestX;
        double dy = eyeY - nearestY;
        double dz = eyeZ - nearestZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (!Double.isFinite(distance)) {
            return new Result(false, "INVALID_GEOMETRY", distance, interactionRange);
        }
        if (gameMode == GameMode.SPECTATOR) {
            return new Result(false, "SPECTATOR_ATTACKER", distance, interactionRange);
        }
        if (!enforceReach) {
            return new Result(true, "CUSTOM_REACH_DISABLED", distance, interactionRange);
        }
        // Creative's reach modifier is already represented by ENTITY_INTERACTION_RANGE.
        boolean allowed = distance <= interactionRange + DISTANCE_EPSILON;
        return new Result(allowed, allowed ? "IN_REACH" : "OUT_OF_REACH", distance, interactionRange);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }

    private static boolean finite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    public record Result(boolean allowed, String reason, double distance, double range) {
    }
}
