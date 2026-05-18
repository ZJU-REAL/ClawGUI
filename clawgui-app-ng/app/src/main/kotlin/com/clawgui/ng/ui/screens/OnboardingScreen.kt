package com.clawgui.ng.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.clawgui.ng.ui.theme.ClawTheme
import kotlinx.coroutines.launch

private data class Page(val emoji: String, val title: String, val body: String)

private val pages = listOf(
    Page("✨", "认识 ClawGUI", "会思考、会动手的端侧 Agent,陪你玩转手机。"),
    Page("🔓", "由 Shizuku 驱动", "一次授权,无需 Root 即可操控 Android。"),
    Page("🎯", "全程掌控", "灵动岛实时显示每一步执行,你随时可以叫停。"),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OnboardingScreen(onFinish: () -> Unit) {
    val extras = ClawTheme.extras
    val brush = Brush.linearGradient(
        listOf(extras.gradientStart.copy(alpha = 0.18f),
            extras.gradientEnd.copy(alpha = 0.10f),
            MaterialTheme.colorScheme.background)
    )
    val pagerState = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()

    Surface(
        color = Color.Transparent,
        modifier = Modifier.fillMaxSize().background(brush),
    ) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onFinish) { Text("跳过") }
            }
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { i ->
                PageContent(pages[i])
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                PageIndicator(pagerState.currentPage, pages.size)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick = {
                        if (pagerState.currentPage == pages.lastIndex) onFinish()
                        else scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                    },
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(
                        if (pagerState.currentPage == pages.lastIndex) "开始使用" else "下一步",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageContent(page: Page) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(132.dp)
                .clip(RoundedCornerShape(46.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(page.emoji, style = MaterialTheme.typography.displayLarge)
        }
        Spacer(Modifier.height(36.dp))
        Text(page.title, style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            page.body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
    }
}

@Composable
private fun PageIndicator(active: Int, total: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { i ->
            val dotWidth by animateDpAsState(
                targetValue = if (i == active) 22.dp else 8.dp, label = "ind",
            )
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .height(8.dp)
                    .width(dotWidth)
                    .clip(CircleShape)
                    .background(
                        if (i == active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                    )
            )
        }
    }
}
