# 低延迟与实时反馈设计

## 目标

1. 缩短 agent 回复等待时间。
2. 支持语音形式反馈。
3. 用户连续发送消息时即时确认，避免长时间无反馈。
4. 高并发场景下稳定、低延迟、按会话保序。

## 异步回复调度

`ReplyDispatcher` 使用有界线程池和 `CompletableFuture` 链维护每个会话的处理队列：

- 每个 `fromUserId:toUserId` 会话一个任务链，保证同一会话回复顺序不被打乱。
- 不同会话可以并发处理，线程数由 `wechat.ilink.reply-threads` 配置。
- 队列大小由 `wechat.ilink.reply-queue-capacity` 限制，队列满时使用 `CallerRunsPolicy`
  进行背压，避免消息无限堆积。
- 生图任务使用独立线程池，默认 2 个线程，不再被单线程阻塞。

## 后台输入态反馈

收到微信消息后，通过 iLink 输入态在后台反馈，不在微信或管理界面展示可见确认文本：

- 消息到达后调用 `startTyping`，显示“对方正在输入”状态。
- 同一会话的确认有冷却时间 `wechat.ilink.typing-cooldown-ms`，避免频繁请求。
- 回复任务开始和生图任务执行期间会再次进入输入态，任务结束后调用 `stopTyping`。
- 机器人自身发出的消息不会进入 `/api/wechat/messages`，不会出现在管理界面。

这样既给用户即时反馈，又不占用会话消息流。

## 快速合并窗口

图文合并完整窗口仍为 5000ms，但新增快速窗口：

- `wechat.ilink.media-fast-merge-window-ms=800ms`
- 单条文字或图片在快速窗口到期后立即回复。
- 同批或快速窗口内到达的“图片 + 文字”仍合并为一次多模态请求。
- 需要更严格的 5 秒配对时，可将快速窗口调回 5000ms。

这样既保留图文合并能力，又避免所有单条消息都必须等待 5 秒。

## 延迟优化

- 默认关闭 Tavily 强制搜索（`wechat.ilink.search-before-reply=false`），避免外部
  搜索拖慢普通回复。
- 天气、生图等能力通过 Function Calling 自动选择，不额外调用无关服务。
- 语音回复默认使用 MP3（`tts.format=mp3`），微信链路直接发送音频文件。
- 微信语音发送使用 `encode_type=7`，让微信客户端按 MP3 解析，不再误标为 SILK。

## 配置

```properties
wechat.ilink.reply-threads=8
wechat.ilink.reply-queue-capacity=512
wechat.ilink.realtime-typing-enabled=true
wechat.ilink.typing-cooldown-ms=1200
wechat.ilink.media-merge-window-ms=5000
wechat.ilink.media-fast-merge-window-ms=800
wechat.ilink.search-before-reply=false
wechat.ilink.voice-reply-enabled=true
wechat.ilink.image-generation-threads=2
```

## 验证

- `ReplyDispatcherTest` 验证同一会话任务按顺序执行、待处理计数正确。
- `MediaMessageMergerTest` 验证快速窗口下单条文本提前回复，同时图文同批仍合并。
- `mvn test` 全量回归通过。
