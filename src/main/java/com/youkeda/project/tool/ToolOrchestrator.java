// 文件路径: com/youkeda/project/tool/ToolOrchestrator.java
package com.youkeda.project.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ToolOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(ToolOrchestrator.class);

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor;

    public ToolOrchestrator(
            ToolRegistry toolRegistry,
            @Value("${tool.executor.parallel-threads:8}") int parallelThreads) {
        this.toolRegistry = toolRegistry;
        this.executor = Executors.newFixedThreadPool(
                Math.max(2, parallelThreads),
                r -> {
                    Thread t = new Thread(r, "tool-parallel-executor");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * 并行执行多个工具（互不依赖），适用于同一轮 LLM 请求中的多个 tool_calls。
     */
    public List<ToolExecutionResult> executeParallel(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        log.info("Executing {} tools in parallel: {}", requests.size(),
                requests.stream().map(ToolExecutionRequest::name).collect(Collectors.toList()));

        List<CompletableFuture<ToolExecutionResult>> futures = requests.stream()
                .map(req -> CompletableFuture.supplyAsync(() -> executeSingle(req), executor))
                .toList();

        // 等待所有完成，若某个超时则强制返回异常结果
        return futures.stream()
                .map(future -> {
                    try {
                        return future.get(30, TimeUnit.SECONDS); // 可配置超时
                    } catch (Exception e) {
                        log.warn("Parallel tool execution timeout or failed: {}", e.getMessage());
                        // 构造一个失败结果，避免阻塞整个链路
                        return new ToolExecutionResult(
                                null, "unknown", "", null, e, -1);
                    }
                })
                .collect(Collectors.toList());
    }

    /**
     * 串行执行工具链：后一个工具可以获取前一个工具的输出（作为上下文）。
     * 注意：标准的 LLM Agent 循环通常是多轮串行，但此处提供显式的链式调用。
     */
    public List<ToolExecutionResult> executeSerial(List<ToolExecutionRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        log.info("Executing {} tools in serial chain.", requests.size());

        List<ToolExecutionResult> results = new ArrayList<>();
        String previousOutput = null;

        for (int i = 0; i < requests.size(); i++) {
            ToolExecutionRequest req = requests.get(i);
            String finalArgs = req.arguments();

            // 如果参数中包含占位符 {{previous_output}}，则替换为上一个工具的输出（链式传递）
            if (previousOutput != null && finalArgs != null && finalArgs.contains("{{previous_output}}")) {
                finalArgs = finalArgs.replace("{{previous_output}}", previousOutput);
                log.debug("Replaced placeholder in tool {} with previous output.", req.name());
            }

            ToolExecutionRequest finalReq = new ToolExecutionRequest(req.id(), req.name(), finalArgs);
            ToolExecutionResult result = executeSingle(finalReq);
            results.add(result);

            if (result.isSuccess() && result.content() != null) {
                previousOutput = result.content();
            } else {
                // 如果某个工具失败，串行链默认中断（可根据需求配置是否继续）
                log.warn("Serial chain broken at tool {} due to error: {}", req.name(),
                        result.error() != null ? result.error().getMessage() : "unknown");
                break;
            }
        }
        return results;
    }

    /**
     * 单个工具的实际执行逻辑（复用 ToolRegistry）。
     */
    private ToolExecutionResult executeSingle(ToolExecutionRequest request) {
        long start = System.currentTimeMillis();
        try {
            AgentToolResult result = toolRegistry.execute(request.name(), request.arguments());
            long duration = System.currentTimeMillis() - start;
            if (result == null) {
                return new ToolExecutionResult(request.id(), request.name(), "", null, null, duration);
            }
            return new ToolExecutionResult(
                    request.id(),
                    request.name(),
                    result.content(),
                    result.imageUrl(),
                    null,
                    duration);
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Tool execution failed: {} with args {}", request.name(), request.arguments(), e);
            return new ToolExecutionResult(
                    request.id(),
                    request.name(),
                    "执行失败: " + e.getMessage(),
                    null,
                    e,
                    duration);
        }
    }

    /**
     * 优雅关闭线程池。
     */
    public void shutdown() {
        executor.shutdownNow();
    }
}