package org.example.validation;

import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MovementValidatorTest {
    private static final Pos START = new Pos(0, 64, 0);
    private static final long TICK = 50_000_000L;
    private final MovementValidator validator = new MovementValidator();

    @Test
    void acceptsARegularSprintPathAcrossManyTicks() {
        var state = validator.initialState(START, 0);

        for (int tick = 1; tick <= 200; tick++) {
            var requested = new Pos(tick * 0.30, 64, 0);
            var result = validator.validate(state, requested, tick * TICK, false);
            assertTrue(result.accepted(), "Normal step rejected at tick " + tick);
            assertEquals(requested, result.authoritativePosition());
            state = result.nextState();
        }
    }

    @Test
    void rejectedTeleportKeepsTheLastAcceptedPosition() {
        var state = validator.initialState(START, 0);
        var rejected = validator.validate(state, new Pos(100, 64, 0), TICK, false);

        assertFalse(rejected.accepted());
        assertEquals(START, rejected.authoritativePosition());
        assertEquals(START, rejected.nextState().position());

        var normal = validator.validate(rejected.nextState(), new Pos(0.3, 64, 0), TICK, false);
        assertTrue(normal.accepted());
        assertEquals(new Pos(0.3, 64, 0), normal.authoritativePosition());
    }

    @Test
    void repeatedRejectedPacketsCannotCreditTheSameElapsedTimeTwice() {
        var first = validator.validate(validator.initialState(START, 0), new Pos(2.5, 64, 0), 0, false);
        assertTrue(first.accepted());
        var state = first.nextState();

        for (int packet = 0; packet < 100; packet++) {
            var result = validator.validate(state, new Pos(3.25, 64, 0), TICK, false);
            assertFalse(result.accepted(), "Repeated rejection minted movement budget at packet " + packet);
            assertEquals(new Pos(2.5, 64, 0), result.authoritativePosition());
            state = result.nextState();
        }

        // Real additional elapsed time can replenish the budget.
        assertTrue(validator.validate(state, new Pos(3.25, 64, 0), 2 * TICK, false).accepted());
    }

    @Test
    void manyPacketsInOneTickShareOneTravelBudget() {
        var state = validator.initialState(START, 0);
        int accepted = 0;

        for (int packet = 1; packet <= 20; packet++) {
            var result = validator.validate(state, new Pos(packet * 0.25, 64, 0), 0, false);
            if (result.accepted()) accepted++;
            state = result.nextState();
        }

        assertTrue(accepted > 0);
        assertTrue(state.position().x() < 3.0, "A burst of small packets bypassed the travel limit");
        assertTrue(accepted < 20);
    }

    @Test
    void rotationPacketsCannotResetTheRemainingTravelBudget() {
        var spent = validator.validate(validator.initialState(START, 0), new Pos(2.5, 64, 0), 0, false);
        assertTrue(spent.accepted());
        var state = spent.nextState();

        for (int packet = 0; packet < 50; packet++) {
            var result = validator.validate(state, new Pos(2.5, 64, 0, packet * 3, 10), 0, false);
            assertTrue(result.accepted());
            state = result.nextState();
        }

        assertFalse(validator.validate(state, new Pos(3.0, 64, 0), 0, false).accepted());
    }

    @Test
    void aLongIdlePeriodCannotAccumulateAnUnlimitedTeleportBudget() {
        var result = validator.validate(validator.initialState(START, 0),
                new Pos(100, 64, 0), 3_600_000_000_000L, false);

        assertFalse(result.accepted());
        assertEquals(START, result.authoritativePosition());
    }

    @Test
    void rejectedVerticalMotionDoesNotChangeTheAuthoritativePosition() {
        var state = validator.initialState(START, 0);
        var upward = validator.validate(state, new Pos(0, 90, 0), TICK, false);
        assertFalse(upward.accepted());
        assertEquals(START, upward.authoritativePosition());

        var downward = validator.validate(upward.nextState(), new Pos(0, -100, 0), TICK, false);
        assertFalse(downward.accepted());
        assertEquals(START, downward.authoritativePosition());
    }

    @Test
    void acceptsNormalJumpAndFallSteps() {
        var jump = validator.validate(validator.initialState(START, 0), new Pos(0.3, 65.2, 0), TICK, false);
        assertTrue(jump.accepted());

        var fall = validator.validate(jump.nextState(), new Pos(0.6, 61.2, 0), 2 * TICK, false);
        assertTrue(fall.accepted());
    }

    @Test
    void flyingUsesALargerSustainedMovementAllowance() {
        var flying = validator.initialState(START, 0);
        var walking = validator.initialState(START, 0);
        boolean groundRejected = false;

        for (int tick = 1; tick <= 40; tick++) {
            var position = new Pos(tick, 64, 0);
            var flyResult = validator.validate(flying, position, tick * TICK, true);
            assertTrue(flyResult.accepted(), "Flying step rejected at tick " + tick);
            flying = flyResult.nextState();

            var walkResult = validator.validate(walking, position, tick * TICK, false);
            groundRejected |= !walkResult.accepted();
            walking = walkResult.nextState();
        }

        assertTrue(groundRejected);
    }

    @Test
    void rejectsNonFiniteCoordinatesAndViewAngles() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (Pos requested : new Pos[]{
                    new Pos(invalid, 64, 0), new Pos(0, invalid, 0), new Pos(0, 64, invalid),
                    new Pos(0, 64, 0, (float) invalid, 0)}) {
                var result = validator.validate(validator.initialState(START, 0), requested, TICK, false);
                assertFalse(result.accepted());
                assertEquals(START, result.authoritativePosition());
            }
        }
        // Pos clamps infinite pitch to +/-90 before validation; NaN remains observable here.
        assertFalse(validator.validate(validator.initialState(START, 0),
                new Pos(0, 64, 0, 0, Float.NaN), TICK, false).accepted());
    }

    @Test
    void rejectsPositionsOutsideWorldBoundsEvenWhenCloseToTheCurrentPosition() {
        for (double direction : new double[]{-1, 1}) {
            var edge = new Pos(direction * 30_000_000, 64, 0);
            var requested = edge.add(direction, 0, 0);
            var result = validator.validate(validator.initialState(edge, 0), requested, TICK, false);

            assertFalse(result.accepted());
            assertEquals(edge, result.authoritativePosition());
        }
    }
}
