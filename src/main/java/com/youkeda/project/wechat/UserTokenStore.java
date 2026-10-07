package com.youkeda.project.wechat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wechat.ilink.sdk.core.login.LoginContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 用户扫码登录信息持久化。
 *
 * <p>ilink 的 LoginContext 保存了 botToken 等恢复登录所需字段。本类将其序列化为 JSON
 * 落盘，按 sessionId 隔离存储；下次启动时读出并用 resumeContext 重建 client，
 * 用户无需再次扫码。
 */
@Service
public class UserTokenStore {

    private static final Logger log = LoggerFactory.getLogger(UserTokenStore.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path storageDir;

    public UserTokenStore(
        @Value("${wechat.ilink.token-storage-dir:./data/user-tokens}") String storageDir) {
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    /** 保存（或覆盖）某个 session 的登录 token。 */
    public synchronized void save(String sessionId, LoginContext loginContext) {
        if (sessionId == null || sessionId.isBlank() || loginContext == null) {
            return;
        }
        TokenData data =
            new TokenData(
                sessionId,
                loginContext.getBotToken(),
                loginContext.getUserId(),
                loginContext.getBotId(),
                loginContext.getBaseUrl());
        try {
            Files.createDirectories(storageDir);
            Path file = fileFor(sessionId);
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writeValue(temp.toFile(), data);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            log.warn("Failed to persist token for session {}: {}", sessionId, e.getMessage());
        }
    }

    /** 读取某个 session 的登录 token，文件不存在或解析失败时返回 null。 */
    public synchronized LoginContext load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return null;
        }
        Path file = fileFor(sessionId);
        if (!Files.exists(file)) {
            return null;
        }
        TokenData data = read(file);
        return data == null ? null : toLoginContext(data);
    }

    /** 读取所有已保存的 token，返回 sessionId -> LoginContext。 */
    public synchronized Map<String, LoginContext> loadAll() {
        Map<String, LoginContext> all = new HashMap<>();
        if (!Files.isDirectory(storageDir)) {
            return all;
        }
        try (var stream = Files.list(storageDir)) {
            stream
                .filter(p -> p.toString().endsWith(".json"))
                .forEach(
                    p -> {
                        TokenData data = read(p);
                        if (data != null && data.sessionId() != null) {
                            LoginContext ctx = toLoginContext(data);
                            if (ctx != null) {
                                all.put(data.sessionId(), ctx);
                            }
                        }
                    });
        } catch (IOException e) {
            log.warn("Failed to list tokens in {}: {}", storageDir, e.getMessage());
        }
        return all;
    }

    /** 删除某个 session 的 token 文件（登出 / 重新扫码时调用）。 */
    public synchronized void delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(sessionId));
        } catch (IOException e) {
            log.warn("Failed to delete token for session {}: {}", sessionId, e.getMessage());
        }
    }

    private TokenData read(Path file) {
        try {
            return objectMapper.readValue(file.toFile(), TokenData.class);
        } catch (IOException e) {
            log.warn("Failed to read token {}: {}", file, e.getMessage());
            return null;
        }
    }

    private LoginContext toLoginContext(TokenData data) {
        if (data == null || data.botToken() == null || data.botToken().isBlank()) {
            return null;
        }
        return new LoginContext(
            data.botToken(), data.userId(), data.botId(), data.baseUrl());
    }

    /** 文件名用 sessionId 的 SHA-256，避免用户自定义 sessionId 带来路径穿越问题。 */
    private Path fileFor(String sessionId) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sessionId.getBytes(StandardCharsets.UTF_8));
            return storageDir.resolve(HexFormat.of().formatHex(hash) + ".json");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** 落盘的 token 数据，包含 sessionId 以便 loadAll 时能还原映射关系。 */
    private record TokenData(
        String sessionId, String botToken, String userId, String botId, String baseUrl) {}
}
