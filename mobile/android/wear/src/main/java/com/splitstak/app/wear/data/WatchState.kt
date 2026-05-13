package com.splitstak.app.wear.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide cache of the most recent Snapshot received from the phone.
 *
 *  - The Data Layer listener calls [update] whenever the phone publishes
 *    a new snapshot. The listener fires regardless of whether any UI is
 *    bound, which is why this is a singleton object rather than a
 *    ViewModel.
 *  - UI / Tile / Complication consumers observe [snapshotFlow] (Compose)
 *    or call [currentSnapshot] (Tile / Complication onRequest callbacks).
 *  - Snapshots are also written to SharedPreferences so a watch reboot
 *    doesn't show "syncing…" until the phone reconnects.
 *
 * Optimistic local mutations (`applyLocal…`) let UI controls respond
 * instantly without waiting for the watch→phone→watch round trip. The
 * phone re-publishes the authoritative snapshot a moment later and any
 * drift gets corrected.
 */
object WatchState {

    private const val PREFS = "splitstak_wear"
    private const val KEY_SNAPSHOT_JSON = "snapshot_json"
    private const val KEY_WIDGET_SELECTED_ID = "widget_selected_id"

    private val _snapshotFlow = MutableStateFlow<Snapshot?>(null)
    val snapshotFlow: StateFlow<Snapshot?> = _snapshotFlow.asStateFlow()

    private val _widgetSelectedFlow = MutableStateFlow<String?>(null)
    val widgetSelectedFlow: StateFlow<String?> = _widgetSelectedFlow.asStateFlow()

    /** Load the cached snapshot. Safe to call repeatedly; idempotent. */
    fun hydrate(context: Context) {
        if (_snapshotFlow.value != null) return
        val prefs = prefs(context)
        val raw = prefs.getString(KEY_SNAPSHOT_JSON, null)
        if (raw != null) {
            _snapshotFlow.value = Snapshot.fromJson(raw)
        }
        _widgetSelectedFlow.value = prefs.getString(KEY_WIDGET_SELECTED_ID, null)
    }

    /** Replace the snapshot, persist to prefs, fan out to observers. */
    fun update(context: Context, json: String) {
        val snap = Snapshot.fromJson(json) ?: return
        prefs(context).edit().putString(KEY_SNAPSHOT_JSON, json).apply()
        _snapshotFlow.value = snap
        // If watch had no selection yet (cold start) OR the previous
        // selection is no longer in the new snapshot, fall back to the
        // PWA's selected exercise.
        val current = _widgetSelectedFlow.value
        if (current == null || snap.exercises.none { it.id == current }) {
            val newId = snap.selectedExerciseId
                ?: snap.exercises.firstOrNull()?.id
            if (newId != null) setSelected(context, newId)
        }
    }

    fun currentSnapshot(context: Context): Snapshot? {
        if (_snapshotFlow.value == null) hydrate(context)
        return _snapshotFlow.value
    }

    fun setSelected(context: Context, exerciseId: String) {
        prefs(context).edit().putString(KEY_WIDGET_SELECTED_ID, exerciseId).apply()
        _widgetSelectedFlow.value = exerciseId
    }

    /** Resolve the exercise the watch is currently displaying. */
    fun currentExercise(context: Context): Exercise? {
        val snap = currentSnapshot(context) ?: return null
        val id = _widgetSelectedFlow.value
        if (id != null) {
            snap.exercises.firstOrNull { it.id == id }?.let { return it }
        }
        return snap.currentExercise()
    }

    // ---- Optimistic local mutations -------------------------------------
    //
    // These mutate _snapshotFlow.value in place so the UI re-renders
    // immediately when the user taps a control or rotates the crown. The
    // phone is the authority and will overwrite this snapshot via [update]
    // a beat later — these are pure UI smoothing.

    fun applyLocalIncStrength(exerciseId: String, setIdx: Int, field: String, signedSteps: Int) {
        mutateSet(exerciseId, setIdx) { ex, set ->
            val step = when (field) {
                "w" -> _snapshotFlow.value?.weightStep ?: 5
                else -> _snapshotFlow.value?.repStep ?: 1
            }
            val current = when (field) {
                "w" -> set.w.trim().toIntOrNull() ?: 0
                else -> set.r.trim().toIntOrNull() ?: 0
            }
            val next = maxOf(0, current + signedSteps * step)
            when (field) {
                "w" -> set.copy(w = next.toString())
                else -> set.copy(r = next.toString())
            }
        }
    }

    fun applyLocalIncHold(exerciseId: String, setIdx: Int, signedSteps: Int) {
        mutateSet(exerciseId, setIdx) { _, set ->
            val step = _snapshotFlow.value?.holdStep ?: 5
            val current = set.t.trim().toIntOrNull() ?: 0
            val next = maxOf(0, current + signedSteps * step)
            set.copy(t = next.toString())
        }
    }

    fun applyLocalIncCardio(exerciseId: String, field: String, signedSteps: Int) {
        val snap = _snapshotFlow.value ?: return
        val exIdx = snap.exercises.indexOfFirst { it.id == exerciseId }
        if (exIdx < 0) return
        val ex = snap.exercises[exIdx]
        val cardio = ex.cardio ?: return
        val step = when (field) {
            "time" -> snap.timeStep
            else -> snap.distanceStep
        }
        val current = when (field) {
            "time" -> cardio.time.trim().toDoubleOrNull() ?: 0.0
            else -> cardio.distance.trim().toDoubleOrNull() ?: 0.0
        }
        val next = maxOf(0.0, current + signedSteps * step)
        val formatted = if (next == next.toInt().toDouble()) next.toInt().toString()
                        else "%.1f".format(next)
        val newCardio = when (field) {
            "time" -> cardio.copy(time = formatted)
            else -> cardio.copy(distance = formatted)
        }
        val newEx = ex.copy(cardio = newCardio)
        val list = snap.exercises.toMutableList().also { it[exIdx] = newEx }
        _snapshotFlow.value = snap.copy(exercises = list)
    }

    fun applyLocalToggleDone(exerciseId: String, setIdx: Int) {
        val snap = _snapshotFlow.value ?: return
        val exIdx = snap.exercises.indexOfFirst { it.id == exerciseId }
        if (exIdx < 0) return
        val ex = snap.exercises[exIdx]
        if (ex.mode == "cardio") {
            val cardio = ex.cardio ?: return
            val newDone = !cardio.done
            val newEx = ex.copy(
                cardio = cardio.copy(done = newDone),
                allComplete = newDone
            )
            val list = snap.exercises.toMutableList().also { it[exIdx] = newEx }
            _snapshotFlow.value = snap.copy(exercises = list)
            return
        }
        if (setIdx < 0 || setIdx >= ex.sets.size) return
        val newSet = ex.sets[setIdx].let { it.copy(d = !it.d) }
        val newSets = ex.sets.toMutableList().also { it[setIdx] = newSet }
        val allDone = newSets.all { it.d }
        val newEx = ex.copy(sets = newSets, allComplete = allDone)
        val list = snap.exercises.toMutableList().also { it[exIdx] = newEx }
        _snapshotFlow.value = snap.copy(exercises = list)
    }

    private inline fun mutateSet(
        exerciseId: String,
        setIdx: Int,
        block: (Exercise, SetEntry) -> SetEntry
    ) {
        val snap = _snapshotFlow.value ?: return
        val exIdx = snap.exercises.indexOfFirst { it.id == exerciseId }
        if (exIdx < 0) return
        val ex = snap.exercises[exIdx]
        if (setIdx < 0 || setIdx >= ex.sets.size) return
        val newSet = block(ex, ex.sets[setIdx])
        val newSets = ex.sets.toMutableList().also { it[setIdx] = newSet }
        val newEx = ex.copy(sets = newSets)
        val list = snap.exercises.toMutableList().also { it[exIdx] = newEx }
        _snapshotFlow.value = snap.copy(exercises = list)
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
