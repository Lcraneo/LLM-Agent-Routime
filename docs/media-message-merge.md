# iLink 图文/文图消息合并机制

## 目标

同一用户在同一会话的 5 秒窗口内分别发送文字和图片时，将两条消息合并为一次图文输入，交给视觉大模型基于“图片识别结果 + 文字意图”生成综合回复，避免文字和图片被当成两条独立消息处理。

## 消息关联标识规范

合并窗口由以下关联 ID 标识：

```text
ilink-media://{fromUserId}/{toUserId}/{firstMessageId}/{windowStartEpochMs}
```

字段说明：

- `fromUserId`：消息发送者，消息关联的主体。
- `toUserId`：机器人接收方，用于区分不同会话方向。
- `firstMessageId`：窗口内第一条消息的 `message_id`；为空时使用 `none`。
- `windowStartEpochMs`：窗口开始时间，毫秒级 Unix 时间戳。

缓存键使用 `{fromUserId}::{toUserId}`，保证不同用户、不同会话的缓存互不串扰。

## 接收缓存策略

- 合并窗口默认 `5000ms`，可通过 `wechat.ilink.media-merge-window-ms` 调整。
- 单条文字或图片默认在 `wechat.ilink.media-fast-merge-window-ms=800ms` 后触发回复；
  同批或 800ms 内到达的图文仍会合并，完整 5 秒窗口用于较慢的图文配对。
- 内存中最多保留 `256` 个窗口，可通过 `wechat.ilink.media-merge-max-buffers` 调整；超出后淘汰最早窗口。
- 窗口内凑齐“图片 + 文字”时立即合并并触发回复；快速窗口到期仍只有文字或只有图片时，按普通单条消息触发回复。
- 窗口只缓存消息关联信息和原始消息引用，不缓存图片二进制，避免长时间占用内存。

## 内容融合算法

1. 按 `receivedAtMs` 和 `messageId` 对同批消息排序。
2. 将窗口内的文字片段合并为一条 `fusedText`，多段文字以换行连接。
3. 如果意图为普通文字/语音回复，构造视觉 Prompt：

```text
用户同时发送了图片和以下文字，请结合图片内容回答用户的问题：
{fusedText}
```

4. 如果文字意图为图片生成，先让视觉模型描述原图，再融合为用户生成提示词：

```text
请参考原图描述生成图片：
原图描述：{imageDescription}
用户要求：{userPrompt}
```

5. 最终按意图输出文字回复、语音回复或图片生成任务。
