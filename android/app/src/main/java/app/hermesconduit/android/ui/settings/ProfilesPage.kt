package app.hermesconduit.android.ui.settings

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.hermesconduit.android.core.ProfileAppearance
import app.hermesconduit.android.core.t
import app.hermesconduit.android.ui.appViewModel
import app.hermesconduit.android.ui.components.ScreenScaffold
import app.hermesconduit.android.ui.components.SettingsColumn
import app.hermesconduit.android.ui.components.SettingsSection
import kotlinx.coroutines.launch

/** iOS ProfilePickerSheet as a page: order, photos and background glow per profile. */
@Composable
fun ProfilesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = appViewModel()
    val profiles by app.profiles.collectAsStateWithLifecycle()
    val active by app.profile.collectAsStateWithLifecycle()
    val appearance = remember { ProfileAppearance.of(context) }
    val version by appearance.version.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var reordering by remember { mutableStateOf(false) }
    var photoFor by remember { mutableStateOf<String?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val profile = photoFor ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val bitmap = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap != null) appearance.setPhoto(profile, bitmap)
        }
    }
    LaunchedEffect(profiles) { profiles.forEach { appearance.loadPhoto(it) } }
    val ordered = remember(profiles, version) { appearance.sorted(profiles) }

    ScreenScaffold(t("Profiles"), onBack, actions = {
        TextButton(onClick = { reordering = !reordering }) { Text(if (reordering) t("Done") else t("Reorder")) }
    }) { padding ->
        SettingsColumn(padding) {
            SettingsSection(null, footer = t("Sessions and settings follow the active profile. Photos are saved to Hermes, so bots and Hermes Desktop show them too.")) {
                ordered.forEachIndexed { index, profile ->
                    if (index > 0) HorizontalDivider()
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(appearance, profile, version, Modifier.clickable { photoFor = profile; pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                            Column(Modifier.weight(1f).padding(start = 12.dp).clickable { app.selectProfile(profile) }) {
                                Text(profile, style = MaterialTheme.typography.titleSmall)
                                Text(if (profile == "default") t("Primary Hermes profile") else t("Hermes profile"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (profile == active) Text("✓", color = MaterialTheme.colorScheme.primary)
                            if (reordering) {
                                IconButton(enabled = index > 0, onClick = { appearance.setOrder(ordered.toMutableList().apply { add(index - 1, removeAt(index)) }) }) { Icon(Icons.Default.KeyboardArrowUp, t("Move up")) }
                                IconButton(enabled = index < ordered.lastIndex, onClick = { appearance.setOrder(ordered.toMutableList().apply { add(index + 1, removeAt(index)) }) }) { Icon(Icons.Default.KeyboardArrowDown, t("Move down")) }
                            }
                        }
                        if (appearance.photo(profile) != null) {
                            TextButton(onClick = { scope.launch { appearance.setPhoto(profile, null) } }, modifier = Modifier.padding(start = 48.dp)) { Text(t("Remove Photo")) }
                        }
                        Row(Modifier.padding(start = 60.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(t("Background glow"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (appearance.pickedGlow(profile) != null) TextButton(onClick = { appearance.setGlow(profile, null) }) { Text(t("Automatic")) }
                        }
                        Row(Modifier.padding(start = 60.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            val picked = appearance.pickedGlow(profile)
                            ProfileAppearance.swatches.forEach { hex ->
                                val color = Color(android.graphics.Color.parseColor(hex))
                                Box(
                                    Modifier.size(26.dp).clip(CircleShape).background(color)
                                        .border(if (picked == hex) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                        .clickable { appearance.setGlow(profile, hex) },
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
fun Avatar(appearance: ProfileAppearance, profile: String, version: Int, modifier: Modifier = Modifier, size: Int = 48) {
    val bitmap = remember(profile, version) { appearance.photo(profile)?.asImageBitmap() }
    if (bitmap != null) {
        Image(bitmap, profile, modifier.size(size.dp).clip(CircleShape), contentScale = ContentScale.Crop)
    } else {
        val glow = remember(profile, version) { appearance.glow(profile) }
        Box(
            modifier.size(size.dp).clip(CircleShape).background(glow?.let { Color(it) } ?: MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Text(profile.take(1).uppercase(), style = MaterialTheme.typography.titleMedium) }
    }
}
