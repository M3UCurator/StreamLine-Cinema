package com.streamline.cinema.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/** Thrown before a large document is written when the device cannot hold two copies. */
class InsufficientStorageException(
    val label: String,
    val requiredBytes: Long,
    val freeBytes: Long
) : IOException(
    "Not enough storage to save $label · needs ${AtomicStore.formatSize(requiredBytes)} " +
        "but only ${AtomicStore.formatSize(freeBytes)} is free"
)

/**
 * Crash-safe JSON document storage.
 *
 * [write] stages the new content beside the target, fsyncs it, then renames it
 * over the old file. The previous copy stays as `.bak` until the replacement is
 * in place, so a TV losing power mid-write cannot leave the only copy broken.
 * A pre-flight free-space check rejects documents that would leave no room for
 * the staged copy and the backup instead of failing halfway through the write.
 */
object AtomicStore {

    private const val SPACE_MARGIN_BYTES = 4L * 1024 * 1024

    fun read(file: File): String? = runCatching {
        if (!file.exists() || !file.isFile) return null
        val text = file.readText()
        text.takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Reads [primary], falling back to its `.bak` sibling when the primary is unusable. */
    fun readWithBackup(primary: File): String? {
        val text = read(primary)
        if (text != null) return text
        return read(File(primary.parentFile, "${primary.name}.bak"))
    }

    fun write(target: File, text: String, label: String = target.name) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        requireSpace(target, bytes.size.toLong(), label)
        val dir = target.parentFile ?: throw IOException("Cannot save $label: missing folder")
        if (!dir.exists() && !dir.mkdirs()) throw IOException("Cannot save $label: folder unavailable")
        val tmp = File(dir, "${target.name}.tmp")
        val backup = File(dir, "${target.name}.bak")
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (target.exists()) target.copyTo(backup, overwrite = true)
        if (!tmp.renameTo(target)) {
            // Some FUSE-backed file systems refuse the rename; retry through a
            // second temp file rather than truncating the live document.
            val fallback = File(dir, "${target.name}.new")
            tmp.copyTo(fallback, overwrite = true)
            if (!fallback.renameTo(target)) {
                target.writeText(text)
                fallback.delete()
            }
        }
        tmp.delete()
    }

    /**
     * Rejects a write that needs more room than the device has. The staged copy
     * and the `.bak` copy mean the payload briefly exists twice, so the check
     * asks for roughly two payloads plus a margin for the filesystem.
     */
    fun requireSpace(target: File, payloadBytes: Long, label: String) {
        val free = target.parentFile?.usableSpace ?: 0L
        // usableSpace reports 0 when the platform cannot answer; never block a
        // write on a missing measurement.
        if (free <= 0L) return
        val required = payloadBytes * 2 + SPACE_MARGIN_BYTES
        if (free < required) throw InsufficientStorageException(label, required, free)
    }

    /** True when [free] can hold a staged copy of [payloadBytes]; split out for tests. */
    fun hasRoomFor(payloadBytes: Long, free: Long): Boolean =
        free <= 0L || free >= payloadBytes * 2 + SPACE_MARGIN_BYTES

    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = listOf("KB", "MB", "GB")
        var value = bytes.toDouble()
        var unit = -1
        while (value >= 1024 && unit < units.lastIndex) {
            value /= 1024
            unit++
        }
        return String.format(Locale.US, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }
}

/** Shared device-class profile used to size parser caches on low-RAM TV boxes. */
object DeviceProfile {
    private const val LOW_RAM_MERGED_EPG_PROGRAMS = 150_000
    private const val LOW_RAM_SINGLE_EPG_PROGRAMS = 80_000

    fun isLowRam(context: android.content.Context): Boolean {
        val manager = context.getSystemService(android.content.Context.ACTIVITY_SERVICE)
            as? android.app.ActivityManager ?: return false
        // Some 1 GB TV boxes never set the low-RAM flag, but a 256 MB memory
        // class is the same hardware class regardless of what the vendor
        // declared, so the playback buffers must be sized for it.
        return manager.isLowRamDevice || manager.memoryClass <= 256
    }

    fun mergedEpgProgramCap(lowRam: Boolean): Int =
        if (lowRam) LOW_RAM_MERGED_EPG_PROGRAMS else MAX_MERGED_EPG_PROGRAMS

    fun singleEpgProgramCap(lowRam: Boolean): Int =
        if (lowRam) LOW_RAM_SINGLE_EPG_PROGRAMS else MAX_EPG_PROGRAMS
}
