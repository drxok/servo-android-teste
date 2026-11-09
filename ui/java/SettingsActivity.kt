/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package org.servo.servoshell

import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.preference.PreferenceManager

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        val historyManager = HistoryManager(this)
        setContent {
            BrowserTheme {
                val mode by rememberStringPref(Prefs.THEME, "system")
                val accent by rememberStringPref(Prefs.ACCENT, "lilac")
                val engine by rememberStringPref(Prefs.ENGINE, "ddg")
                val homeMode by rememberStringPref(Prefs.HOME_MODE, "ddg")
                var cleared by remember { mutableStateOf(false) }

                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    topBar = {
                        @OptIn(ExperimentalMaterial3Api::class)
                        TopAppBar(
                            title = { Text(stringResource(R.string.settings_title)) },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        painterResource(R.drawable.arrow_back),
                                        stringResource(R.string.back),
                                    )
                                }
                            },
                        )
                    },
                ) { innerPadding ->
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding =
                            PaddingValues(
                                start = 16.dp,
                                end = 16.dp,
                                top = innerPadding.calculateTopPadding() + 4.dp,
                                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                            ),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        item {
                            Section("Aparência") {
                                Text("Tema", style = MaterialTheme.typography.labelLarge)
                                val modes =
                                    listOf("system" to "Automático", "light" to "Claro", "dark" to "Escuro")
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    modes.forEachIndexed { index, (id, label) ->
                                        SegmentedButton(
                                            selected = mode == id,
                                            onClick = { preferences.edit { putString(Prefs.THEME, id) } },
                                            shape =
                                                SegmentedButtonDefaults.itemShape(
                                                    index = index,
                                                    count = modes.size,
                                                ),
                                        ) {
                                            Text(label)
                                        }
                                    }
                                }
                                Text(
                                    "Cor de destaque",
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                    ACCENTS.forEach { a ->
                                        Swatch(
                                            color = Color(a.primary),
                                            label = a.label,
                                            selected = accent == a.id,
                                            onClick = {
                                                preferences.edit { putString(Prefs.ACCENT, a.id) }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        item {
                            Section("Páginas da web") {
                                Text("Modo escuro nas páginas", style = MaterialTheme.typography.labelLarge)
                                val pageDark by rememberStringPref(Prefs.PAGE_DARK, "off")
                                val opts = listOf("off" to "Desligado", "auto" to "Automático", "force" to "Forçar")
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    opts.forEachIndexed { index, (id, label) ->
                                        SegmentedButton(
                                            selected = pageDark == id,
                                            onClick = { preferences.edit { putString(Prefs.PAGE_DARK, id) } },
                                            shape = SegmentedButtonDefaults.itemShape(index = index, count = opts.size),
                                        ) {
                                            Text(label)
                                        }
                                    }
                                }
                                Text(
                                    "Automático: só sites que já têm tema escuro. Forçar: escurece qualquer site (pode alterar cores e deixar mais lento).",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            Section("Busca") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("ddg" to "DuckDuckGo", "google" to "Google", "bing" to "Bing")
                                        .forEach { (id, label) ->
                                            FilterChip(
                                                selected = engine == id,
                                                onClick = { preferences.edit { putString(Prefs.ENGINE, id) } },
                                                label = { Text(label) },
                                            )
                                        }
                                }
                                Text(
                                    "O DuckDuckGo não rastreia suas buscas. A mudança vale para as próximas pesquisas.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            Section("Página inicial") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    listOf("ddg" to "DuckDuckGo", "start" to "Do navegador", "custom" to "Personalizada")
                                        .forEach { (id, label) ->
                                            FilterChip(
                                                selected = homeMode == id,
                                                onClick = { preferences.edit { putString(Prefs.HOME_MODE, id) } },
                                                label = { Text(label) },
                                            )
                                        }
                                }
                                if (homeMode == "custom") {
                                    var custom by remember { mutableStateOf(preferences.getString(Prefs.HOME_CUSTOM, "") ?: "") }
                                    OutlinedTextField(
                                        value = custom,
                                        onValueChange = {
                                            custom = it
                                            preferences.edit { putString(Prefs.HOME_CUSTOM, it) }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        label = { Text("Endereço da página inicial") },
                                        placeholder = { Text("ex.: wikipedia.org") },
                                    )
                                }
                                Text(
                                    "Aparece ao abrir uma aba nova e no botão Página inicial do menu.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            Section("Privacidade") {
                                SwitchRow(
                                    title = "Somente HTTPS",
                                    summary = "Endereços http:// são abertos como https://",
                                    preferences = preferences,
                                    key = Prefs.HTTPS_ONLY,
                                    default = true,
                                )
                                SwitchRow(
                                    title = "Bloquear rastreadores",
                                    summary = "Anúncios e rastreadores conhecidos · ${Stats.blocked} bloqueados nesta sessão",
                                    preferences = preferences,
                                    key = Prefs.BLOCK_TRACKERS,
                                    default = true,
                                )
                                TextButton(
                                    onClick = {
                                        historyManager.clearHistory()
                                        cleared = true
                                    }
                                ) {
                                    Text(if (cleared) "Histórico apagado" else "Apagar histórico")
                                }
                            }
                        }
                        item {
                            Section("Avançado") {
                                SwitchRow(
                                    title = stringResource(R.string.settings_experimental_title),
                                    summary = stringResource(R.string.settings_experimental_summary),
                                    preferences = preferences,
                                    key = "experimental",
                                    default = false,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            content()
        }
    }
}

@Composable
private fun Swatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val scale by
        animateFloatAsState(
            targetValue = if (selected) 1.14f else 1f,
            animationSpec = Motion.spatial(),
            label = "swatch",
        )
    Box(
        modifier =
            Modifier.size(44.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(color, CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painterResource(R.drawable.check),
                contentDescription = label,
                tint = Color.White,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    summary: String,
    preferences: SharedPreferences,
    key: String,
    default: Boolean,
) {
    var checked by remember { mutableStateOf(preferences.getBoolean(key, default)) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = {
                    checked = it
                    preferences.edit { putBoolean(key, it) }
                },
            )
        },
    )
}
