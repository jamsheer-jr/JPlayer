package com.tmplayer.data

/**
 * The weekly auto backup's clock (owner, v1.0.14). The folder it writes to is the
 * viewer's pick, kept as a setting on this device alone: a folder grant belongs to the
 * device that was given it, so neither the folder nor this clock travels in a backup
 * (see BackupCoverageTest's left behind list, beside download_root).
 */
object AutoBackup {

    /** A week, in milliseconds. */
    const val WEEK_MS: Long = 7L * 24 * 60 * 60 * 1000

    /**
     * Whether a backup is owed at [now], the last one having been written at [lastAt]
     * (0 for never). A clock set back past the last write counts as due, so a wrong
     * clock cannot put the weekly save off for good.
     */
    fun due(lastAt: Long, now: Long): Boolean =
        lastAt <= 0L || now < lastAt || now - lastAt >= WEEK_MS
}
