package com.cardroid.app.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class ReleaseVersionTest {
    @Test public void semverPrecedenceAndBuildMetadata() {
        assertEquals(0, ReleaseVersion.compare("v1.2.3+build.1", "1.2.3+other"));
        assertTrue(ReleaseVersion.compare("1.2.3", "1.2.3-rc.9") > 0);
        assertTrue(ReleaseVersion.compare("1.2.3-rc.10", "1.2.3-rc.2") > 0);
        assertTrue(ReleaseVersion.compare("0.1.1", "0.2.0") < 0);
        assertTrue(ReleaseVersion.compare("999999999999999999999.0.0", "2.0.0") > 0);
    }
    @Test public void rejectsUnknownAndMalformedVersions() {
        for (String bad : new String[]{"latest", "1.2", "01.2.3", "1.2.3-01", " 1.2.3", "1.2.3.4"})
            assertThrows(IllegalArgumentException.class, () -> ReleaseVersion.compare(bad, "1.0.0"));
    }
    @Test public void comparesNumericComponentsInsteadOfText() {
        assertTrue(ReleaseVersion.compare("v0.10.0", "0.9.9") > 0);
    }
}
