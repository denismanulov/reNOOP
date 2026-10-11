package com.noop.friends

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.noop.NoopApplication
import com.noop.ui.ProfileAvatarStore
import com.noop.ui.ProfileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

// MARK: - Friends: sending today and yesterday
//
// Three things ask for an upload: a finished strap sync, the Friends tab opening or being pulled, and a
// sharing switch changing. All of them end in [FriendsStore.uploadRecentDays], one at a time. With
// Friends off it returns before touching the network or the database.
//
// Failure is silent by design. Nothing here is shown to the wearer or retried in a loop: a day that did
// not go up goes up on the next trigger, and the strap sync that asked never learns how it went.

/** What the store reads out of the app: the profile's name and photo, and the figures of a day. */
internal class FriendsAppSource(private val app: Context) : FriendsLocalSource {
    override suspend fun profile(): FriendsLocalProfile = withContext(Dispatchers.IO) {
        FriendsLocalProfile(
            name = ProfileStore.from(app).displayName,
            photoJpeg = ProfileAvatarStore.storedJpeg(app)?.let { FriendsAvatars.fitForUpload(it) },
        )
    }

    override suspend fun recentDays(share: FriendShare): List<FriendsLocalDay> {
        val noopApp = app as? NoopApplication ?: return emptyList()
        return FriendsDaySource.recentDays(noopApp, share)
    }
}

/** Queues [FriendsUploadWorker]. Unique work, so any number of triggers collapse into one pending run. */
object FriendsUploadScheduler {
    internal const val UNIQUE_WORK = "friends-day-upload"

    /**
     * How long after a strap sync the upload waits. The sync is followed by the scoring pass that turns
     * the new samples into Recovery, Strain and sleep; uploading at once would send the figures from
     * before it. A newer trigger inside the wait replaces this one.
     */
    internal const val AFTER_OFFLOAD_DELAY_S = 60L
    private const val BACKOFF_S = 60L

    /**
     * The strap-sync hook. It only enqueues, and only when Friends is on (one read of an ordinary
     * preference); it never waits, never throws into the sync path, and with Friends off it does nothing
     * at all.
     */
    fun enqueueAfterSuccessfulOffload(context: Context) = enqueue(context, AFTER_OFFLOAD_DELAY_S)

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK) }
    }

    private fun enqueue(context: Context, delaySeconds: Long) {
        runCatching {
            val app = context.applicationContext
            if (!FriendsStore.isKeptOn(app)) return
            val request = OneTimeWorkRequest.Builder(FriendsUploadWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_S, TimeUnit.SECONDS)
                .build() // No input data: nothing of the account enters WorkManager's own database.
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** The background run of the upload. It never fails a chain and never reports to the wearer. */
class FriendsUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!FriendsStore.isKeptOn(applicationContext)) return Result.success()
        return when (FriendsStore.get(applicationContext).uploadRecentDays()) {
            FriendsStore.UploadOutcome.OFFLINE -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            else -> Result.success()
        }
    }

    private companion object {
        /** The network came and went this many times: leave it to the next strap sync. */
        const val MAX_ATTEMPTS = 3
    }
}
