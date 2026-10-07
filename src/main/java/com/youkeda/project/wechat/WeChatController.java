package com.youkeda.project.wechat;

import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wechat")
public class WeChatController {

    private final WeChatService weChatService;

    public WeChatController(WeChatService weChatService) {
        this.weChatService = weChatService;
    }

    /** 列出所有会话及其登录状态。 */
    @GetMapping("/sessions")
    public List<Map<String, Object>> sessions() {
        return weChatService.listSessions();
    }

    @PostMapping("/{sessionId}/login")
    public WeChatStatus login(@PathVariable String sessionId) {
        return weChatService.startLogin(sessionId);
    }

    @GetMapping("/{sessionId}/status")
    public WeChatStatus status(@PathVariable String sessionId) {
        return weChatService.currentStatus(sessionId);
    }

    @GetMapping(value = "/{sessionId}/qrcode", produces = MediaType.IMAGE_PNG_VALUE)
    public byte[] qrcode(@PathVariable String sessionId) {
        return weChatService.renderQrCodePng(sessionId);
    }

    @GetMapping("/{sessionId}/messages")
    public List<ReceivedMessage> messages(@PathVariable String sessionId) {
        return weChatService.snapshotMessages(sessionId);
    }

    @PostMapping("/{sessionId}/poll")
    public List<ReceivedMessage> poll(@PathVariable String sessionId) {
        return weChatService.pollMessages(sessionId);
    }

    @PostMapping("/{sessionId}/send")
    public SendResult send(
        @PathVariable String sessionId, @RequestBody SendMessageRequest request) {
        return weChatService.sendText(sessionId, request.toUserId(), request.text());
    }

    @PostMapping("/{sessionId}/send-voice")
    public SendResult sendVoice(
        @PathVariable String sessionId, @RequestBody SendVoiceRequest request) {
        return weChatService.sendVoiceReply(
            sessionId, request.toUserId(), request.text(), request.format());
    }

    @PutMapping("/{sessionId}/auto-reply/{enabled}")
    public WeChatStatus setAutoReply(
        @PathVariable String sessionId, @PathVariable boolean enabled) {
        return weChatService.setAutoReply(sessionId, enabled);
    }
}
