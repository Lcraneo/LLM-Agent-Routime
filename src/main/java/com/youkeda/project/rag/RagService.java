package com.youkeda.project.rag;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 极简关键词检索版 RAG。
 *
 * <p>启动时从 {@code rag.knowledge-dir} 加载所有 {@code .txt} 知识库文件，按空行切块；
 * 检索时对查询分词，统计每个块命中的查询词数作为得分，取 Top-K。
 * 不依赖向量模型，纯粹的关键词召回，适合演示 RAG 增强 Prompt 的全流程。
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    /** 分词分隔符：中英文标点与空白 */
    private static final Pattern TOKEN_SPLIT = Pattern.compile("[\\s\\p{Punct}，。、；：！？“”‘’（）【】《》·—…]+");

    private final List<RagChunk> chunks = new ArrayList<>();
    private final Set<String> domainKeywords;
    private final int topK;
    private final int maxChars;

    public RagService(
            @Value("${rag.knowledge-dir:./data/knowledge-base}") String knowledgeDir,
            @Value("${rag.top-k:3}") int topK,
            @Value("${rag.max-chars:800}") int maxChars,
            @Value("${rag.domain-keywords:退款,退货,物流,配送,发票,售后,商品,订单,价格,多少钱,会员}") String domainKeywordsCsv) {
        this.topK = Math.max(1, topK);
        this.maxChars = Math.max(64, maxChars);
        this.domainKeywords = parseCsv(domainKeywordsCsv);
        loadKnowledgeBase(knowledgeDir);
    }

    @PostConstruct
    void logState() {
        log.info("RAG loaded {} chunks from knowledge base, domainKeywords={}", chunks.size(), domainKeywords);
    }

    /**
     * 是否命中 RAG 关键词：查询中出现任一领域关键词即视为命中。
     * 这是三路路由中 “命中 RAG 关键词？” 的判定依据。
     */
    public boolean hasRelevantKnowledge(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        for (String kw : domainKeywords) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return !retrieve(query).isEmpty();
    }

    /** 关键词召回：按查询词命中数打分，返回 Top-K（累计不超过 maxChars 字符）。 */
    public List<RagChunk> retrieve(String query) {
        if (query == null || query.isBlank() || chunks.isEmpty()) {
            return List.of();
        }
        Set<String> queryTerms = tokenize(query);
        // 把出现在查询中的领域关键词也作为召回词，
        // 保证自然中文查询（无空格/标点）也能命中知识库
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        for (String kw : domainKeywords) {
            if (lowerQuery.contains(kw.toLowerCase(Locale.ROOT))) {
                queryTerms.add(kw.toLowerCase(Locale.ROOT));
            }
        }
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<RagChunk> scored = new ArrayList<>();
        for (RagChunk chunk : chunks) {
            String lowerContent = chunk.content().toLowerCase(Locale.ROOT);
            int overlap = 0;
            for (String term : queryTerms) {
                if (lowerContent.contains(term)) {
                    overlap++;
                }
            }
            if (overlap > 0) {
                scored.add(RagChunk.of(chunk.source(), chunk.content(), overlap));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));

        List<RagChunk> top = new ArrayList<>();
        int totalChars = 0;
        for (RagChunk chunk : scored) {
            if (top.size() >= topK) {
                break;
            }
            int len = chunk.content().length();
            if (totalChars + len > maxChars) {
                // 截断超长块，保证整体不超 maxChars
                int remain = maxChars - totalChars;
                if (remain <= 0) {
                    break;
                }
                top.add(RagChunk.of(chunk.source(), chunk.content().substring(0, remain) + "…", chunk.score()));
                totalChars = maxChars;
                break;
            }
            top.add(chunk);
            totalChars += len;
        }
        return top;
    }

    /** 把检索到的知识块拼进增强 Prompt，交给 LLM。 */
    public String buildEnhancedPrompt(String query, List<RagChunk> retrieved) {
        StringBuilder sb = new StringBuilder();
        sb.append("请根据以下知识库内容回答用户问题。如果知识库中没有相关信息，请明确告知用户。")
          .append("\n\n=== 知识库 ===\n");
        if (retrieved == null || retrieved.isEmpty()) {
            sb.append("（无相关内容）\n");
        } else {
            for (RagChunk chunk : retrieved) {
                sb.append("【来源：").append(chunk.source()).append("】\n")
                  .append(chunk.content().strip())
                  .append("\n\n");
            }
        }
        sb.append("=== 用户问题 ===\n").append(query == null ? "" : query.trim());
        return sb.toString();
    }

    public List<RagChunk> allChunks() {
        return List.copyOf(chunks);
    }

    public Set<String> domainKeywords() {
        return Set.copyOf(domainKeywords);
    }

    private void loadKnowledgeBase(String knowledgeDir) {
        if (knowledgeDir == null || knowledgeDir.isBlank()) {
            log.warn("RAG knowledge-dir is empty, RAG disabled");
            return;
        }
        Path dir = Paths.get(knowledgeDir);
        if (!Files.isDirectory(dir)) {
            log.warn("RAG knowledge base dir not found: {} (RAG will return empty until files added)", dir);
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path file : stream) {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                for (String block : content.split("\\n\\s*\\n")) {
                    String trimmed = block.strip();
                    if (!trimmed.isBlank()) {
                        chunks.add(RagChunk.of(file.getFileName().toString(), trimmed, 0));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Load RAG knowledge base failed: {}", e.getMessage());
        }
    }

    private Set<String> tokenize(String text) {
        Set<String> tokens = new HashSet<>();
        for (String token : TOKEN_SPLIT.split(text.toLowerCase(Locale.ROOT))) {
            if (token.length() >= 2) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private Set<String> parseCsv(String csv) {
        Set<String> result = new HashSet<>();
        if (csv == null || csv.isBlank()) {
            return result;
        }
        Arrays.stream(csv.split(","))
              .map(String::trim)
              .filter(s -> !s.isBlank())
              .forEach(result::add);
        return result;
    }
}
