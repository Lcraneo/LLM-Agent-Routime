# 微信大模型机器人实训项目

基于 Spring Boot 4 + Java 21 构建的全栈智能微信机器人，集成通义千问大模型、Function Calling 工具调用、自主规划型旅行 Agent、高德地图、语音交互与多轮对话记忆，可通过一句话目标自动生成完整出行手册。

## 技术栈

| 分类 | 技术 |
|---|---|
| 框架 | Spring Boot 4.1.0、Java 21 |
| 微信 SDK | wechat-ilink-sdk 2.3.3（扫码登录、收发消息） |
| 大模型 | 阿里云百炼 DashScope（qwen-plus 文本 / qwen-vl-plus 视觉 / wanx2.1 图片 / qwen3-asr 语音识别 / qwen-audio TTS） |
| 地图 | 高德 Web 服务 API（地理编码、路线规划、POI、静态图） |
| 出行 | 滴滴打车预估、心知天气、Tavily 联网搜索 |
| 存储 | MongoDB Reactive、MySQL、本地文件持久化 |
| 其他 | ZXing（二维码）、SILK 编码器（微信语音） |

## 已完成功能

### 1. 微信接入与多用户会话
- 接入微信 iLink SDK，扫码登录，支持收发文本 / 图片 / 语音消息。
- 多用户会话隔离：按 `sessionId` 隔离登录态与 token，扫码登录的 HTTP 请求走独立线程池不阻塞。
- 用户登录 token 持久化到 `./data/user-tokens`，服务重启后自动恢复会话。
- REST 管理接口见 `WeChatController`（`/api/wechat`）。

### 2. 大模型对话
- 接入阿里云百炼 DashScope，调用通义千问大模型（qwen-plus）实现文本对话。
- 支持视觉模型（qwen-vl-plus）识别用户发送的图片内容并综合回复。
- 通义万相（wanx2.1-t2i-turbo）文生图，按用户指令生成图片并发送回微信。

### 3. 多轮对话上下文记忆
- 完整保存所有交互消息，按 `conversationId` 隔离（微信场景为 `wechat:{fromUserId}:{toUserId}`）。
- 滑动窗口（默认 10 条）+ 动态摘要（LLM 优先，失败回退本地摘要器），优化上下文长度。
- 持久化到 `./data/conversations`，服务重启后自动恢复历史。详见 [docs/conversation-memory.md](docs/conversation-memory.md)。

### 4. 意图识别 & Function Calling
- `IntentClassifier` 自动判断用户意图：文本回复 / 语音回复 / 图片生成。
- `ToolRegistry` 自动注册 15 个工具，LLM 根据 JSON Schema 自主选择并调用，循环次数受 `llm.tool-max-rounds` 限制。详见 [docs/function-calling.md](docs/function-calling.md)。

### 5. 语音 ASR / TTS
- 语音识别（qwen3-asr-flash）：语音转文字。
- 语音合成（qwen-audio-3.0-tts-flash）：文字转语音，默认输出 MP3。
- SILK 编码器适配微信语音格式，通过 iLink `sendVoice`（`encode_type=7`）发送。

### 6. 天气查询
- 接入心知天气（Seniverse），支持实时天气与未来天气预报。
- 提供 JSON 与格式化文本两种输出形式（`/api/weather`）。

### 7. 联网搜索增强
- 集成 Tavily 联网搜索，可按配置开启搜索增强；默认关闭以降低回复延迟。

### 8. RAG 知识库
- 极简关键词检索版 RAG：启动时从 `./data/knowledge-base` 加载 `.txt`，按空行切块，检索时按查询词命中数打分取 Top-K。不依赖向量模型。

### 9. 消息路由与技能系统
- `MessageRouter` 三路路由：命中 Skill 关键词 → 命中 RAG 关键词 → 走 LLM。
- Skill 为关键词命中的确定性执行单元，响应快、零 token 成本，已注册 6 个技能。

### 10. 高德地图与静态地图
- 地理编码（geocode）、路线规划（plan_route）、POI 搜索（search_poi）、静态地图（static_map）。
- 静态地图本地缓存于 `./data/amap-static/`，以 SHA-256 哈希为文件名，支持浏览器直链访问（`/api/amap/static/{cacheKey}.png`）。
- 静态图标注：起点（S/绿色）、终点（E/红色）、途经点（1-9/蓝色）、单点（P/橙色）。

### 11. 滴滴打车预估
- 基于距离 / 时长估算快车、专车等车型价格区间；未配置官方凭证时使用本地估算模型。

### 12. 自主规划型旅行 Agent
- 用户输入一句话目标（如「做一份上海三日游完整出行方案」），Agent 自主拆解 6 大任务、调用 15 个工具，最终输出 8 大章节 Markdown 出行手册。
- 三层兜底保障：System Prompt 强制工具调用链 → `QwenChatService` 连续重复调用检测 + 结果缓存 → 代码层确定性 `fallbackBuild`（零幻觉）。
- 异地串台检测、空壳模板检测、24 小时结果缓存。
- 覆盖 10 个城市：上海、北京、杭州、成都、西安、广州、重庆、厦门、丽江、桂林。

### 13. 低延迟异步回复与实时反馈
- 收到微信消息后通过 iLink 输入态后台反馈「正在输入」，不发送可见确认文本。
- 回复任务按 `fromUserId:toUserId` 串行执行，不同会话并发处理，避免上下文错乱。
- 快速合并窗口（800ms）：单条消息快速回复，同时保留 5 秒完整窗口用于图文配对。详见 [docs/realtime-performance.md](docs/realtime-performance.md)。

### 14. 图文消息合并
- 同一会话 5 秒窗口内分别发送的文字和图片合并为一次多模态请求，交给视觉大模型基于「图片识别结果 + 文字意图」生成综合回复。详见 [docs/media-message-merge.md](docs/media-message-merge.md)。

### 15. 管理界面
- 启动后访问 `http://localhost:8080` 打开静态管理页面，可视化管理微信会话、扫码登录、查看消息。

## 工具与技能清单

### Function Calling 工具（15 个，自动注册到 ToolRegistry）

| 工具名 | 说明 |
|---|---|
| `query_weather` | 查询心知天气实时天气或未来预报 |
| `generate_image` | 调用通义万相生成图片 |
| `get_time` | 查询当前时间 |
| `web_search` | 联网搜索 |
| `search_attractions` | 城市景点知识库（5A/4A 列表，含门票 / 开放时间 / 游玩时长） |
| `search_foods` | 城市特色菜 + 本地人 TOP 餐厅推荐 |
| `search_tickets` | 往返车票方案（高铁 / 普速 / 机票 3 档） |
| `book_hotel` | 酒店预订方案（经济 / 舒适 / 高端 3 档） |
| `travel_tips` | 出行注意事项 & 智能行李清单（按天数×人数量化） |
| `geocode` | 地址 → 经纬度 |
| `plan_route` | 两点间距离 / 耗时 / 详情 |
| `static_map` | 高德静态 PNG 路线图 |
| `search_poi` | 周边 POI 搜索 |
| `estimate_ride` | 滴滴打车价格预估 |
| `build_itinerary` | 汇总所有工具返回，生成 8 章节完整 Markdown 行程手册 |

### 技能（6 个，关键词命中本地执行）

| 技能名 | 说明 |
|---|---|
| `calculator` | 计算器 |
| `joke` | 笑话 |
| `time` | 时间查询 |
| `weather_advisor` | 天气顾问 |
| `campus_event_planning` | 校园活动规划 |
| `travel_plan` | 旅游规划 |

## REST API 一览

| 模块 | 方法 | 路径 | 说明 |
|---|---|---|---|
| 微信 | GET | `/api/wechat/sessions` | 列出所有会话及登录状态 |
| 微信 | POST | `/api/wechat/{sessionId}/login` | 发起扫码登录 |
| 微信 | GET | `/api/wechat/{sessionId}/qrcode` | 获取登录二维码 PNG |
| 微信 | GET | `/api/wechat/{sessionId}/messages` | 获取会话消息快照 |
| 微信 | POST | `/api/wechat/{sessionId}/send` | 发送文本消息 |
| 微信 | POST | `/api/wechat/{sessionId}/send-voice` | 发送语音消息 |
| 微信 | PUT | `/api/wechat/{sessionId}/auto-reply/{enabled}` | 开关自动回复 |
| LLM | POST | `/api/llm/chat` | 文本对话 |
| LLM | POST | `/api/llm/chat-with-image` | 图片 + 文字对话 |
| LLM | POST | `/api/llm/generate-image` | 文生图 |
| 对话 | POST | `/api/conversation/chat` | 多轮对话（含记忆） |
| 对话 | GET | `/api/conversation/history` | 查询对话历史 |
| 对话 | DELETE | `/api/conversation/history` | 清空指定会话 |
| 工具 | GET | `/api/tool/list` | 查看已注册工具定义 |
| 工具 | POST | `/api/tool/chat` | 带多轮记忆的工具对话 |
| 意图 | POST | `/api/intent/classify` | 意图识别 |
| 路由 | POST | `/api/router/message` | 路由一条消息 |
| 路由 | GET | `/api/router/skills` | 列出已注册技能 |
| 路由 | GET | `/api/router/rag` | 列出已加载知识块 |
| 路由 | POST | `/api/router/rag/retrieve` | 按查询召回知识块 |
| 天气 | GET | `/api/weather/now` | 实时天气 |
| 天气 | GET | `/api/weather/daily` | 未来预报 |
| 天气 | GET | `/api/weather/now/formatted` | 格式化实时天气 |
| 天气 | GET | `/api/weather/daily/formatted` | 格式化预报 |
| 语音 | POST | `/api/voice/asr` | 语音转文字 |
| 语音 | POST | `/api/voice/tts` | 文字转语音（Base64） |
| 语音 | POST | `/api/voice/tts/audio` | 文字转语音（直接返回音频） |
| 地图 | GET | `/api/amap/static/{cacheKey}.png` | 获取静态图缓存 |
| 地图 | POST | `/api/amap/static/generate` | 直接生成静态图 |
| 旅行 | POST | `/api/travel/plan` | 一句话目标 → 完整出行手册 |
| 诊断 | GET | `/api/diagnose/voice` | 语音意图诊断 |

## 配置说明

### API Key 加载优先级
系统环境变量 → `.env.local` 文件 → `application.properties`。

> `.env.local` 文件必须严格命名且不能包含 UTF-8 BOM，否则 Key 无法加载。

### 关键配置项

| Key | 说明 |
|---|---|
| `DASHSCOPE_API_KEY` | 通义千问 / 万相 / 语音 API Key（必填） |
| `AMAP_API_KEY` | 高德 Web 服务类型 Key（旅行地图功能必填） |
| `TAVILY_API_KEY` | Tavily 联网搜索 Key（可选，默认关闭） |
| `DIDI_CLIENT_ID` / `DIDI_CLIENT_SECRET` / `DIDI_ACCESS_TOKEN` | 滴滴打车凭证（可选，未配置用本地估算模型） |
| `SENSE_API_KEY` | 心知天气 Key |

### 核心运行参数

```properties
# 微信 iLink
wechat.ilink.reply-threads=8
wechat.ilink.reply-queue-capacity=512
wechat.ilink.media-merge-window-ms=5000
wechat.ilink.media-fast-merge-window-ms=800
wechat.ilink.realtime-typing-enabled=true
wechat.ilink.voice-reply-enabled=true
wechat.ilink.image-generation-threads=2
wechat.ilink.token-storage-dir=./data/user-tokens

# 多轮对话记忆
conversation.window-size=10
conversation.summary-trigger=6
conversation.summary.llm-enabled=true

# 大模型
llm.text-model=qwen-plus
llm.vision-model=qwen-vl-plus
llm.tool-max-rounds=5

# 旅行 Agent
travel.agent-max-rounds=12
```

完整配置见 [src/main/resources/application.properties](src/main/resources/application.properties)。

## 快速开始

1. 准备环境：JDK 21+、Maven。
2. 配置 API Key：在项目根目录创建 `.env.local`，填入 `DASHSCOPE_API_KEY` 和 `AMAP_API_KEY`（Web 服务类型）。
3. 微信语音需将 SILK 编码器放到 `bin/silk-encoder-x64.exe`。
4. 启动服务：

```bash
./mvnw spring-boot:run
```

5. 浏览器打开 `http://localhost:8080`，扫码登录微信。
6. 调用旅行 Agent（示例）：

```bash
curl -X POST http://localhost:8080/api/travel/plan \
  -H "Content-Type: application/json" \
  -d '{"goal":"做一份上海三日游完整出行方案"}'
```

> 8080 端口可能存在进程残留，重启前可用 PowerShell 命令强制终止占用进程。

## 受限说明
- 多个外部服务（DashScope、Tavily、Seniverse、高德、滴滴）需要分别配置 API Key，未配置时对应功能会自动禁用或返回错误提示。
- 高德 API 错误码 `10009` 表示 Key 类型不匹配，需重新申请 Web 服务类型 Key；`10005/10006` 表示配额超限。
- 通义千问 API 401/403 错误表示 Key 失效或额度用完，需更换 Key 或充值。

## 设计文档
- [多轮对话上下文记忆机制](docs/conversation-memory.md)
- [Function Calling / Tool Use](docs/function-calling.md)
- [iLink 图文/文图消息合并机制](docs/media-message-merge.md)
- [低延迟与实时反馈设计](docs/realtime-performance.md)
