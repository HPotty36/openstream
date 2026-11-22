package dev.openstream.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionTest {
    @Test fun higherPatchIsNewer() = assertTrue(isNewer("v0.4.1", "0.4.0"))
    @Test fun comparesNumericallyNotAsText() = assertTrue(isNewer("0.10.0", "0.9.9"))
    @Test fun sameVersionIsNotNewer() = assertFalse(isNewer("v0.4.0", "0.4.0"))
    @Test fun olderIsNotNewer() = assertFalse(isNewer("0.3.9", "0.4.0"))
    @Test fun missingPartsCountAsZero() {
        assertFalse(isNewer("1.0", "1.0.0"))
        assertTrue(isNewer("1.1", "1.0.5"))
    }
    @Test fun suffixIsIgnored() = assertFalse(isNewer("0.4.0-beta", "0.4.0"))
    @Test fun tagThatIsNotAVersionIsNotNewer() = assertFalse(isNewer("nightly", "0.4.0"))
    @Test fun unknownInstalledVersionIsNotUpdated() = assertFalse(isNewer("0.5.0", ""))
}
