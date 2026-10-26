package org.example.validation;

import net.minestom.server.collision.BoundingBox;
import net.minestom.server.coordinate.Pos;
import net.minestom.server.coordinate.Vec;
import net.minestom.server.entity.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CombatValidatorTest {
    private static final Pos ATTACKER = new Pos(0, 64, 0);
    private static final BoundingBox PLAYER_BOX = new BoundingBox(0.6, 1.8, 0.6);

    @Test
    void measuresReachToTheNearestSurfaceRatherThanTheTargetCenter() {
        var result = validate(new Pos(3.2, 64, 0), 3.0);

        assertTrue(result.allowed());
        assertEquals(2.9, result.distance(), 1.0e-9);
    }

    @Test
    void acceptsTheReachBoundaryAndRejectsBeyondIt() {
        assertTrue(validate(new Pos(3.3, 64, 0), 3.0).allowed());
        assertFalse(validate(new Pos(3.3001, 64, 0), 3.0).allowed());
    }

    @Test
    void includesEyeHeightAndVerticalDistance() {
        var result = validate(new Pos(2.7, 67.82, 0), 3.0);

        assertEquals(Math.hypot(2.4, 2.2), result.distance(), 1.0e-9);
        assertFalse(result.allowed());
    }

    @Test
    void usesDepthAndOffsetForAnAsymmetricBox() {
        var box = new BoundingBox(2, 3, 6, new Vec(-2, 0, -6));
        var result = CombatValidator.validate(
                new Pos(7, 64, 2), 1.62, new Pos(10, 64, 10), box,
                GameMode.SURVIVAL, 3.0, true);

        // The nearest surface is (8, 65.62, 4), two blocks away in Z.
        assertEquals(Math.sqrt(5), result.distance(), 1.0e-9);
        assertTrue(result.allowed());
    }

    @Test
    void eyeInsideTargetBoxHasZeroDistance() {
        var result = validate(ATTACKER, 0.0);

        assertEquals(0.0, result.distance());
        assertTrue(result.allowed());
    }

    @Test
    void creativeUsesTheSuppliedAttributeRangeWithoutAnExtraBonus() {
        var creative = CombatValidator.validate(ATTACKER, 1.62,
                new Pos(4.8, 64, 0), PLAYER_BOX, GameMode.CREATIVE, 5.0, true);
        var shortAttribute = CombatValidator.validate(ATTACKER, 1.62,
                new Pos(4.8, 64, 0), PLAYER_BOX, GameMode.CREATIVE, 3.0, true);

        assertTrue(creative.allowed());
        assertEquals(5.0, creative.range());
        assertFalse(shortAttribute.allowed());
    }

    @Test
    void spectatorCannotAttackEvenWhenCustomReachIsDisabled() {
        for (boolean enforceReach : new boolean[]{true, false}) {
            var result = CombatValidator.validate(ATTACKER, 1.62,
                    new Pos(1, 64, 0), PLAYER_BOX, GameMode.SPECTATOR, 5.0, enforceReach);
            assertFalse(result.allowed());
        }
    }

    @Test
    void controlModeAllowsDistantFiniteGeometry() {
        var result = CombatValidator.validate(ATTACKER, 1.62,
                new Pos(10, 64, 0), PLAYER_BOX, GameMode.SURVIVAL, 3.0, false);

        assertTrue(result.allowed());
        assertEquals(9.7, result.distance(), 1.0e-9);
    }

    @Test
    void rejectsNonFiniteGeometryInBothModes() {
        for (boolean enforceReach : new boolean[]{true, false}) {
            for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                assertFalse(CombatValidator.validate(new Pos(invalid, 64, 0), 1.62,
                        ATTACKER, PLAYER_BOX, GameMode.SURVIVAL, 3.0, enforceReach).allowed());
                assertFalse(CombatValidator.validate(ATTACKER, invalid,
                        ATTACKER, PLAYER_BOX, GameMode.SURVIVAL, 3.0, enforceReach).allowed());
                assertFalse(CombatValidator.validate(ATTACKER, 1.62,
                        new Pos(0, 64, invalid), PLAYER_BOX, GameMode.SURVIVAL, 3.0, enforceReach).allowed());
                assertFalse(CombatValidator.validate(ATTACKER, 1.62,
                        ATTACKER, PLAYER_BOX, GameMode.SURVIVAL, invalid, enforceReach).allowed());
            }
        }
    }

    @Test
    void rejectsInvertedBoxesAndNegativeRanges() {
        var inverted = new BoundingBox(new Vec(1, 0, 0), new Vec(-1, 1, 1));

        assertFalse(CombatValidator.validate(ATTACKER, 1.62,
                ATTACKER, inverted, GameMode.SURVIVAL, 3.0, true).allowed());
        assertFalse(validate(ATTACKER, -1).allowed());
    }

    private static CombatValidator.Result validate(Pos target, double range) {
        return CombatValidator.validate(ATTACKER, 1.62, target, PLAYER_BOX,
                GameMode.SURVIVAL, range, true);
    }
}
