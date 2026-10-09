package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.ui.workouts.NowRunning
import com.noop.ui.workouts.StartWorkoutPicker
import com.noop.ui.workouts.rememberWorkoutStarter

/**
 * The shared "start a workout" entry for screens outside the Workouts tab (Live): the Fitness-style activity
 * list; picking one starts the session (a distance sport asks for location first and records its route,
 * #101) and opens the always-dark recording screen over the app, which minimises to the mini-player.
 * [onDismiss] runs once the list is closed, or once the picked session's recording screen is open — this
 * stays composed until then, so a location answer still reaches the starter.
 */
@Composable
fun StartWorkoutSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val start = rememberWorkoutStarter(vm)
    var picked by remember { mutableStateOf(false) }
    val expanded by NowRunning.expanded.collectAsStateWithLifecycle()
    LaunchedEffect(picked, expanded) { if (picked && expanded != null) onDismiss() }
    if (!picked) {
        StartWorkoutPicker(onDismiss = onDismiss, onStart = { picked = true; start(it) })
    }
}
