package com.noop.analytics

/**
 * The learned sleep-stage model as the app ships it: two tree files in the APK's assets
 * (`sleepstage/SleepStageFirst.trees`, `SleepStageSecond.trees`), installed into [SleepStageLearned] so
 * every place a night is staged asks them first. Android twin of Swift `SleepStageModelStore`, which
 * runs the same trees through Core ML. How they were fit is in `Tools/SleepML`; what they are given and
 * how their answer becomes a hypnogram is in [SleepStageFeatures] and [SleepStageLearned]. This file
 * only loads them.
 *
 * Trained on Wearanize+ OA (Radboud University, CC BY 4.0) and sleep-accel (Walch et al., PhysioNet,
 * ODC-By 1.0); each file's comment lines carry both attributions.
 *
 * The analytics layer has no Android `Context`, so the app hands over a way to read an asset
 * ([register], from `NoopApplication.onCreate`) and the files are read and parsed on the first
 * [ensureInstalled], on whichever background thread scores or re-stages first.
 */
object SleepStageModelStore {

    const val ASSET_DIR = "sleepstage"
    const val FIRST = "SleepStageFirst.trees"
    const val SECOND = "SleepStageSecond.trees"

    private val lock = Any()
    private var read: ((name: String) -> String?)? = null
    private var log: (String) -> Unit = {}
    private var attempted = false

    /**
     * How to read one of the two files as text, null when it is not there. Cheap: nothing is read until
     * a night is about to be staged. A second registration is ignored once the files have been read.
     */
    fun register(log: (String) -> Unit = {}, read: (name: String) -> String?) {
        synchronized(lock) {
            if (attempted) return
            this.read = read
            this.log = log
        }
    }

    /**
     * Install the shipped models, once per process. Called before a night is scored or re-staged; a
     * second call does nothing. Files that are missing, malformed or fit on other columns install
     * nothing, and every night is then staged by the recipe as before. With nothing registered (a
     * plain JVM test) this does nothing and may be asked again.
     *
     * Once per process and before the first pass is also why the model is not a field of the engine's
     * day-cache signature (`IntelligenceEngine.dayCacheConfigFields`, a list shared with the Swift
     * twin): that cache lives in memory, so it never holds a scan another model staged.
     */
    fun ensureInstalled() {
        synchronized(lock) {
            if (attempted) return
            val read = read ?: return
            attempted = true
            val model = load(read)
            if (model == null) {
                log("SleepStageModelStore: the stage models are not in the assets or did not load; staging with the recipe")
                return
            }
            SleepStageLearned.model = model
        }
    }

    /**
     * The pair [read] gives, with the decoder settings the second file carries. The version, which keys
     * every cached hypnogram and marks which model the history was last staged with, is the two files'
     * `source` values joined: each is the SHA-256 of the `.mlmodel` its file was written from, so a
     * rebuilt app with refit models stages every stored night again and one with the same models does
     * not. It stays on the phone; no backup carries it.
     */
    fun load(read: (name: String) -> String?): SleepStageModel? {
        val first = read(FIRST)?.let(SleepStageTrees::parse) ?: return null
        val second = read(SECOND)?.let(SleepStageTrees::parse) ?: return null
        if (first.source.isEmpty() || second.source.isEmpty()) return null
        return SleepStageModel.fromTrees(first, second, version = first.source + second.source)
    }
}
