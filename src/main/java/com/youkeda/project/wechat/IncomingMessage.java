package com.youkeda.project.wechat;

import com.github.wechat.ilink.sdk.core.model.WeixinMessage;

public record IncomingMessage(ReceivedMessage received, WeixinMessage raw) {}
