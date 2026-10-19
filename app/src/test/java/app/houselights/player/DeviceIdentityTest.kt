package app.houselights.player

import app.houselights.player.FakeApps.Companion.OTHER_BUILD
import app.houselights.player.FakeApps.Companion.REALTEK_BUILD
import app.houselights.player.FakeApps.Companion.ZIDOO_BUILD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A device as its build names it: whether it says Zidoo, and the name About shows. */
class DeviceIdentityTest {
    @Test
    fun aZidooIsToldByAnyFieldOfItsBuild() {
        assertTrue(ZIDOO_BUILD.namesZidoo)
        assertFalse(REALTEK_BUILD.namesZidoo)
        assertFalse(OTHER_BUILD.namesZidoo)
        assertTrue(REALTEK_BUILD.copy(model = "Zidoo Z2000 Pro").namesZidoo)
        assertTrue(REALTEK_BUILD.copy(fingerprint = "ZIDOO/Z9X/rtd1619:9/PPR1/1:user/release-keys").namesZidoo)
    }

    @Test
    fun aDeviceIsNamedByItsMakerAndModel() {
        assertEquals("ZIDOO Z9X Pro", ZIDOO_BUILD.name)
        assertEquals("the maker said once", "Zidoo Z1000 Pro", ZIDOO_BUILD.copy(model = "Zidoo Z1000 Pro").name)
    }
}
