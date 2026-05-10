# 飞书 Channel 配置 & 测试清单

阶段 2 新增的飞书 channel 需要在飞书开放平台建一个自建应用,再把 App ID / App Secret 填进 app 设置页。本文给同事照做用,测试也只挑最能暴露问题的点,不求覆盖全。

---

## 一、大致流程

1. 在飞书开放平台建一个自建应用,开 WS 长连 + 消息权限 + 订阅 `im.message.receive_v1`。
2. 记下 App ID、App Secret。
3. 在真机 app 设置页填,存下来 → 重启 app。
4. 拉一个自己的 open_id 加到白名单(这一步用下面的小技巧,不用去接口里查)。
5. 按测试清单点一遍。

---

## 二、飞书开放平台配置

1. 打开 <https://open.feishu.cn/app>,点右上角"创建自建应用",名字随便(e.g. `ClawGUI-Dev`)。创建完进控制台。

2. **权限管理** → 开通以下权限(搜关键词即可):
   - `im:message`(接收和发送消息)
   - `im:message.group_at_msg`(接收群里 at 消息,群聊测试要这个)
   - `im:message.p2p_msg`(接收单聊消息)
   - `contact:user.base:readonly` *(可选,拉 open_id 用,不开也能测试,见下方"拿到自己的 open_id")*

3. **事件订阅**:
   - 订阅方式选 **长连接 (WebSocket)**,不用配回调 URL。
   - 添加事件:`im.message.receive_v1`(搜"接收消息")。

4. **应用发布**:
   - 应用控制台 → "版本管理与发布" → 创建版本 → 提交审核(自建应用一般管理员一键放行,没有管理员就联系运维)。
   - 发布完应用才能进群 / 被 at,光保存不发布不生效。

5. **邀请机器人进群**(测试群聊用):
   - 飞书客户端,进一个测试群 → 群设置 → 群机器人 → 添加机器人 → 搜刚才建的应用 → 添加。
   - 或直接单聊搜应用名,1:1 就能聊。

6. 回到控制台"凭证与基础信息",记下 **App ID** 和 **App Secret**。App Secret 只给我看一次,漏掉了要重置。

---

## 三、app 端配置

1. GitHub 拉最新 main,Android Studio 打开 → 真机安装。

2. 打开 app → 底栏"设置" → 滑到"飞书 Channel"卡。

3. 第一次配置建议先开 "允许所有 open_id"(开关打开),这样任何人 at 机器人都能触发任务,**方便拿到你自己的 open_id,后面会关掉**。

4. 填好 App ID、App Secret,点"保存"。

5. **重启 app**(杀掉进程或从最近任务划掉,再点图标启动)。Brain / VLM / 飞书的初始化都只在启动时跑。

6. 看 logcat(Android Studio 下方 Logcat 面板过滤 `FeishuChannel`):
   - 期望看到 `FeishuChannel started`。
   - 如果看到 `FeishuChannel start failed: ...`,大概率是 App ID / Secret 错了,或者应用没发布。

### 拿到自己的 open_id

1. 在飞书里单聊机器人 / 群里 at 机器人,随便发一句话(比如 "hello")。
2. 回 app → 底栏"收件" → 最顶上就是你刚发的那条,点开看 `sender_id` 字段 —— 就是你的 open_id(`ou_xxxxxx` 开头)。
3. 回设置页 → 飞书卡 → "允许的 open_id"框里填你的 open_id,**关闭"允许所有 open_id"** → 保存 → 重启 app。
4. 再发一条消息验证机器人仍能回复;让别的同学发一条,应该被 drop(logcat 过滤 `FeishuChannel` 看不到入站日志)。

---

## 四、测试清单

真机测,按这个顺序点一遍就行。有问题截 logcat 给我。

### A. 基本回归(不要因为加飞书把原功能搞坏)

1. **关飞书,app 内对话正常**
   - 关掉飞书开关(或者 App ID 留空),重启 app。
   - 对话页发 "hello",机器人应该回气泡(走 Brain 模型),悬浮球正常出现。
   - 期望:行为跟阶段 1 完全一致。

2. **关飞书,GUI 任务正常**
   - 对话页发 "打开设置"。
   - 期望:手机屏幕自动打开系统设置 app,app 内气泡回 "Task completed" 或类似。

### B. 飞书基本收发

3. **飞书 1:1 消息**
   - 单聊机器人发 "你好"。
   - 期望:机器人 **reply** 回你刚发那条(长按消息看 "回复",会看到引用关系,而不是新消息气泡)。
   - app 内 → 收件页,看到两条(入 + 出)。

4. **飞书群 at 机器人**
   - 在测试群里 at 机器人 + "你好"。
   - 期望同上,回复仍然是 reply 形式,挂在你那条消息下面。

5. **飞书触发 GUI**
   - 单聊或群里让机器人 "打开设置"(或 "打开微信"等装好的 app)。
   - 期望:手机屏幕自动跳转(app 必须在前台 + Shizuku 已授权 + 悬浮窗权限已给),机器人 reply 一句完成说明。
   - 如果手机锁屏或 app 在后台,可能失败,属于预期(ProcessLifecycleOwner 后台会 stop channel)。

### C. 白名单 & 互斥

6. **白名单 drop**
   - 关 "允许所有 open_id",白名单里只留你的 open_id。
   - 让同事 at 机器人发一条 —— 机器人不应有任何反应。
   - app 里 logcat 过滤 `FeishuChannel` 不会看到入站。收件页也不会出现那条 —— 因为 drop 发生在 `publishInbound` 之前。

7. **设备互斥**
   - app 内发 "打开设置",屏幕正在跳转时,马上单聊机器人发 "打开微信"。
   - 期望:飞书机器人回 "Error: 设备正在处理其他任务,请稍后再试。",app 内当前任务不受影响,正常跑完。
   - 反过来测一次也行:飞书先跑,然后 app 内输入新 msg,期望 app 气泡显示"设备占用中"类错误。

### D. 生命周期

8. **前后台切换**
   - app 前台:logcat 看到 `FeishuChannel started`。
   - Home 出去 → 等 30 秒以上 → logcat 应该看到 `FeishuChannel stopped`(ProcessLifecycleOwner 自带 30s 去抖)。
   - 回前台 → `FeishuChannel started` 再来一次。
   - 断连期间飞书里发消息,不会被收到;重连后重新能收。

9. **强杀**
   - 从最近任务划掉 app。飞书里发消息,机器人不回。
   - 重开 app,收件页之前的消息还在(jsonl 持久化了)。飞书里再发新消息,又能收。

### E. 持久化

10. **收件页历史**
    - 飞书发过几条消息后,强杀 app → 重开 → 底栏"收件",之前那几条应该都还在,按时间倒序排。
    - 点其中一条 feishu 消息 → 应该跳到对话页 `feishu:<chat_id>` 对应的 session,能看到完整对话历史。

### F. 异常路径(粗测)

11. **App Secret 填错**
    - 故意把 App Secret 改成随便什么字符串,重启 app。
    - 期望:app 不崩,logcat 看到 `FeishuChannel start failed: ...` 或 WS 连上之后报 invalid credentials,其他功能(in_app 对话、GUI)不受影响。

12. **无网络**
    - 飞一下飞行模式,重启 app。
    - 期望:WS 连不上会失败,app 照常能用 in_app(虽然 Brain API 也会失败,但不是飞书的锅)。关飞行模式回来,可能需要重启 app 让 channel 重连(阶段 2 暂不做自动重连,靠 SDK 自带的)。

---

## 五、什么时候可以认为阶段 2 通过

- A 组 2 项全过:没破坏原功能。
- B 组 3 项至少 1:1 (测 3) 通过:WS + reply 接通。
- C 组至少测 7 (互斥)过:多 channel 共享设备不打架。
- D 组至少测 8 (前后台)过:生命周期 OK。

剩下的(白名单、强杀、异常)报一下有没有问题即可,有坑我来跟。

---

## 六、常见坑 & 报告方式

- **机器人 at 不了 / 进不了群**:检查应用是否已发布,以及权限 `im:message.group_at_msg` 有没有开。
- **消息发出去机器人没反应**:先看 logcat 过滤 `FeishuChannel`:
  - 没有任何日志 → WS 没连上,检查 App ID/Secret 和网络。
  - 看到入站日志但没回复 → 看后面有没有 `feishu reply failed` 之类。
- **回复不是 reply 而是新消息**:看 logcat 入站有没有带 `message_id`,理论上飞书每条消息都有。如果 metadata 丢了 reply 会 fallback 到 create。
- **app 崩了**:直接把 logcat 的 crash stack 截过来。

报问题模板:
```
场景:[我在做什么,e.g. 飞书单聊发 "打开设置"]
期望:[应该发生啥]
实际:[实际发生啥]
Logcat:[过滤 FeishuChannel / ChannelDispatcher / AgentLoop 各贴一小段]
```
