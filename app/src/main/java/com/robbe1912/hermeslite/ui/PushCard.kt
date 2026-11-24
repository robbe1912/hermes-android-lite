package com.robbe1912.hermeslite.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robbe1912.hermeslite.App
import com.robbe1912.hermeslite.data.PushConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The push setup. The user picks the google-services.json of the Firebase project.
 *
 * The file stays on the device. The app reads four values from it and starts Firebase with them.
 */
@Composable
internal fun PushCard(onError: (String) -> Unit) {
    val app = App.instance
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by app.push.state.collectAsStateWithLifecycle()
    val push by app.settings.push.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf<String?>(null) }

    // Android 13 and later need the okay of the user for notifications.
    var permissionCheck by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionCheck++ }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch(Dispatchers.IO) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
            }.getOrNull()
            val config = text?.let { PushConfig.fromServicesJson(it, context.packageName) }
            if (config == null) {
                failed = "The file does not hold the values of an Android app in a Firebase project."
            } else {
                failed = null
                app.settings.savePush(config)
            }
        }
    }

    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Heading("Push")
        Hint("The gateway tells this device when a cron run ends. It needs your Firebase project.")
        Hint("Pick the google-services.json of the Android app in that project. The file stays on this device.")
        Text(state, style = MaterialTheme.typography.bodyMedium)
        val config = push
        if (config?.isSetUp == true) {
            Hint("Project: ${config.projectId}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch(arrayOf("application/json", "application/octet-stream", "text/plain")) }) {
                Text(if (config?.isSetUp == true) "Replace the file" else "Choose google-services.json")
            }
            if (config?.isSetUp == true) {
                TextButton(onClick = { scope.launch(Dispatchers.IO) { app.push.turnOff() } }) {
                    Text("Turn push off")
                }
            }
        }
        val denied = Build.VERSION.SDK_INT >= 33 && permissionCheck >= 0 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        if (denied) {
            TextButton(onClick = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text("Allow notifications")
            }
        }
        failed?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}
