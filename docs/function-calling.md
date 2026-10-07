# Function Calling / Tool Use

## 目标

让大模型根据用户意图自动选择并调用自定义工具，再把工具执行结果融入最终回复。
当前项目使用阿里云 DashScope OpenAI 兼容协议，工具描述采用 JSON Schema。

## 已注册工具

| 工具名 | 说明 | 必填参数 |
|---|---|---|
| `query_weather` | 查询心知天气实时天气或未来预报 | `location` |
| `generate_image` | 调用通义万相生成图片 | `prompt` |

`query_weather` 支持 `type=now|daily` 和 `days` 参数，`location` 可以是城市名、
拼音或 `latitude:longitude`。`generate_image` 支持 `negativePrompt`、`size`
和 `n` 参数。

## JSON Schema

每个工具通过 `AgentTool.parameters()` 返回 OpenAI 兼容的 JSON Schema，例如：

```json
{
  "type": "object",
  "properties": {
    "location": {"type": "string", "description": "城市名或经纬度"},
    "type": {"type": "string", "enum": ["now", "daily"]}
  },
  "required": ["location"]
}
```

发送给 DashScope 的请求体包含：

```json
{
  "model": "qwen-plus",
  "messages": [{"role": "user", "content": "北京明天天气怎么样"}],
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "query_weather",
        "description": "查询指定城市的实时天气或未来天气预报",
        "parameters": {
          "type": "object",
          "properties": {},
          "required": ["location"]
        }
      }
    }
  ],
  "tool_choice": "auto"
}
```

## 工具调用循环

1. 模型返回 `choices[0].message.tool_calls`。
2. 服务端把 assistant 消息（含 `tool_calls`）追加到消息列表。
3. 逐个执行工具，把 `{role: "tool", tool_call_id, content}` 追加到消息列表。
4. 再次请求模型，直到返回不包含 `tool_calls` 的最终回复。
5. 循环次数受 `llm.tool-max-rounds` 限制，默认 5。

图片工具执行成功后，`ToolChatResult.imageUrl` 会携带图片地址，微信链路会下载
图片并作为图片消息发送，避免只向用户返回一个 URL。

## REST 接口

查看工具定义：

```http
GET /api/tool/list
```

带多轮记忆的工具对话：

```http
POST /api/tool/chat
Content-Type: application/json

{
  "sessionId": "wechat-demo",
  "message": "北京明天天气怎么样"
}
```

返回示例：

```json
{
  "reply": "北京明天多云，气温 25~32℃。",
  "imageUrl": null,
  "calledTools": ["query_weather"],
  "sessionId": "wechat-demo",
  "totalMessages": 2,
  "windowSize": 10,
  "summary": null
}
```

## 微信接入

微信文本消息先经过 `IntentClassifier` 判断回复方式（文字/语音/生图），随后文本
回复统一走 `chatWithTools`。生图意图在后台线程执行工具调用，通过输入态反馈
处理状态，成功后再发送图片和模型生成的说明文字。语音意图的最终文本会
通过 Bailian TTS 生成 MP3，再调用 iLink `sendVoice` 返回给用户。

## 配置

```properties
llm.tool-max-rounds=5
tts.format=mp3
```

`tts.format=mp3` 确保 AI 语音回复默认输出 MP3 文件。
