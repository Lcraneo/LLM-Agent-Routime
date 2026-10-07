package com.youkeda.project.map;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 高德地图 Key 验证测试。
 *
 * <p>运行方法：
 * <pre>
 *   cd d:\javaCode\group-seven-master
 *   ./mvnw.cmd test -Dtest=AmapServiceTest -q
 * </pre>
 *
 * <p>如出现 infocode=10009 USERKEY_PLAT_NOMATCH：说明 Key 是 JSAPI/Android/iOS/小程序 类型，
 * 需要到控制台删除该 Key，重新添加 Key 并选择【Web服务】平台。
 */
@SpringBootTest
class AmapServiceTest {

    @Autowired
    private AmapService amapService;

    @Test
    void testKeyIsConfigured() {
        assertNotNull(amapService, "AmapService 未注入");
        if (!amapService.isConfigured()) {
            System.out.println("[INFO] AMAP_API_KEY 未配置，跳过剩余高德 API 实际调用测试。"
                    + "请在 .env.local 中设置 AMAP_API_KEY=你的Web服务Key");
            return;
        }
        System.out.println("[OK] AmapService 已配置 Key，尾号检查通过。");
    }

    @Test
    void testGeocode() {
        if (!amapService.isConfigured()) return;
        String loc = amapService.geocode("上海外滩观景平台", "上海");
        System.out.println("[地理编码] 上海外滩观景平台 → " + loc);
        if (loc == null) {
            String err = amapService.lastError();
            if (err != null) System.out.println("[错误详情]\n" + err);
            fail("地理编码返回 null，请检查 Key（如类型不对，详见上方错误详情）。");
        }
        assertNotNull(loc, "地理编码不应为 null");
        assertTrue(loc.contains(","), "坐标格式应为 经度,纬度，实际：" + loc);
    }

    @Test
    void testSearchPoiText() {
        if (!amapService.isConfigured()) return;
        String result = amapService.searchPoiText("火锅", "成都", 5);
        System.out.println("========== [POI 搜索：成都 火锅（Top5）] ==========");
        System.out.println(result);
        System.out.println("==================================================");

        // 如果用户的 Key 没配置对，返回的结果通常会包含「调用失败 / infocode / 失败」
        assertFalse(result.contains("调用失败") || result.contains("infocode") || result.startsWith("失败："),
                "POI 搜索失败：" + result);
        assertTrue(result.contains("个 POI") || result.contains("匹配的 POI"),
                "返回格式异常，疑似 Key 问题。原始返回：" + result);
    }

    @Test
    void testDirectionDriving() {
        if (!amapService.isConfigured()) return;
        String from = amapService.geocode("北京首都国际机场", "北京");
        String to   = amapService.geocode("天安门广场", "北京");
        if (from == null || to == null) {
            System.out.println("[WARN] 地理编码无法获取起/终点坐标，跳过驾车路线测试。"
                    + "上次错误：" + amapService.lastError());
            return;
        }
        String result = amapService.directionDriving(from, to);
        System.out.println("========== [驾车路线：首都机场 → 天安门] ==========");
        System.out.println(result);
        System.out.println("==================================================");
        assertTrue(result.contains("距离") && result.contains("耗时"),
                "驾车路线返回格式异常：" + result);
    }

    @Test
    void testDistance() {
        if (!amapService.isConfigured()) return;
        String from = amapService.geocode("虹桥站", "上海");
        String to = amapService.geocode("外滩", "上海");
        if (from == null || to == null) return;
        String d = amapService.measureDistance(from, to);
        System.out.println("[距离测量] 上海虹桥站 → 外滩：" + d);
        assertTrue(d.contains("距离") && d.contains("耗时"),
                "距离测量返回异常：" + d);
    }
}
