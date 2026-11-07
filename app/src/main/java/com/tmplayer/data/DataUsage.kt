package com.tmplayer.data

import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.TrafficStats
import android.os.Process
import android.os.SystemClock

/**
 * This device's data usage for THIS app (owner, v1.0.15, item 2), read from Android's own
 * counters at the moment it is asked for: nothing is measured or stored by the app itself.
 *
 * The honest limits, which the Profile page's wording repeats: the counters are this app's
 * traffic only (its uid), on this device only, and they restart from zero when the device
 * restarts. Streaming and downloads are one figure: both run through the same connection and
 * the platform does not separate them. Other devices' usage cannot be had at all: Telegram
 * does not share it, and the app has no server of its own to ask.
 *
 * The mobile and Wi-Fi split comes from [NetworkStatsManager] for the same uid and the same
 * span (since the device booted). Where the platform refuses or cannot answer, the split is
 * null and the page shows the total alone rather than a guessed split.
 */
object DataUsage {

    /** One reading: the app's bytes since the device booted, split where the platform allows. */
    data class Usage(
        val totalBytes: Long,
        val mobileBytes: Long?,
        val wifiBytes: Long?,
    )

    /** The current reading, or null where the platform keeps no per app counters at all. */
    fun read(context: Context): Usage? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx < 0 || tx < 0) return null
        val total = rx + tx
        val manager = runCatching {
            context.getSystemService(Context.NETWORK_STATS_SERVICE) as? NetworkStatsManager
        }.getOrNull()
        val bootedAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val wifi = manager?.let { sumFor(it, ConnectivityManager.TYPE_WIFI, bootedAt, now, uid) }
        val mobile = manager?.let { sumFor(it, ConnectivityManager.TYPE_MOBILE, bootedAt, now, uid) }
        return Usage(totalBytes = total, mobileBytes = mobile, wifiBytes = wifi)
    }

    /** One network type's bytes for [uid] in the span, or null when the platform will not say. */
    private fun sumFor(
        manager: NetworkStatsManager,
        type: Int,
        from: Long,
        to: Long,
        uid: Int,
    ): Long? = runCatching {
        val stats = manager.queryDetailsForUid(type, null, from, to, uid)
        var sum = 0L
        val bucket = android.app.usage.NetworkStats.Bucket()
        while (stats.hasNextBucket()) {
            stats.getNextBucket(bucket)
            sum += bucket.rxBytes + bucket.txBytes
        }
        stats.close()
        sum
    }.getOrNull()
}
