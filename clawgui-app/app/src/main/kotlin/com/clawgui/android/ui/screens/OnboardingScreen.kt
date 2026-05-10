package com.clawgui.android.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Onboarding screen — shown on first launch.
 * Walks user through Shizuku setup steps.
 */
@Composable
fun OnboardingScreen(
    onComplete: () -> Unit,
    onRequestShizuku: () -> Unit,
) {
    var page by remember { mutableIntStateOf(0) }

    val pages = listOf(
        OnboardingPage(
            title = "欢迎使用 ClawGUI",
            description = "通过对话方式让 AI 自动操作你的手机\n\n支持中文指令，理解屏幕内容，智能完成任务。"
        ),
        OnboardingPage(
            title = "连接 Shizuku",
            description = "ClawGUI 通过 Shizuku 获得操控手机的能力。\n\n" +
                "首次需要连接电脑，执行一条 ADB 命令：\n" +
                "adb shell sh /sdcard/Android/data/moe.shizuku.privileged.api/start.sh\n\n" +
                "之后可以在手机上用「无线调试」独立启动，无需电脑。"
        ),
        OnboardingPage(
            title = "授权 ClawGUI",
            description = "Shizuku 启动后，ClawGUI 会弹出授权请求。\n\n点击「授权」即可开始使用。"
        )
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Spacer(modifier = Modifier.height(48.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = pages[page].title,
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = pages[page].description,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onComplete) {
                Text("跳过")
            }

            // Page indicators
            Row {
                pages.indices.forEach { index ->
                    Surface(
                        modifier = Modifier
                            .padding(horizontal = 4.dp)
                            .size(if (index == page) 10.dp else 6.dp),
                        shape = MaterialTheme.shapes.small,
                        color = if (index == page) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    ) {}
                }
            }

            Button(
                onClick = {
                    if (page < pages.size - 1) {
                        page++
                        if (page == 2) onRequestShizuku()
                    } else {
                        onComplete()
                    }
                }
            ) {
                Text(if (page < pages.size - 1) "下一步" else "开始使用")
            }
        }
    }
}

private data class OnboardingPage(val title: String, val description: String)
