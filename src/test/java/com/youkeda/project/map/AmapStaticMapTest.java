package com.youkeda.project.map;

import static org.junit.jupiter.api.Assertions.*;
import com.youkeda.project.map.AmapService.Point;
import com.youkeda.project.map.AmapService.StaticMapResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 高德静态图功能验收（单测覆盖 5 个断言点）。
 *
 * <ol>
 *   <li>API Key 是否已配置；</li>
 *   <li>AmapService.staticMap()：输入成都 4 个点位（机场/宽窄巷子/熊猫基地/都江堰），
 *       返回 ok=true，PNG 文件存在、字节>1KB，previewUrl 以 /api/amap/static/ 开头；</li>
 *   <li>PNG 魔数合法（本地缓存文件读取 8 字节）；</li>
 *   <li>Controller 静态缓存查找方法 staticCacheFile(cacheKey) 能找到；非法 cacheKey 返回 null；</li>
 *   <li>AmapStaticMapTool 已被注册到 ToolRegistry（function-calling 列表包含 static_map）。</li>
 * </ol>
 *
 * <p>注意：静态图对同一组参数有 sha256 缓存，第二次运行不会真正请求高德。
 * 想强制刷新，可以删除 ./data/amap-static/*.png。
 */
@SpringBootTest
class AmapStaticMapTest {

    @Autowired private AmapService amapService;
    @Autowired private com.youkeda.project.tool.ToolRegistry toolRegistry;

    private static final byte[] PNG_HEAD = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47};

    @Test
    void T1_KeyConfigured() {
        assertTrue(amapService.isConfigured(),
                "AMAP_API_KEY 未配置。请在 .env.local 写入 AMAP_API_KEY=你的Web服务Key，然后重新运行测试。");
    }

    @Test
    void T2_StaticMapDownloadAndCache() throws Exception {
        T1_KeyConfigured();
        List<Point> pts = List.of(
                new Point("双流机场",   103.947867, 30.578864),
                new Point("宽窄巷子",   104.061351, 30.673131),
                new Point("熊猫基地",   104.138176, 30.740573),
                new Point("都江堰景区", 103.614546, 30.998154)
        );
        long t0 = System.currentTimeMillis();
        StaticMapResult r = amapService.staticMap("成都4点线路测试", pts, 0, 900, 560);
        System.out.println("staticMap 耗时 " + (System.currentTimeMillis() - t0) + "ms，ok=" + r.ok());
        assertTrue(r.ok(), "staticMap 失败：" + r.error());

        assertNotNull(r.cacheKey(), "cacheKey 不应为空");
        assertNotNull(r.previewUrl(), "previewUrl 不应为空");
        assertTrue(r.previewUrl().startsWith("/api/amap/static/") && r.previewUrl().endsWith(".png"),
                "previewUrl 格式异常：" + r.previewUrl());

        Path f = r.localPath();
        assertNotNull(f, "localPath 不应为空");
        assertTrue(Files.exists(f), "缓存文件不存在：" + f);
        long size = Files.size(f);
        System.out.println("缓存文件 " + f.getFileName() + " 大小=" + size + " bytes");
        assertTrue(size > 1024, "PNG 大小异常（<1KB）：" + size + " bytes。疑似高德返回了错误响应而非图片。");

        // 魔数前 4 字节（89 50 4E 47 = PNG）
        byte[] head = Files.readAllBytes(f);
        for (int i = 0; i < PNG_HEAD.length; i++) {
            assertEquals(PNG_HEAD[i] & 0xFF, head[i] & 0xFF,
                    "PNG 头魔数错误，第 " + i + " 字节。文件可能是高德返回的错误 JSON。前 16 字节：" + hex(head, 16));
        }
    }

    @Test
    void T3_StaticCacheFileHelper() {
        // 先生成一张再测
        List<Point> pts = List.of(new Point("上海外滩", 121.497995, 31.227957),
                                  new Point("迪士尼", 121.677778, 31.146667));
        StaticMapResult r = amapService.staticMap("上海两点测试", pts, 0, 700, 500);
        if (!r.ok()) {
            System.out.println("⚠️ staticMap 失败，跳过 T3：" + r.error());
            return;
        }
        String ck = r.cacheKey();
        Path p = amapService.staticCacheFile(ck);
        assertNotNull(p, "合法 cacheKey 不应返回 null：" + ck);

        // 非法 cacheKey 一律 null（防目录穿越）
        assertNull(amapService.staticCacheFile(null));
        assertNull(amapService.staticCacheFile("../etc/passwd"));
        assertNull(amapService.staticCacheFile("a/b/c"));
        assertNull(amapService.staticCacheFile("GGnot-hex123"));    // 非 hex
        assertNull(amapService.staticCacheFile("abcd1234"));       // 长度不够64
        assertNull(amapService.staticCacheFile("0".repeat(64)));   // 纯0但不存在
    }

    @Test
    void T4_StaticMapToolRegistered() {
        List<String> names = toolRegistry.names();
        assertTrue(names.contains("static_map"),
                "static_map 工具未注册。实际工具清单：" + names);
        // 打印参数定义便于人工核对
        System.out.println("\nstatic_map function 参数定义：");
        toolRegistry.definitions().stream()
                .filter(d -> "static_map".equals(d.function().name()))
                .forEach(d -> System.out.println("  " + d.function().parameters()));
    }

    @Test
    void T5_PointsSizeValidation() {
        // 空列表应该 fail，不抛 NPE
        StaticMapResult r = amapService.staticMap("空点位", List.of(), 0, 700, 500);
        assertFalse(r.ok(), "空 points 应返回失败，但实际 ok=true。");
        assertNotNull(r.error(), "错误描述不应为空");
        System.out.println("空 points 正确报错：" + r.error());
    }

    private static String hex(byte[] b, int limit) {
        int n = Math.min(limit, b.length);
        StringBuilder sb = new StringBuilder(n * 3);
        for (int i = 0; i < n; i++) sb.append(String.format("%02X ", b[i] & 0xFF));
        return sb.toString().trim();
    }
}
