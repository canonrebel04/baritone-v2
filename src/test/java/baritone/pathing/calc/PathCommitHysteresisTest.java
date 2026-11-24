package baritone.pathing.calc;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PathCommitHysteresisTest {

    @Test
    public void keepsCommittedWhenNewPathIsOnlySlightlyCheaper() {
        assertTrue(PathCommit.shouldKeepCommitted(100.0, 90.0, 25.0));
    }

    @Test
    public void replacesCommittedWhenNewPathBeatsMargin() {
        assertFalse(PathCommit.shouldKeepCommitted(100.0, 70.0, 25.0));
    }

    @Test
    public void keepsCommittedWhenNewPathIsEqualOrWorse() {
        assertTrue(PathCommit.shouldKeepCommitted(80.0, 80.0, 25.0));
        assertTrue(PathCommit.shouldKeepCommitted(80.0, 100.0, 25.0));
    }

    @Test
    public void rejectsInvalidCosts() {
        assertFalse(PathCommit.shouldKeepCommitted(Double.POSITIVE_INFINITY, 50.0, 25.0));
        assertFalse(PathCommit.shouldKeepCommitted(50.0, Double.NaN, 25.0));
        assertFalse(PathCommit.shouldKeepCommitted(-1.0, 10.0, 25.0));
    }
}
