package com.streamline.cinema.ui.player

import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.DrmInitData
import androidx.media3.common.Format
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.drm.DefaultDrmSessionManager
import androidx.media3.exoplayer.drm.DrmSession
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.DrmSessionManager
import androidx.media3.exoplayer.drm.FrameworkMediaDrm
import androidx.media3.exoplayer.drm.HttpMediaDrmCallback
import androidx.media3.exoplayer.drm.LocalMediaDrmCallback
import androidx.media3.extractor.mp4.PsshAtomUtil
import com.streamline.cinema.data.DrmConfig
import com.streamline.cinema.data.DrmScheme

/**
 * Builds the Media3 DRM stack for a resolved source. ClearKey feeds publish the
 * key directly on the watch page, so the license callback is local; Widevine and
 * PlayReady need the license server the page pointed at.
 */
internal fun drmSessionManager(
    config: DrmConfig,
    dataSourceFactory: DataSource.Factory
): DrmSessionManager? {
    val uuid = when (config.scheme) {
        DrmScheme.WIDEVINE -> C.WIDEVINE_UUID
        DrmScheme.PLAYREADY -> C.PLAYREADY_UUID
        DrmScheme.CLEARKEY -> C.CLEARKEY_UUID
    }
    if (!FrameworkMediaDrm.isCryptoSchemeSupported(uuid)) {
        Log.w(
            "StreamLine:drm",
            "scheme ${config.scheme} unsupported on this device (no MediaDrm module)"
        )
        return null
    }
    Log.i(
        "StreamLine:drm",
        "scheme=${config.scheme} license=${config.licenseUrl?.take(120) ?: "local"} headers=${config.headers.keys}"
    )
    val builder = DefaultDrmSessionManager.Builder()
        .setUuidAndExoMediaDrmProvider(uuid, FrameworkMediaDrm.DEFAULT_PROVIDER)
    val callback = when {
        // A watch page that inlines the ClearKey JSON needs no server round trip.
        config.scheme == DrmScheme.CLEARKEY && !config.licenseJson.isNullOrBlank() ->
            LocalMediaDrmCallback(config.licenseJson.toByteArray())
        // ClearKey DASH/HLS can also publish a licence endpoint instead of the key.
        else -> {
            val licenseUrl = config.licenseUrl?.takeIf { it.isNotBlank() } ?: return null
            HttpMediaDrmCallback(licenseUrl, dataSourceFactory).also { callback ->
                config.headers.forEach { (name, value) -> callback.setKeyRequestProperty(name, value) }
            }
        }
    }
    val manager = builder.build(callback)
    return if (config.scheme == DrmScheme.CLEARKEY) ClearKeySchemeDataInjector(manager) else manager
}

/**
 * The DASH manifest advertises Widevine/PlayReady protection plus a default KID,
 * not a ClearKey entry. Media3 already parsed that KID into a Common PSSH scheme
 * data, so mirror it under the ClearKey UUID before the DRM session is acquired.
 */
private class ClearKeySchemeDataInjector(
    private val delegate: DrmSessionManager
) : DrmSessionManager {

    override fun setPlayer(looper: Looper, playerId: PlayerId) = delegate.setPlayer(looper, playerId)

    override fun prepare() = delegate.prepare()

    override fun release() = delegate.release()

    override fun getCryptoType(format: Format): Int = delegate.getCryptoType(inject(format))

    override fun preacquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSessionManager.DrmSessionReference =
        delegate.preacquireSession(eventDispatcher, inject(format))

    override fun acquireSession(
        eventDispatcher: DrmSessionEventListener.EventDispatcher?,
        format: Format
    ): DrmSession? = delegate.acquireSession(eventDispatcher, inject(format))

    private fun inject(format: Format): Format {
        val init = format.drmInitData ?: return format
        if (init.schemeDataCount == 0) return format
        if ((0 until init.schemeDataCount).any { init.get(it).matches(C.CLEARKEY_UUID) }) return format
        val indices = 0 until init.schemeDataCount
        val source = indices.map { init.get(it) }
            .firstOrNull { it.matches(C.COMMON_PSSH_UUID) }
            ?: indices.map { init.get(it) }.firstOrNull { it.hasData() }
            ?: return format
        val data = source.data ?: return format
        val injected = if (source.matches(C.COMMON_PSSH_UUID)) clearKeyPssh(data) else null
        val clearKey = DrmInitData.SchemeData(C.CLEARKEY_UUID, source.mimeType, injected ?: data)
        return format.buildUpon()
            .setDrmInitData(DrmInitData(init.schemeType, listOf(clearKey)))
            .build()
    }
}

/**
 * A Common-system PSSH carries the default KID but under the wrong system id;
 * the platform ClearKey MediaDrm only builds a key request for its own PSSH,
 * so the KID list is re-wrapped with the ClearKey UUID.
 */
private fun clearKeyPssh(commonPssh: ByteArray): ByteArray? {
    val atom = runCatching { PsshAtomUtil.parsePsshAtom(commonPssh) }.getOrNull() ?: return null
    val keyIds = atom.keyIds?.takeIf { it.isNotEmpty() } ?: return null
    return PsshAtomUtil.buildPsshAtom(C.CLEARKEY_UUID, keyIds, null)
}
