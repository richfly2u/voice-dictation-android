package com.voicedictation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 啟動無障礙服務
        val intent = Intent(this, FloatService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        setContent {
            VoiceDictationTheme()
        }
    }
}

@Composable
fun VoiceDictationTheme() {
    var overlayPermissionGranted by remember { mutableStateOf(checkOverlayPermission()) }
    var accessibilityEnabled by remember { mutableStateOf(checkAccessibilityEnabled()) }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayPermissionGranted = checkOverlayPermission()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0a0a1a))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "🎙 語音輸入",
            color = Color(0xFFe8e8ed),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "全域懸浮球 · AI 潤稿",
            color = Color(0xFF888888),
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(40.dp))

        // 懸浮窗權限
        PermissionCard(
            title = "懸浮窗權限",
            description = "允許在其他 App 上層顯示懸浮球",
            icon = "🔘",
            granted = overlayPermissionGranted,
            onGrant = {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            }
        )
        Spacer(modifier = Modifier.height(16.dp))

        // 無障礙服務
        PermissionCard(
            title = "無障礙服務",
            description = "允許全域偵測輸入框，自動貼上潤稿結果",
            icon = "♿",
            granted = accessibilityEnabled,
            onGrant = {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
            }
        )
        Spacer(modifier = Modifier.height(40.dp))

        if (!overlayPermissionGranted || !accessibilityEnabled) {
            Text(
                text = "⚠️ 請開啟以上兩項權限以獲得完整功能",
                color = Color(0xFFf59e0b),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        } else {
            Text(
                text = "✅ 權限已就緒！\n前往任何 App → 點擊懸浮球 🎤 即可開始說話",
                color = Color(0xFF34d399),
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = "使用方式：\n1. 打開任何 App\n2. 點擊螢幕上的 🎤 懸浮球\n3. 說話 → AI 自動潤稿\n4. 自動貼入輸入框",
            color = Color(0xFF888888),
            fontSize = 13.sp,
            textAlign = TextAlign.Start,
            lineHeight = 22.sp
        )
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    icon: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (granted)
                Color(0xFF1a1a2e)
            else
                Color(0xFF1a1a2e)
        ),
        border = if (granted)
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF34d399))
        else
            androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF333355))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onGrant() }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = icon, fontSize = 28.sp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = Color(0xFFe8e8ed),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = description,
                    color = Color(0xFF888888),
                    fontSize = 12.sp
                )
            }
            Text(
                text = if (granted) "✅ 已開啟" else "→ 開啟",
                color = if (granted) Color(0xFF34d399) else Color(0xFF6c5ce7),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
