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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

// MARK: - Friends: sending today and yesterday
//
// Three things ask for an upload: a finished strap sync, the Friends tab opening or being pulled, and a
// sharing switch changing. All of them end in [FriendsUploader.run], one at a time. With nobody signed in
// it returns before touching the network, the Keystore or the database.
//
// Failure is silent by design. Nothing here is shown to the wearer or retried in a loop: a day that did
// not go up goes up on the next trigger, and the strap sync that asked never learns how it went.

object FriendsUploader {
    enum class Outcome {
        /** Nobody is signed in, or the session changed while this ran: nothing more was sent. */
        SIGNED_OUT,

        /** The server no longer knows the session (a 401). It has been forgotten on this phone too. */
        SESSION_ENDED,

        /** Every day is on the server as the app holds it (sent now, or unchanged since it was last sent). */
        DONE,

        /** The server could not be reached; worth another try when the network is back. */
        OFFLINE,

        /** The server refused or the session is gone; trying again as is would not help. */
        FAILED,
    }

    private val gate = Mutex()

    /**
     * Uploads today and yesterday, each only when its text differs from the one the server last accepted
     * for that day. The sharing switches are read from the server first, so a switch turned off on another
     * phone is honoured here before anything is built.
     */
    suspend fun run(context: Context): Outcome {
        val app = context.applicationContext
        val store = FriendsStore.get(app)
        if (!store.isSignedIn) return Outcome.SIGNED_OUT
        return gate.withLock {
            try {
                upload(app, store)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // A read that threw is one upload not made, never a crash in a worker or under the tab.
                Outcome.FAILED
            }
        }
    }

    private suspend fun upload(app: Context, store: FriendsStore): Outcome {
        val noopApp = app as? NoopApplication ?: return Outcome.FAILED
        val session = store.epoch
        val token = store.token() ?: return Outcome.SIGNED_OUT
        val api = FriendsApi.create(store.serverUrl, token) ?: return Outcome.FAILED

        val me = when (val answer = api.me()) {
            is FriendsResult.Ok -> answer.value.also { store.saveMe(it, session) }
            is FriendsResult.Fail -> return when (answer.error) {
                FriendsError.OFFLINE -> Outcome.OFFLINE
                FriendsError.UNAUTHORIZED -> endedByServer(app, store, session)
                else -> Outcome.FAILED
            }
        }
        val share = me.share ?: return Outcome.FAILED

        val days = FriendsDaySource.recentDays(noopApp, share)
        val keep = days.mapTo(HashSet()) { it.key }
        var outcome = Outcome.DONE
        for (day in days) {
            // The session this run started under is gone: nothing more is sent with its token.
            if (store.epoch != session) return Outcome.SIGNED_OUT
            val nowTs = System.currentTimeMillis() / 1000L
            val json = FriendsDayPayload.toJson(FriendsDayPayload.build(day.inputs, share, nowTs))
            if (!FriendsUploadPolicy.shouldUpload(store.uploadMark(day.key), json)) continue
            when (val sent = api.putDay(day.key, json)) {
                is FriendsResult.Ok ->
                    store.recordUpload(day.key, FriendsDayPayload.fingerprint(json), keep, nowTs, session)
                is FriendsResult.Fail -> outcome = when (sent.error) {
                    FriendsError.OFFLINE -> return Outcome.OFFLINE
                    FriendsError.UNAUTHORIZED -> return endedByServer(app, store, session)
                    else -> Outcome.FAILED
                }
            }
        }
        return outcome
    }

    /**
     * The server answered 401 to the session [session]. Forgetting it here is what stops every later
     * trigger. If the phone has moved on to another session meanwhile, that one is left alone.
     */
    private fun endedByServer(app: Context, store: FriendsStore, session: Int): Outcome {
        if (store.epoch != session) return Outcome.SIGNED_OUT
        FriendsSessionEnd.local(app)
        return Outcome.SESSION_ENDED
    }
}

/** Ending the session on this phone: the one way the token, the cache and queued uploads go away together. */
object FriendsSessionEnd {
    /** Forgets the session locally and stops uploads. The server is not told; see [FriendsApi.logout]. */
    fun local(context: Context) {
        val app = context.applicationContext
        FriendsUploadScheduler.cancel(app)
        FriendsStore.get(app).clearSession()
        FriendsAvatars.clear(app)
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
     * The strap-sync hook. It only enqueues, and only when an account is signed in (one read of an
     * ordinary preference); it never waits, never throws into the sync path, and with nobody signed in it
     * does nothing at all.
     */
    fun enqueueAfterSuccessfulOffload(context: Context) = enqueue(context, AFTER_OFFLOAD_DELAY_S)

    /** After a sharing switch changed: what the server holds no longer matches, so send without waiting. */
    fun enqueueNow(context: Context) = enqueue(context, 0L)

    fun cancel(context: Context) {
        runCatching { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(UNIQUE_WORK) }
    }

    private fun enqueue(context: Context, delaySeconds: Long) {
        runCatching {
            val app = context.applicationContext
            if (!FriendsStore.get(app).isSignedIn) return
            val request = OneTimeWorkRequest.Builder(FriendsUploadWorker::class.java)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_S, TimeUnit.SECONDS)
                .build() // No input data: the token and the address never enter WorkManager's own database.
            WorkManager.getInstance(app).enqueueUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** The background run of [FriendsUploader]. It never fails a chain and never reports to the wearer. */
class FriendsUploadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        when (FriendsUploader.run(applicationContext)) {
            FriendsUploader.Outcome.OFFLINE -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
            else -> Result.success()
        }

    private companion object {
        /** The network came and went this many times: leave it to the next strap sync. */
        const val MAX_ATTEMPTS = 3
    }
}
