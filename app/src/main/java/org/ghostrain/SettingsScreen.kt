// SPDX-License-Identifier: GPL-3.0-or-later
package org.ghostrain

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Settings skeleton: top bar, HOME/LOCK target tabs, and empty per-screen
 * [LazyColumn] sections. Tasks 4-6 fill the sections (preview, HUD/rain
 * controls, layouts); the tab state they all key off lives here.
 *
 * @param editingLock which screen is being edited; survives rotation via
 * [rememberSaveable].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    var editingLock by rememberSaveable { mutableStateOf(false) }
    Scaffold(
            topBar = {
                TopAppBar(title = { Text("Ghost Rain") })
            }
    ) { padding ->
        Column(
                modifier = Modifier.fillMaxSize().padding(padding)
        ) {
            TabRow(selectedTabIndex = if (editingLock) 1 else 0) {
                Tab(
                        selected = !editingLock,
                        onClick = { editingLock = false },
                        text = { Text("HOME") }
                )
                Tab(
                        selected = editingLock,
                        onClick = { editingLock = true },
                        text = { Text("LOCK") }
                )
            }
            // Per-screen sections; Tasks 4-6 fill these, keyed off editingLock.
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(if (editingLock) "LOCK screen" else "HOME screen")
                }
            }
        }
    }
}
