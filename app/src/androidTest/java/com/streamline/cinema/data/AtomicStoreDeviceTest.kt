package com.streamline.cinema.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * AtomicStore against a real device file system: fsync, rename and the `.bak`
 * sibling behave differently on ext4/f2fs than on a JVM temp folder, so the
 * crash-recovery contract is worth executing on the device that ships it.
 */
@RunWith(AndroidJUnit4::class)
class AtomicStoreDeviceTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "atomic-store-test"
        ).apply {
            deleteRecursively()
            mkdirs()
        }
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    @Test fun writeKeepsThePreviousDocumentAsBackup() {
        val target = File(dir, "doc.json")

        AtomicStore.write(target, """{"version":1}""")
        assertEquals("""{"version":1}""", AtomicStore.read(target))

        AtomicStore.write(target, """{"version":2}""")

        assertEquals("""{"version":2}""", AtomicStore.read(target))
        assertEquals("""{"version":1}""", File(dir, "doc.json.bak").readText())
        assertFalse("staging file must not survive the write", File(dir, "doc.json.tmp").exists())
    }

    @Test fun zeroBytePrimaryFallsBackToTheBackup() {
        val target = File(dir, "doc.json")
        AtomicStore.write(target, """{"version":1}""")
        AtomicStore.write(target, """{"version":2}""")

        target.writeText("")

        assertEquals("""{"version":1}""", AtomicStore.readWithBackup(target))
    }

    @Test fun unicodePayloadRoundTripsThroughFsyncAndRename() {
        val target = File(dir, "channels.json")
        val payload = """{"channel":"SABC 1 — Nuus","emoji":"✓","path":"/data/µ"}"""

        AtomicStore.write(target, payload)

        assertEquals(payload, AtomicStore.read(target))
    }

    @Test fun preFlightRejectsAPayloadThatWouldFillTheDevice() {
        val target = File(dir, "playlist.m3u")
        val free = dir.usableSpace
        assumeTrue("filesystem does not report free space", free > 0)
        val tooBig = free / 2 + 1

        val error = assertThrows(InsufficientStorageException::class.java) {
            AtomicStore.requireSpace(target, tooBig, "playlist")
        }

        assertTrue(error.message!!.startsWith("Not enough storage to save playlist"))
        assertFalse("rejected write must not create the document", target.exists())
    }
}
