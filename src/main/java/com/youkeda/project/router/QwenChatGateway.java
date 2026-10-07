package com.youkeda.project.router;

import com.youkeda.project.llm.QwenChatService;
import org.springframework.stereotype.Component;

/**
 * 基于通义千问的 {@link ChatGateway} 实现，委托给 {@link QwenChatService#chat(String)}。
 */
@Component
public class QwenChatGateway implements ChatGateway {

    private final QwenChatService qwenChatService;

    public QwenChatGateway(QwenChatService qwenChatService) {
        this.qwenChatService = qwenChatService;
    }

    @Override
    public String chat(String prompt) {
        return qwenChatService.chat(prompt);
    }
}
