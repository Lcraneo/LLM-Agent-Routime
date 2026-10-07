package com.youkeda.project.map;

import com.youkeda.project.map.AmapService.Point;
import com.youkeda.project.map.AmapService.StaticMapResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 高德地图辅助控制器。
 *
 * <ul>
 *   <li>GET  /api/amap/static/{cacheKey}.png  —— 从本地缓存返回 PNG（微信/前端直链展示）。
 *       缓存 Key 由 AmapService.staticMap() 生成，是参数串的 sha256（64 位 hex）。
 *   </li>
 *   <li>POST /api/amap/static/generate       —— 直接请求生成静态图（不通过 Agent），
 *       返回 {ok, cacheKey, previewUrl, error}，方便前端自主调用或调试。
 *   </li>
 * </ul>
 */
@RestController
@RequestMapping("/api/amap")
public class AmapController {

    private final AmapService amapService;

    public AmapController(AmapService amapService) { this.amapService = amapService; }

    /** 代理返回静态图 PNG。缓存不存在返回 404；浏览器可直接 <img src="/api/amap/static/xxx.png">。 */
    @GetMapping("/static/{cacheKey}.png")
    public ResponseEntity<byte[]> getStatic(@PathVariable String cacheKey) throws IOException {
        Path f = amapService.staticCacheFile(cacheKey);
        if (f == null) return ResponseEntity.notFound().build();
        byte[] bytes = Files.readAllBytes(f);
        HttpHeaders hdrs = new HttpHeaders();
        hdrs.setContentType(MediaType.IMAGE_PNG);
        hdrs.setContentLength(bytes.length);
        // 强缓存 30 天（sha256 名不可变，缓存极安全）
        hdrs.set(HttpHeaders.CACHE_CONTROL, "public, max-age=2592000, immutable");
        return new ResponseEntity<>(bytes, hdrs, HttpStatus.OK);
    }

    /** 前端/调试用：直接生成静态图。body = {title, points:[{name,lng,lat}], zoom, size_w, size_h}。 */
    @PostMapping("/static/generate")
    public ResponseEntity<?> generateStatic(@RequestBody Map<String, Object> body) {
        if (!amapService.isConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("ok", false,
                            "error", "AMAP_API_KEY 未配置。请填在 .env.local 的 AMAP_API_KEY="));
        }
        String title = str(body.get("title"), "静态地图");
        int zoom = toInt(body.get("zoom"), 0);
        int sizeW = toInt(body.get("size_w"), 800);
        int sizeH = toInt(body.get("size_h"), 500);
        List<Point> pts = parsePoints(body.get("points"));
        if (pts.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "points 为空或无法解析。需要 [{name,lng,lat}]。"));
        }
        StaticMapResult r = amapService.staticMap(title, pts, zoom, sizeW, sizeH);
        if (!r.ok()) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("ok", false, "error", r.error()));
        }
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "title", title,
                "cacheKey", r.cacheKey(),
                "previewUrl", r.previewUrl(),
                "points", pts.size(),
                "bytes", r.localPath() != null ? safeSize(r.localPath()) : 0
        ));
    }

    // ================= helpers =================

    private static String str(Object v, String def) {
        return v == null || String.valueOf(v).isBlank() ? def : String.valueOf(v);
    }
    private static int toInt(Object v, int def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); } catch (Exception e) { return def; }
    }
    private static double toDouble(Object v) {
        if (v == null) return 0;
        if (v instanceof Number n) return n.doubleValue();
        try { return Double.parseDouble(String.valueOf(v)); } catch (Exception e) { return 0; }
    }
    @SuppressWarnings("unchecked")
    private static List<Point> parsePoints(Object raw) {
        List<Point> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    out.add(new Point(str(m.get("name"), "P"),
                            toDouble(m.get("lng")), toDouble(m.get("lat"))));
                }
            }
        }
        return out;
    }
    private static long safeSize(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }
}
