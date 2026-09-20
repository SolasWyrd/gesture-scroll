package com.arti.gesturescroll

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var cameraGranted by mutableStateOf(false)
    private var accessibilityEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissions()
        setContent {
            HandScrollApp(
                cameraGranted = cameraGranted,
                accessibilityEnabled = accessibilityEnabled,
                onRequestAccessibility = ::openAccessibilitySettings,
                onStart = ::startController,
                onStop = ::stopController,
                onRequestCamera = {},
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissions()
    }

    private fun refreshPermissions() {
        cameraGranted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
        accessibilityEnabled = isAccessibilityServiceEnabled()
    }

    private fun startController() {
        val serviceIntent = Intent(this, GestureCameraService::class.java)
        ContextCompat.startForegroundService(this, serviceIntent)
        window.decorView.postDelayed(::openTikTok, 500L)
    }

    private fun stopController() {
        stopService(Intent(this, GestureCameraService::class.java))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun openTikTok() {
        val packages = listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")
        val launchIntent = packages
            .asSequence()
            .mapNotNull(packageManager::getLaunchIntentForPackage)
            .firstOrNull()

        if (launchIntent == null) {
            Toast.makeText(this, "TikTok не найден", Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(launchIntent)
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = ComponentName(this, GestureAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ).orEmpty()

        return enabled
            .split(':')
            .mapNotNull(ComponentName::unflattenFromString)
            .any { it == expected }
    }
}

@Composable
private fun HandScrollApp(
    cameraGranted: Boolean,
    accessibilityEnabled: Boolean,
    onRequestAccessibility: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestCamera: () -> Unit,
) {
    val runtime by GestureRuntime.state.collectAsState()
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissions ->
        val granted = permissions[Manifest.permission.CAMERA] == true
        if (granted) {
            onStart()
        }
    }

    fun requestCameraAndStart() {
        val permissions = buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
        cameraLauncher.launch(permissions)
        onRequestCamera()
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0B0B0D),
            surface = Color(0xFF111216),
            primary = Color(0xFFF4F4F5),
            onPrimary = Color(0xFF111216),
            onBackground = Color(0xFFF4F4F5),
            onSurface = Color(0xFFF4F4F5),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text(
                        text = "HandScroll",
                        fontSize = 30.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Управление TikTok жестами руки",
                        color = Color(0xFFA6A6AD),
                        fontSize = 16.sp,
                    )

                    Spacer(Modifier.height(28.dp))
                    StatusRow("Камера", cameraGranted)
                    HorizontalDivider(color = Color(0xFF26272C))
                    StatusRow("Управление экраном", accessibilityEnabled)
                    HorizontalDivider(color = Color(0xFF26272C))
                    StatusRow("Распознавание", runtime.running)

                    runtime.error?.let {
                        Spacer(Modifier.height(18.dp))
                        Text(
                            text = it,
                            color = Color(0xFFFFB4AB),
                            fontSize = 14.sp,
                        )
                    }

                    Spacer(Modifier.height(32.dp))
                    Text(
                        text = "Жесты",
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    GestureRow("↑", "Рука вверх", "Следующее видео")
                    GestureRow("↓", "Рука вниз", "Предыдущее видео")
                    GestureRow("👍", "Удержать", "Лайк")
                }

                Column {
                    when {
                        !accessibilityEnabled -> {
                            Button(
                                onClick = onRequestAccessibility,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Включить управление экраном")
                            }
                        }
                        runtime.running -> {
                            OutlinedButton(
                                onClick = onStop,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Остановить")
                            }
                        }
                        !cameraGranted -> {
                            Button(
                                onClick = ::requestCameraAndStart,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Разрешить камеру и запустить")
                            }
                        }
                        else -> {
                            Button(
                                onClick = onStart,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Text("Запустить TikTok")
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Видео с камеры обрабатывается только на устройстве.",
                        color = Color(0xFF85858D),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, fontSize = 15.sp)
        Text(
            text = if (enabled) "Готово" else "Не готово",
            color = if (enabled) Color(0xFF8FE39E) else Color(0xFFA6A6AD),
            fontSize = 14.sp,
        )
    }
}

@Composable
private fun GestureRow(symbol: String, gesture: String, action: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = symbol,
            fontSize = 24.sp,
            modifier = Modifier.padding(end = 14.dp),
        )
        Column {
            Text(text = gesture, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(text = action, fontSize = 13.sp, color = Color(0xFFA6A6AD))
        }
    }
}
