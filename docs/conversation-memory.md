# 多轮对话上下文记忆机制

## 目标

让 AI Agent 不再只处理单轮输入，而是能基于完整对话历史理解当前问题。系统会完整保存所有交互消息，并在请求大模型前把“早期摘要 + 最近窗口”融合成优化上下文。

## 对话历史存储

- 每条消息保存为 `ConversationMessage`：`role`、`content`、`type`、`timestampMs`、`messageId`。
- 对话按 `conversationId` 隔离，微信场景使用 `wechat:{fromUserId}:{toUserId}`。
- 默认持久化到 `./data/conversations`，文件名为 `SHA-256(conversationId).json`，使用临时文件 + 原子替换写入。
- 服务重启后会自动从磁盘恢复历史，并继续追加新消息。

## 滑动窗口

- `conversation.window-size` 默认 `10`，表示发送给模型的最近消息数。
- 超过窗口的消息不会丢失，仍完整保存在持久化存储中。
- 当“超出窗口的早期消息数”达到 `conversation.summary-trigger`（默认 6）时，触发一次摘要更新。

## 动态摘要

- `conversation.summary.llm-enabled=true` 时优先调用大模型生成语义摘要。
- LLM 不可用或调用失败时，使用本地摘要器提取用户关键问题与助手关键回答，保证功能不中断。
- 摘要长度受 `conversation.summary-max-chars` 限制，默认 800 字符。
- 摘要与最近窗口组合后形成如下结构：

```text
[早期对话摘要]
{summary}

[最近对话]
用户: ...
助手: ...
```

## REST API

| Method | Path | Description |
|---|---|---|
| POST | `/api/conversation/chat` | 多轮对话，可传 `sessionId`、`message`、`imageBase64` |
| GET | `/api/conversation/history` | 查询完整对话历史 |
| DELETE | `/api/conversation/history` | 清空指定会话 |

配置项：

```properties
conversation.storage-dir=./data/conversations
conversation.window-size=10
conversation.summary-enabled=true
conversation.summary-trigger=6
conversation.summary-max-chars=800
conversation.summary.llm-enabled=true
```
