package org.example.validation;

import net.minestom.server.coordinate.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundaryValidatorTest {
    private static final Pos ORIGIN = new Pos(0, 64, 0);
    private static final double RADIUS = 10;

    @Test
    void includesEveryEdgeAndCornerOfTheSquare() {
        for (double x : new double[]{-RADIUS, 0, RADIUS}) {
            for (double z : new double[]{-RADIUS, 0, RADIUS}) {
                assertTrue(BoundaryValidator.validate(ORIGIN, RADIUS, new Pos(x, 64, z)).allowed(),
                        "Inclusive square rejected x=" + x + ", z=" + z);
            }
        }
    }

    @Test
    void cornersOutsideACircularRadiusStillBelongToTheSquare() {
        var corner = new Pos(RADIUS, 64, RADIUS);

        assertTrue(Math.hypot(corner.x(), corner.z()) > RADIUS);
        assertTrue(BoundaryValidator.validate(ORIGIN, RADIUS, corner).allowed());
    }

    @Test
    void rejectsTheFirstRepresentablePositionOutsideEachEdge() {
        double outside = Math.nextUp(RADIUS);
        for (Pos requested : new Pos[]{
                new Pos(outside, 64, 0), new Pos(-outside, 64, 0),
                new Pos(0, 64, outside), new Pos(0, 64, -outside),
                new Pos(outside, 64, RADIUS), new Pos(RADIUS, 64, outside)}) {
            assertFalse(BoundaryValidator.validate(ORIGIN, RADIUS, requested).allowed(),
                    "Accepted position just outside the square: " + requested);
        }
    }

    @Test
    void measuresTheSquareFromTheConfiguredOrigin() {
        var origin = new Pos(-120.5, 91, 73.25);

        assertTrue(BoundaryValidator.validate(origin, RADIUS, new Pos(-130.5, 91, 83.25)).allowed());
        assertTrue(BoundaryValidator.validate(origin, RADIUS, new Pos(-110.5, 91, 63.25)).allowed());
        assertFalse(BoundaryValidator.validate(origin, RADIUS, new Pos(-130.5001, 91, 73.25)).allowed());
        assertFalse(BoundaryValidator.validate(origin, RADIUS, new Pos(-120.5, 91, 83.2501)).allowed());
        assertFalse(BoundaryValidator.validate(origin, RADIUS, ORIGIN).allowed());
    }

    @Test
    void reportsSignedOffsetsForAcceptedAndRejectedPositions() {
        var origin = new Pos(-120.5, 91, 73.25);
        var accepted = BoundaryValidator.validate(origin, RADIUS, new Pos(-130.5, 91, 83.25));

        assertTrue(accepted.allowed());
        assertEquals("inside-boundary", accepted.reason());
        assertEquals(-10, accepted.deltaX());
        assertEquals(10, accepted.deltaZ());

        var rejected = BoundaryValidator.validate(origin, RADIUS, new Pos(-109.5, 91, 61.25));
        assertFalse(rejected.allowed());
        assertEquals("outside-boundary", rejected.reason());
        assertEquals(11, rejected.deltaX());
        assertEquals(-12, rejected.deltaZ());
    }

    @Test
    void rejectsFiniteCoordinatesWhoseOffsetOverflows() {
        var origin = new Pos(Double.MAX_VALUE, 64, Double.MAX_VALUE);
        var requested = new Pos(-Double.MAX_VALUE, 64, -Double.MAX_VALUE);

        assertFalse(BoundaryValidator.validate(origin, Double.MAX_VALUE, requested).allowed());
    }

    @Test
    void altitudeAndViewAnglesDoNotRestrictFinitePositionsInsideTheSquare() {
        for (double y : new double[]{-10_000, 64, 10_000}) {
            for (float yaw : new float[]{-180, 0, 180}) {
                assertTrue(BoundaryValidator.validate(ORIGIN, RADIUS,
                        new Pos(RADIUS, y, -RADIUS, yaw, 90)).allowed());
                assertTrue(BoundaryValidator.validate(ORIGIN, RADIUS,
                        new Pos(-RADIUS, y, RADIUS, yaw, -90)).allowed());
            }
        }
    }

    @Test
    void rejectsNonFiniteRequestedCoordinatesAndViewAngles() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (Pos requested : new Pos[]{
                    new Pos(invalid, 64, 0), new Pos(0, invalid, 0), new Pos(0, 64, invalid),
                    new Pos(0, 64, 0, (float) invalid, 0)}) {
                assertFalse(BoundaryValidator.validate(ORIGIN, RADIUS, requested).allowed());
            }
        }
        // Pos clamps infinite pitch to +/-90; NaN remains observable by the validator.
        assertFalse(BoundaryValidator.validate(ORIGIN, RADIUS,
                new Pos(0, 64, 0, 0, Float.NaN)).allowed());
    }

    @Test
    void rejectsInvalidOriginsEvenWhenTheRequestedPositionIsFinite() {
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            for (Pos origin : new Pos[]{
                    new Pos(invalid, 64, 0), new Pos(0, invalid, 0), new Pos(0, 64, invalid),
                    new Pos(0, 64, 0, (float) invalid, 0)}) {
                assertFalse(BoundaryValidator.validate(origin, RADIUS, ORIGIN).allowed());
            }
        }
        assertFalse(BoundaryValidator.validate(new Pos(0, 64, 0, 0, Float.NaN),
                RADIUS, ORIGIN).allowed());
    }

    @Test
    void rejectsNonPositiveAndNonFiniteRadii() {
        for (double radius : new double[]{0, -0.0, -1, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(BoundaryValidator.validate(ORIGIN, radius, ORIGIN).allowed(),
                    "Accepted invalid radius " + radius);
        }
    }

    @Test
    void rejectsMissingOriginOrRequestedPosition() {
        assertFalse(BoundaryValidator.validate(null, RADIUS, ORIGIN).allowed());
        assertFalse(BoundaryValidator.validate(ORIGIN, RADIUS, null).allowed());
        assertFalse(BoundaryValidator.validate(null, RADIUS, null).allowed());
    }
}
