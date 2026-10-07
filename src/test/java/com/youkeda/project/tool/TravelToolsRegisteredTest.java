package com.youkeda.project.tool;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 验证 ToolRegistry 中注册了哪些工具。
 *
 * <p>旅游 Agent 是否会调用高德 / 滴滴 API，首先取决于这些工具是否
 * 出现在 LLM 的 function-calling 列表（即 ToolRegistry.definitions()）中。
 * 本测试直接打印清单，并断言 4 个新增工具已注册。
 */
@SpringBootTest
class TravelToolsRegisteredTest {

    @Autowired
    private ToolRegistry toolRegistry;

    @Test
    void listAllTools() {
        List<String> names = toolRegistry.names();
        System.out.println("ToolRegistry 已注册工具（共 " + names.size() + " 个）：");
        for (int i = 0; i < names.size(); i++) {
            System.out.printf("  %2d. %s%n", i + 1, names.get(i));
        }
        // 旅游 Agent 必需的 4 个新增工具
        List<String> expected = List.of("geocode", "search_poi", "plan_route", "estimate_ride");
        for (String e : expected) {
            assertTrue(names.contains(e), "必须注册工具：" + e + "，实际：" + names);
        }
        // 原有的旅行工具也要存在
        for (String e : List.of("query_weather", "search_attractions", "search_foods", "build_itinerary", "web_search")) {
            assertTrue(names.contains(e), "必须注册原有工具：" + e);
        }
        // 额外：打印每个新增工具的参数定义（便于人工核对）
        System.out.println("\n===== 新增工具 function 参数结构 =====");
        toolRegistry.definitions().stream()
                .filter(d -> expected.contains(d.function().name()))
                .forEach(d -> System.out.println(d.function().name() + " " + d.function().parameters()));
    }
}
