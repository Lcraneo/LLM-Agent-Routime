package com.youkeda.project.wechat;

import com.github.wechat.ilink.sdk.core.exception.ILinkException;
import com.youkeda.project.llm.LlmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class WeChatExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WeChatExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiError handleBadRequest(IllegalArgumentException e) {
        return new ApiError(e.getMessage());
    }

    @ExceptionHandler(LlmException.class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    public ApiError handleLlmError(LlmException e) {
        log.warn("LLM service error: {}", e.getMessage());
        return new ApiError("图像/模型服务调用失败：" + e.getMessage());
    }

    @ExceptionHandler(ILinkException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiError handleSdkError(ILinkException e) {
        log.warn("WeChat SDK error: {}", e.getMessage());
        return new ApiError(e.getMessage());
    }

    @ExceptionHandler(WeChatOperationException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleOperationError(WeChatOperationException e) {
        log.error("WeChat operation error", e);
        return new ApiError(e.getMessage());
    }

    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiError handleUnexpected(RuntimeException e) {
        log.error("Unexpected WeChat error", e);
        return new ApiError(e.getMessage());
    }
}
