package com.mobai.jm.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Process
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mobai.jm.util.CrashHandler
import com.mobai.jm.ui.theme.MoBaiTheme

/**
 * 崩溃提示页（独立进程 :crash，主进程被杀后依然可见）：
 * 打开即自动复制崩溃信息，并引导前往反馈站点。
 */
class CrashActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val info = intent.getStringExtra(EXTRA_INFO).orEmpty()
        copyInfo(info)
        setContent {
            MoBaiTheme {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(24.dp),
                    ) {
                        Spacer(Modifier.height(24.dp))
                        Text(
                            "😵 应用意外崩溃",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            "崩溃信息已复制到剪贴板",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "请前往 ${CrashHandler.FEEDBACK_SITE} 反馈（粘贴崩溃信息即可）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(16.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = info.take(4000),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(12.dp),
                            )
                        }
                        Spacer(Modifier.height(20.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            OutlinedButton(
                                onClick = { copyInfo(info) },
                                modifier = Modifier.weight(1f),
                            ) { Text("复制信息") }
                            Button(
                                onClick = {
                                    finishAndRemoveTask()
                                    Process.killProcess(Process.myPid())
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("退出应用") }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }

    private fun copyInfo(text: String) {
        runCatching {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("禁漫姬崩溃信息", text))
        }
    }

    companion object {
        const val EXTRA_INFO = "crash_info"
    }
}
