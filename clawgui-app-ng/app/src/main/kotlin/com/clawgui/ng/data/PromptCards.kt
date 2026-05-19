package com.clawgui.ng.data

/**
 * Default discover-page cards. Pure data; UI picks an accent gradient from
 * accentHue so cards feel hand-picked but stay consistent with the palette.
 */
object DefaultPromptCards {
    val list: List<PromptCard> = listOf(
        PromptCard(
            id = "food_delivery",
            emoji = "\uD83C\uDF5C",
            title = "帮我点份外卖",
            subtitle = "打开美团 / 饿了么,挑一家附近评分高的",
            prompt = "打开外卖 App,在附近评分 4.5 以上的店里挑一家,帮我下单一份能吃饱的午餐。",
            accentHue = 12,
        ),
        PromptCard(
            id = "shake_off_meeting",
            emoji = "\uD83D\uDDD3\uFE0F",
            title = "整理今天的日程",
            subtitle = "打开日历,看看下午有哪些会要参加",
            prompt = "打开系统日历,告诉我今天下午到晚上的所有日程,并提醒最近的一项。",
            accentHue = 200,
        ),
        PromptCard(
            id = "douyin_break",
            emoji = "\uD83C\uDFAC",
            title = "刷会儿短视频",
            subtitle = "打开抖音,自动滑 5 分钟",
            prompt = "打开抖音,自动浏览推荐流 5 分钟,期间遇到广告就跳过。",
            accentHue = 320,
        ),
        PromptCard(
            id = "wechat_summary",
            emoji = "\uD83D\uDCAC",
            title = "看看未读消息",
            subtitle = "汇总最近 24 小时的群聊重要信息",
            prompt = "打开微信,扫一眼未读的群聊,告诉我哪些 @ 了我或者需要回复。",
            accentHue = 140,
        ),
        PromptCard(
            id = "xhs_post",
            emoji = "\uD83D\uDCD3",
            title = "发小红书笔记",
            subtitle = "Agent 会反问你「想发什么内容」",
            // Deliberately vague — triggers the Ask flow so the user can see
            // the mid-task clarification UI in action.
            prompt = "帮我在小红书发一条笔记",
            accentHue = 0,
        ),
        PromptCard(
            id = "settings_optimize",
            emoji = "\u2699\uFE0F",
            title = "省电模式",
            subtitle = "把屏幕亮度、自动同步等设置调到省电",
            prompt = "打开系统设置,将屏幕亮度调到 30%,关闭自动同步和蓝牙。",
            accentHue = 30,
        ),
    )
}
