package ch.bissbert.mortar;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BallisticsTest {
    @Test void trajectoriesHitEndpointWhileDescendingAtDifferentElevationsAndRanges() {
        for (double gravity : new double[]{.0001, .01, .5})
            for (double dx : new double[]{0, 1, 512, -2048})
                for (double dy : new double[]{-250, 0, 250}) {
                    Ballistics.Solution solution = Ballistics.solve(dx, dy, 93, gravity, 64);
                    double x = 0, y = 0, z = 0, vy = solution.y(), apex = 0;
                    for (int tick = 0; tick < solution.flightTicks(); tick++) {
                        x += solution.x(); y += vy; z += solution.z(); vy -= gravity; apex = Math.max(apex, y);
                    }
                    assertEquals(dx, x, 1e-6); assertEquals(dy, y, 1e-6); assertEquals(93, z, 1e-6);
                    assertTrue(vy < 0, "Target must be reached from above");
                    assertTrue(apex >= Math.max(0, dy) + 63.5, "Requested apex clearance");
                }
    }

    @Test void flightCanExceedBuildCeilingAndFormerLifetimeLimits() {
        Ballistics.Solution solution = Ballistics.solve(2048, 0, 0, .0001, 256);
        assertTrue(solution.flightTicks() > 1200);
        double y = 300, vy = solution.y(), peak = y;
        for (int i = 0; i < solution.flightTicks(); i++) { y += vy; vy -= .0001; peak = Math.max(peak, y); }
        assertTrue(peak > 550); assertEquals(300, y, 1e-6);
    }

    @Test void zeroGravityAndNonFiniteInputAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Ballistics.solve(1, 0, 1, 0, 64));
        assertThrows(IllegalArgumentException.class, () -> Ballistics.solve(Double.NaN, 0, 1, .01, 64));
        assertThrows(IllegalArgumentException.class, () -> Ballistics.solve(1, 0, 1, .01, Double.POSITIVE_INFINITY));
    }
}
