package com.youkeda.project.config;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;
import java.util.Locale;

public class CharsetResponseWrapper extends HttpServletResponseWrapper {

    private boolean charsetSet = false;

    public CharsetResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void setContentType(String type) {
        super.setContentType(appendCharset(type));
        charsetSet = true;
    }

    @Override
    public void setHeader(String name, String value) {
        if (isContentTypeHeader(name)) {
            value = appendCharset(value);
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if (isContentTypeHeader(name)) {
            value = appendCharset(value);
        }
        super.addHeader(name, value);
    }

    private boolean isContentTypeHeader(String name) {
        return "Content-Type".equalsIgnoreCase(name);
    }

    private String appendCharset(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return contentType;
        }
        String lower = contentType.toLowerCase(Locale.ROOT);
        if (lower.contains("charset")) {
            return contentType;
        }
        if (lower.startsWith("text/html") || lower.startsWith("application/json")) {
            return contentType + ";charset=UTF-8";
        }
        return contentType;
    }
}
