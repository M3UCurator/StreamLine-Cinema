package com.streamline.cinema.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The updater compares release tags numerically and installs the APK flavor
 * that matches the running build (32-bit ARM vs all-ABI).
 */
class UpdaterTest {

    private fun asset(name: String, size: Long = 1_000) =
        UpdateAsset(name = name, url = "https://example.test/$name", size = size)

    @Test
    fun parsesTagWithPrefixAndSuffix() {
        assertEquals(listOf(12, 77), parseVersion("v12.77"))
        assertEquals(listOf(12, 100), parseVersion("V12.100-beta"))
        assertEquals(listOf(12, 77), parseVersion(" 12.77 "))
        assertTrue(parseVersion("nightly").isEmpty())
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(isNewerVersion("v12.77", "12.76"))
        assertFalse(isNewerVersion("v12.76", "12.76"))
        assertFalse(isNewerVersion("v12.75", "12.76"))
        assertTrue(isNewerVersion("v12.100", "12.99"))
        assertFalse(isNewerVersion("v12.7", "12.70"))
        assertTrue(isNewerVersion("v13.0", "12.99"))
        assertFalse(isNewerVersion("nightly", "12.76"))
    }

    @Test
    fun picksTheAssetMatchingTheInstalledFlavor() {
        val assets = listOf(
            asset("Cinema111126v12.77.apk"),
            asset("Cinema111126v12.77-allabis.apk"),
            asset("checksums.txt")
        )

        assertEquals(
            "Cinema111126v12.77.apk",
            pickUpdateAsset(assets, allAbis = false)?.name
        )
        assertEquals(
            "Cinema111126v12.77-allabis.apk",
            pickUpdateAsset(assets, allAbis = true)?.name
        )
    }

    @Test
    fun fallsBackWhenThePreferredFlavorIsMissing() {
        val onlyAllAbis = listOf(asset("Cinema111126v12.77-allabis.apk"))
        val onlyArm = listOf(asset("Cinema111126v12.77.apk"))

        assertEquals(
            "Cinema111126v12.77-allabis.apk",
            pickUpdateAsset(onlyAllAbis, allAbis = false)?.name
        )
        assertEquals(
            "Cinema111126v12.77.apk",
            pickUpdateAsset(onlyArm, allAbis = true)?.name
        )
        assertNull(pickUpdateAsset(listOf(asset("notes.txt")), allAbis = false))
    }
}
