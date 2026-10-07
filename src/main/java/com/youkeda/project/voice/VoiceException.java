package com.youkeda.project.voice;

public class VoiceException extends RuntimeException {

    public VoiceException(String message) {
        super(message);
    }

    public VoiceException(String message, Throwable cause) {
        super(message, cause);
    }
}
