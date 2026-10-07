package com.youkeda.project.wechat;

import java.util.List;

public record MergedIncoming(
    String associationId,
    String fromUserId,
    String toUserId,
    String fusedText,
    List<IncomingMessage> parts) {

    public boolean isMergedImageText() {
        return firstImage() != null && firstText() != null;
    }

    public IncomingMessage firstImage() {
        if (parts == null) {
            return null;
        }
        for (IncomingMessage part : parts) {
            if ("image".equals(part.received().type())) {
                return part;
            }
        }
        return null;
    }

    public IncomingMessage firstText() {
        if (parts == null) {
            return null;
        }
        for (IncomingMessage part : parts) {
            if ("text".equals(part.received().type())) {
                return part;
            }
        }
        return null;
    }
}
