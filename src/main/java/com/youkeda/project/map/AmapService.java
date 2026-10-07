package com.youkeda.project.map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 高德地图 Web 服务 API 封装。
 *
 * <p>覆盖能力：
 * <ul>
 *   <li>地理编码（地址 → 经纬度）</li>
 *   <li>逆地理编码（经纬度 → 地址）</li>
 *   <li>POI 关键字搜索 / 周边搜索</li>
 *   <li>驾车 / 步行 / 公交 / 骑行 路径规划</li>
 *   <li>两点距离测量</li>
 * </ul>
 *
 * <p>所有方法均带有简单内存缓存（5 分钟 TTL），减少重复 API 调用。
 * API Key 通过 {@code amap.api-key} 配置项注入。
 */
@Service
public class AmapService {

    private static final Logger log = LoggerFactory.getLogger(AmapService.class);

    private static final String GEOCODE_URL = "https://restapi.amap.com/v3/geocode/geo";
    private static final String REGEOCODE_URL = "https://restapi.amap.com/v3/geocode/regeo";
    private static final String POI_TEXT_URL = "https://restapi.amap.com/v5/place/text";
    private static final String POI_AROUND_URL = "https://restapi.amap.com/v5/place/around";
    private static final String DIRECTION_DRIVING_URL = "https://restapi.amap.com/v3/direction/driving";
    private static final String DIRECTION_WALKING_URL = "https://restapi.amap.com/v3/direction/walking";
    private static final String DIRECTION_TRANSIT_URL = "https://restapi.amap.com/v3/direction/transit/integrated";
    private static final String DIRECTION_BICYCLING_URL = "https://restapi.amap.com/v4/direction/bicycling";
    private static final String DISTANCE_URL = "https://restapi.amap.com/v3/distance";

    private static final long CACHE_TTL_MS = 5 * 60 * 1000;
    private record CacheEntry(String content, long timestamp) {}
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    /** 高德错误码 → 人类可读排障说明。infocode 见官方文档 https://lbs.amap.com/api/webservice/guide/tools/info */
    private static final Map<String, String> AMAP_ERROR_HELP = Map.ofEntries(
            Map.entry("10001", "key 无效 / 格式不正确。请核对复制的 Key 是否有多余空格，或重新创建。"),
            Map.entry("10002", "用户不存在 / 账号异常。请到 https://console.amap.com/dev/key/app 确认账号状态。"),
            Map.entry("10003", "域名 / IP / 包名 绑定校验失败。若在控制台开启了「安全密钥」或「IP白名单」，请确认本机 IP / 请求来源是否在白名单内；临时测试可先取消白名单设置。"),
            Map.entry("10004", "账户已停用。请登录高德开发者控制台查看账号状态。"),
            Map.entry("10005", "账户余额或免费配额已用完。基础LBS个人免费 15万次/月、搜索 5000次/月，可在控制台购买流量包。"),
            Map.entry("10006", "每天请求量超限。明天 00:00 自动恢复，或购买日增包。"),
            Map.entry("10007", "每分钟请求量超限（QPS 超过当前 Key 额度）。稍后重试或购买 QPS 包。"),
            Map.entry("10008", "每秒请求量超限。降低并发频率。"),
            Map.entry("10009", "【最常见】USERKEY_PLAT_NOMATCH：Key 类型不匹配！\n"
                    + "  你创建的是 JS API / Android / iOS / 小程序 Key，但本服务调用的是 restapi.amap.com (Web 服务 API)。\n"
                    + "  ✅ 修复步骤：\n"
                    + "  1. 打开 https://console.amap.com/dev/key/app\n"
                    + "  2. 进入「应用管理 → 我的应用」，新建或打开一个应用\n"
                    + "  3. 点击「添加 Key」，服务平台必须选择：【Web服务】（不要选Web端JSAPI/Android/iOS/小程序）\n"
                    + "  4. 把新 Key 填到项目 .env.local 的 AMAP_API_KEY= 或 application.properties 的 amap.api-key=\n"
                    + "  5. 若是刚创建的 Key，需等待 1-2 分钟生效后再调用。"),
            Map.entry("10010", "请求服务接口无权限。请在控制台确认该 Key 是否开通了对应服务（地理编码/搜索/路径规划等）。"),
            Map.entry("10011", "接口被禁用。控制台中该 Key 下对应服务被关闭了，开启即可。"),
            Map.entry("10015", "数字签名 sig 不匹配。如果开启了安全密钥的数字签名校验，请核对签名算法，临时测试可以先关闭。"),
            Map.entry("10016", "Key 开通了服务但未开通该权限等级（如 POI多语言需要商务申请）。改用默认参数即可。"),
            Map.entry("10017", "请求内容存在违规。检查请求参数是否含敏感词。"),
            Map.entry("10019", "IP 黑白名单校验失败。与 10003 类似，检查控制台的 IP 白名单配置。"),
            Map.entry("10020", "签名参数错误（与 10015 类似）。关闭安全密钥或重新计算 sig。"),
            Map.entry("10021", "缺少必填参数 key。检查 Key 配置是否为空字符串。"),
            Map.entry("10023", "数字签名校验失败。请参考 https://lbs.amap.com/faq/quota-key/key/41181/ 检查密钥拼接顺序。"),
            Map.entry("10044", "日请求量超限（和 10006 同义）。"),
            Map.entry("10045", "账号类型不支持该接口。例如个人认证用户使用企业专属接口。"),
            Map.entry("10050", "访问来源非法。检查 referer / 白名单 / 签名。"),
            Map.entry("10066", "安全密钥（JSAPI 安全密钥模式）不适配 Web 服务。请用纯 Web 服务 Key（不开启 JSAPI 安全密钥）。"),
            Map.entry("10067", "安全密钥已被禁用。可在控制台重新启用。"),
            Map.entry("10070", "Key 的服务平台选择错误（和 10009 同义，但更明确）。务必选择【Web服务】平台类型。")
    );

    private String apiKey;                  // 构造后通过 resolveApiKey() 重新解析
    private final String configuredKey;     // application.properties 中配置的原始值
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** 最近一次 Key/鉴权类错误（给 Tool 层输出，方便用户看到排障指引）。 */
    private final AtomicReference<String> lastError = new AtomicReference<>();

    public AmapService(
            @Value("${amap.api-key:}") String apiKey,
            @Value("${amap.timeout-seconds:15}") int timeoutSeconds) {
        this.configuredKey = apiKey == null ? "" : apiKey.trim();
        this.apiKey = this.configuredKey;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(3, timeoutSeconds)))
                .build();
    }

    @PostConstruct
    void init() {
        this.apiKey = resolveApiKey(configuredKey);
        if (apiKey.isBlank()) {
            log.warn("AMAP_API_KEY is not configured; map/direction/POI features are disabled. "
                    + "Set it in .env.local (AMAP_API_KEY=xxx) or application.properties (amap.api-key=xxx).");
        } else {
            log.info("AmapService initialized with API Key ending in ...{}",
                    apiKey.length() > 4 ? apiKey.substring(apiKey.length() - 4) : "****");
        }
    }

    /** 与 QwenChatService 相同的解析优先级：环境变量 > .env.local > .env > application.properties。 */
    private static String resolveApiKey(String propsKey) {
        // 1) 环境变量
        String env = System.getenv("AMAP_API_KEY");
        if (env != null && !env.isBlank()) return env.trim();
        // 2) .env.local / .env
        for (String file : List.of(".env.local", ".env")) {
            Path p = Path.of(file);
            if (!Files.exists(p)) continue;
            try {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (t.startsWith("AMAP_API_KEY=")) {
                        String v = t.substring("AMAP_API_KEY=".length()).trim();
                        // 去掉首尾可能的引号
                        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                        if (v.startsWith("'") && v.endsWith("'") && v.length() >= 2) v = v.substring(1, v.length() - 1);
                        if (!v.isBlank()) return v;
                    }
                }
            } catch (IOException e) {
                log.warn("读取 {} 失败: {}", file, e.getMessage());
            }
        }
        // 3) application.properties
        return propsKey == null ? "" : propsKey;
    }

    /** Key 是否已配置（未配置时所有方法返回友好提示而非抛异常）。 */
    public boolean isConfigured() {
        return !apiKey.isBlank();
    }

    /** 返回最近一次鉴权/配额类错误（没有返回 null）。调用 geocode 失败时可显示给用户。 */
    public String lastError() { return lastError.get(); }

    // ========== 地理编码 ==========

    /**
     * 地址 → 经纬度。
     * @return "经度,纬度" 格式字符串；失败时返回 null；若出现 Key/配额类错误，会把错误详情写入 lastError。
     */
    public String geocode(String address, String city) {
        if (!isConfigured()) return null;
        if (address == null || address.isBlank()) return null;
        String cacheKey = "geo:" + address + "|" + (city == null ? "" : city);
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            return cached.content();
        }
        StringBuilder sb = new StringBuilder(GEOCODE_URL).append("?key=").append(apiKey)
                .append("&address=").append(urlEncode(address));
        if (city != null && !city.isBlank()) {
            sb.append("&city=").append(urlEncode(city));
        }
        JsonResult r = getJson(sb.toString(), "地理编码(v3/geocode/geo)");
        if (r.hasError()) {
            lastError.set(r.error());
            return null;
        }
        JsonNode codes = r.data().path("geocodes");
        if (codes.isArray() && codes.size() > 0) {
            String loc = codes.get(0).path("location").asText(null);
            if (loc != null) {
                CACHE.put(cacheKey, new CacheEntry(loc, System.currentTimeMillis()));
                return loc;
            }
        }
        return null;
    }

    /** 地址 → 经纬度（不限制城市）。 */
    public String geocode(String address) {
        return geocode(address, null);
    }

    // ========== 逆地理编码 ==========

    /**
     * 经纬度 → 结构化地址描述。
     * @param location "经度,纬度"
     */
    public String reverseGeocode(String location) {
        if (!isConfigured()) return null;
        if (location == null || location.isBlank()) return null;
        String cacheKey = "regeo:" + location;
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            return cached.content();
        }
        try {
            String url = REGEOCODE_URL + "?key=" + apiKey + "&location=" + urlEncode(location);
            JsonNode root = getJson(url);
            if (root != null && "1".equals(root.path("status").asText())) {
                String addr = root.path("regeocode").path("formatted_address").asText(null);
                if (addr != null) {
                    CACHE.put(cacheKey, new CacheEntry(addr, System.currentTimeMillis()));
                    return addr;
                }
            }
        } catch (Exception e) {
            log.warn("逆地理编码失败 location={}: {}", location, e.getMessage());
        }
        return null;
    }

    // ========== POI 搜索 ==========

    /**
     * 关键字搜索 POI，返回格式化文本（名称+地址+经纬度）。
     *
     * @param keywords 关键字
     * @param city 可选，限定搜索城市名
     * @param pageSize 返回数量 1-25
     */
    public String searchPoiText(String keywords, String city, int pageSize) {
        if (!isConfigured()) return "【提示】未配置高德地图 API Key，无法搜索 POI。请在项目根目录创建 .env.local 并填 AMAP_API_KEY=你的Web服务Key（控制台选「Web服务」平台）。";
        if (keywords == null || keywords.isBlank()) return "缺少搜索关键字。";
        int ps = Math.min(25, Math.max(1, pageSize));
        String cacheKey = "poit:" + keywords + "|" + (city == null ? "" : city) + "|" + ps;
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            return cached.content();
        }
        StringBuilder sb = new StringBuilder(POI_TEXT_URL).append("?key=").append(apiKey)
                .append("&keywords=").append(urlEncode(keywords))
                .append("&page_size=").append(ps)
                .append("&show_fields=business");
        if (city != null && !city.isBlank()) {
            sb.append("&region=").append(urlEncode(city)).append("&city_limit=true");
        }
        JsonResult r = getJson(sb.toString(), "POI关键字搜索(v5/place/text)");
        if (r.hasError()) return r.error();
        String result = formatPois(r.data());
        CACHE.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    /**
     * 周边搜索 POI。
     * @param location 圆心 "经度,纬度"
     * @param radius 半径米，0-50000
     */
    public String searchPoiAround(String location, int radius, String keywords) {
        if (!isConfigured()) return "【提示】未配置高德地图 API Key。";
        if (location == null || location.isBlank()) return "缺少中心点坐标 location。";
        int r = Math.min(50000, Math.max(100, radius));
        String cacheKey = "poia:" + location + "|" + r + "|" + (keywords == null ? "" : keywords);
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            return cached.content();
        }
        StringBuilder sb = new StringBuilder(POI_AROUND_URL).append("?key=").append(apiKey)
                .append("&location=").append(urlEncode(location))
                .append("&radius=").append(r)
                .append("&page_size=10&show_fields=business");
        if (keywords != null && !keywords.isBlank()) {
            sb.append("&keywords=").append(urlEncode(keywords));
        }
        JsonResult jr = getJson(sb.toString(), "POI周边搜索(v5/place/around)");
        if (jr.hasError()) return jr.error();
        String result = formatPois(jr.data());
        CACHE.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
        return result;
    }

    private String formatPois(JsonNode root) {
        if (root == null || !"1".equals(root.path("status").asText())) {
            String info = root == null ? "空响应" : root.path("info").asText("未知错误");
            return "未搜索到结果（" + info + "）。";
        }
        JsonNode pois = root.path("pois");
        if (!pois.isArray() || pois.size() == 0) {
            return "未搜索到匹配的 POI。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共找到 ").append(root.path("count").asText("0")).append(" 个 POI，取前 ")
                .append(pois.size()).append(" 条：\n");
        for (int i = 0; i < pois.size(); i++) {
            JsonNode p = pois.get(i);
            sb.append(i + 1).append(". 【").append(p.path("name").asText("未知地点")).append("】");
            String type = p.path("type").asText(null);
            if (type != null && !type.isBlank()) sb.append(" - ").append(type);
            sb.append("\n");
            String addr = p.path("address").asText(null);
            if (addr != null && !addr.isBlank()) sb.append("   地址：").append(addr).append("\n");
            String area = p.path("pname").asText("") + p.path("cityname").asText("") + p.path("adname").asText("");
            if (!area.isBlank()) sb.append("   区域：").append(area).append("\n");
            sb.append("   坐标：").append(p.path("location").asText("N/A")).append("\n");
            JsonNode biz = p.path("business");
            if (biz.isObject() && biz.size() > 0) {
                String rating = biz.path("rating").asText(null);
                String cost = biz.path("cost").asText(null);
                String tel = biz.path("tel").asText(null);
                String open = biz.path("opentime_today").asText(null);
                List<String> extras = new ArrayList<>();
                if (rating != null && !rating.isBlank()) extras.add("评分 " + rating);
                if (cost != null && !cost.isBlank()) extras.add("人均 " + cost);
                if (tel != null && !tel.isBlank()) extras.add("电话 " + tel);
                if (open != null && !open.isBlank()) extras.add("营业 " + open);
                if (!extras.isEmpty()) sb.append("   ").append(String.join(" | ", extras)).append("\n");
            }
        }
        return sb.toString().trim();
    }

    // ========== 路径规划 ==========

    /**
     * 驾车路径规划。
     * @param origin 起点 "经度,纬度"
     * @param destination 终点 "经度,纬度"
     * @return 格式化文本：总距离、总耗时、分段路线
     */
    public String directionDriving(String origin, String destination) {
        return directionCommon(DIRECTION_DRIVING_URL, "驾车", origin, destination, null, null);
    }

    /** 步行路径规划。 */
    public String directionWalking(String origin, String destination) {
        return directionCommon(DIRECTION_WALKING_URL, "步行", origin, destination, null, null);
    }

    /** 骑行路径规划。 */
    public String directionBicycling(String origin, String destination) {
        return directionCommon(DIRECTION_BICYCLING_URL, "骑行", origin, destination, null, null);
    }

    /**
     * 公交换乘规划。
     * @param city 所在城市名或 citycode
     */
    public String directionTransit(String origin, String destination, String city) {
        return directionCommon(DIRECTION_TRANSIT_URL, "公交", origin, destination, city, null);
    }

    private String directionCommon(String baseUrl, String modeName,
                                   String origin, String destination,
                                   String city, String extraParams) {
        if (!isConfigured()) return "【提示】未配置高德地图 API Key，无法进行路径规划。";
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return "缺少起点 origin 或终点 destination 参数。";
        }
        String cacheKey = "dir:" + modeName + "|" + origin + "|" + destination + "|" + (city == null ? "" : city);
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            return cached.content();
        }
        try {
            StringBuilder sb = new StringBuilder(baseUrl).append("?key=").append(apiKey)
                    .append("&origin=").append(urlEncode(origin))
                    .append("&destination=").append(urlEncode(destination));
            if (city != null && !city.isBlank()) sb.append("&city=").append(urlEncode(city));
            if (extraParams != null) sb.append(extraParams);
            JsonNode root = getJson(sb.toString());
            String result = formatDirection(root, modeName);
            CACHE.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
            return result;
        } catch (Exception e) {
            log.warn("{}路径规划失败: {}", modeName, e.getMessage());
            return modeName + "路径规划失败：" + e.getMessage();
        }
    }

    private String formatDirection(JsonNode root, String modeName) {
        if (root == null || !"1".equals(root.path("status").asText())) {
            String info = root == null ? "空响应" : root.path("info").asText("未知错误");
            return "路径规划失败（" + info + "）。";
        }
        JsonNode route = root.path("route");
        // 公交是 route -> transits，驾车/步行是 route -> paths
        StringBuilder sb = new StringBuilder();
        sb.append("【").append(modeName).append("路线规划】\n");

        JsonNode paths = route.path("paths");
        JsonNode transits = route.path("transits");

        if (paths.isArray() && paths.size() > 0) {
            // 驾车 / 步行 / 骑行
            JsonNode first = paths.get(0);
            int dist = first.path("distance").asInt(0);
            int dur = first.path("duration").asInt(0);
            String tactic = first.path("strategy").asText("推荐路线");
            sb.append("总距离：").append(formatDistance(dist)).append("\n");
            sb.append("预计耗时：").append(formatDuration(dur)).append("\n");
            if (!tactic.isBlank()) sb.append("策略：").append(tactic).append("\n");
            sb.append("taxi_cost：").append(first.path("taxi_cost").asText("N/A")).append("元\n");
            sb.append("\n分段路线：\n");
            JsonNode steps = first.path("steps");
            if (steps.isArray()) {
                for (int i = 0; i < Math.min(steps.size(), 15); i++) {
                    JsonNode s = steps.get(i);
                    sb.append(i + 1).append(". ")
                            .append(s.path("instruction").asText(s.path("polyline").asText("行驶")))
                            .append(" (").append(formatDistance(s.path("distance").asInt(0)))
                            .append(" / ").append(formatDuration(s.path("duration").asInt(0))).append(")\n");
                }
                if (steps.size() > 15) sb.append("... 共 ").append(steps.size()).append(" 段，此处省略后续\n");
            }
        } else if (transits.isArray() && transits.size() > 0) {
            // 公交
            for (int i = 0; i < Math.min(transits.size(), 3); i++) {
                JsonNode t = transits.get(i);
                sb.append("方案 ").append(i + 1).append("：\n");
                sb.append("  总距离：").append(formatDistance(t.path("distance").asInt(0))).append("\n");
                sb.append("  预计耗时：").append(formatDuration(t.path("duration").asInt(0))).append("\n");
                sb.append("  换乘次数：").append(t.path("transitsNum").asInt(0)).append(" 次\n");
                sb.append("  票价：").append(t.path("cost").asText("N/A")).append(" 元\n");
                JsonNode segs = t.path("segments");
                if (segs.isArray()) {
                    for (int j = 0; j < segs.size(); j++) {
                        JsonNode seg = segs.get(j);
                        JsonNode bus = seg.path("bus");
                        JsonNode walk = seg.path("walking");
                        if (walk != null && !walk.isNull() && walk.path("distance").asInt(0) > 0) {
                            sb.append("  步行 ").append(formatDistance(walk.path("distance").asInt(0))).append("\n");
                        }
                        if (bus != null && !bus.isNull()) {
                            JsonNode buslines = bus.path("buslines");
                            if (buslines.isArray() && buslines.size() > 0) {
                                JsonNode bl = buslines.get(0);
                                sb.append("  乘 ").append(bl.path("name").asText("公交"))
                                        .append(" (").append(bl.path("departure_stop").path("name").asText("?"))
                                        .append(" → ").append(bl.path("arrival_stop").path("name").asText("?"))
                                        .append("，").append(bl.path("via_num").asInt(0)).append("站)\n");
                            }
                        }
                    }
                }
                sb.append("\n");
            }
        } else {
            sb.append("未找到可用路线。");
        }
        return sb.toString().trim();
    }

    // ========== 距离测量 ==========

    /**
     * 计算两点间驾车距离和耗时。
     * @return "距离X公里，耗时Y分钟"
     */
    public String measureDistance(String origin, String destination) {
        if (!isConfigured()) return "【提示】未配置高德地图 API Key，无法测量距离。";
        if (origin == null || origin.isBlank() || destination == null || destination.isBlank()) {
            return "缺少起点或终点坐标。";
        }
        String cacheKey = "dist:" + origin + "|" + destination;
        CacheEntry cached = CACHE.get(cacheKey);
        if (cached != null && System.currentTimeMillis() - cached.timestamp() < CACHE_TTL_MS) {
            return cached.content();
        }
        try {
            String url = DISTANCE_URL + "?key=" + apiKey
                    + "&origins=" + urlEncode(origin)
                    + "&destination=" + urlEncode(destination)
                    + "&type=1";
            JsonNode root = getJson(url);
            if (root != null && "1".equals(root.path("status").asText())) {
                JsonNode results = root.path("results");
                if (results.isArray() && results.size() > 0) {
                    JsonNode r = results.get(0);
                    int dist = r.path("distance").asInt(0);
                    int dur = r.path("duration").asInt(0);
                    String result = "驾车距离 " + formatDistance(dist) + "，预计耗时 " + formatDuration(dur);
                    CACHE.put(cacheKey, new CacheEntry(result, System.currentTimeMillis()));
                    return result;
                }
            }
            return "距离测量失败。";
        } catch (Exception e) {
            log.warn("距离测量失败: {}", e.getMessage());
            return "距离测量失败：" + e.getMessage();
        }
    }

    // ========== 结果对象：带错误诊断的 HTTP 响应 ==========

    /** 内部使用的统一结果容器：成功时 data != null；失败时 error 含 infocode+帮助信息。 */
    record JsonResult(JsonNode data, String error) {
        static JsonResult ok(JsonNode d) { return new JsonResult(d, null); }
        static JsonResult fail(String e) { return new JsonResult(null, e); }
        boolean hasError() { return error != null && !error.isBlank(); }
    }

    /** 检查 JSON 返回体的 status / infocode，翻译错误码为人类可读提示。 */
    private JsonResult checkResponse(JsonNode root, String apiName) {
        if (root == null) return JsonResult.fail(apiName + "：HTTP 响应为空，请检查网络连接和 restapi.amap.com 连通性。");
        String status = root.path("status").asText("");
        String info   = root.path("info").asText("");
        String infocode = root.path("infocode").asText("");
        if ("1".equals(status)) return JsonResult.ok(root);

        String help = AMAP_ERROR_HELP.getOrDefault(infocode, "");
        String msg = String.format("高德「%s」调用失败：status=%s info=%s infocode=%s",
                apiName, status, info, infocode);
        String full = help.isBlank() ? msg : (msg + "\n\n【排障指引】\n" + help);
        log.warn(full);
        return JsonResult.fail(full);
    }

    // ========== 工具方法 ==========

    private static String urlEncode(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    /**
     * 执行 HTTP GET 并解析 JSON。
     * @return JsonResult（成功时 data 非空；失败时 error 有人类可读说明）
     */
    private JsonResult getJson(String url, String apiName) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                String hint = String.format("高德「%s」HTTP %d：请检查 Key 是否过期、是否开启了Referer/IP限制（10003/10019）。",
                        apiName, resp.statusCode());
                log.warn(hint + "  url={}", maskKey(url));
                return JsonResult.fail(hint);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            return checkResponse(root, apiName);
        } catch (java.net.ConnectException | java.net.UnknownHostException ce) {
            String hint = String.format("高德「%s」网络连接失败：%s。请确认本机可以访问 https://restapi.amap.com （检查代理/防火墙）。",
                    apiName, ce.getMessage());
            log.warn(hint);
            return JsonResult.fail(hint);
        } catch (Exception e) {
            String hint = String.format("高德「%s」请求异常：%s", apiName, e.getMessage());
            log.warn(hint);
            return JsonResult.fail(hint);
        }
    }

    /** 日志中隐藏 Key 的中间若干字符，避免泄露。 */
    private static String maskKey(String url) {
        int i = url.indexOf("key=");
        if (i < 0) return url;
        int start = i + 4;
        int end = url.indexOf('&', start);
        if (end < 0) end = url.length();
        String key = url.substring(start, end);
        if (key.length() <= 8) return url.substring(0, start) + "***";
        return url.substring(0, start)
                + key.substring(0, 4) + "****" + key.substring(key.length() - 4)
                + url.substring(end);
    }

    /** 快速辅助：成功时返回 JsonNode，失败返回 null（但仍打日志）。保留给简单调用方使用。 */
    private JsonNode getJson(String url) {
        String api = url.contains("?") ? url.substring(url.lastIndexOf('/', url.indexOf('?')) + 1, url.indexOf('?')) : "unknown";
        JsonResult r = getJson(url, api);
        return r.data();
    }

    /** 失败时把错误描述拼进格式化文本的快捷入口。成功返回 null，失败返回「失败：xxx」描述。 */
    private String errorOrNull(JsonResult r) { return r.hasError() ? "失败：" + r.error() : null; }

    static String formatDistance(int meters) {
        if (meters <= 0) return "0 米";
        if (meters < 1000) return meters + " 米";
        return String.format("%.1f 公里", meters / 1000.0);
    }

    static String formatDuration(int seconds) {
        if (seconds <= 0) return "0 分钟";
        if (seconds < 60) return seconds + " 秒";
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        if (h == 0) return m + " 分钟";
        if (m == 0) return h + " 小时";
        return h + " 小时 " + m + " 分钟";
    }

    // ======================================================================
    //  静态地图（amap v3/staticmap）+ 本地 PNG 缓存 + Controller 代理读
    // ======================================================================

    private static final String STATICMAP_URL = "https://restapi.amap.com/v3/staticmap";
    /** 静态图缓存目录（与 travel-cache 同级，便于调试和清理）。 */
    public static final Path STATIC_CACHE_DIR;
    static {
        Path base;
        try {
            base = Path.of("./data/amap-static");
            Files.createDirectories(base);
        } catch (IOException e) {
            base = Path.of("./amap-static");
            try { Files.createDirectories(base); } catch (IOException ignored) {}
        }
        STATIC_CACHE_DIR = base;
    }
    /** PNG 头（89 50 4E 47 0D 0A 1A 0A）8 字节。 */
    private static final byte[] PNG_MAGIC = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    /**
     * 下载高德静态图并存入本地缓存。
     *
     * <p>高德静态图接口参数文档：https://lbs.amap.com/api/webservice/guide/api/staticmaps
     * <ul>
     *   <li>key：Web 服务 API Key（与本服务其他接口相同）</li>
     *   <li>location：地图中心点 "lng,lat"</li>
     *   <li>zoom：3~18，默认 12</li>
     *   <li>size：{width}*{height}，默认 700*500，最大 1024*1024</li>
     *   <li>markers：彩色标注，格式 "large,0xFF0000,A:lng,lat|..."</li>
     *   <li>paths：折线，格式 "2,0x00B42AFF,1.0,:lng,lat;..."</li>
     * </ul>
     *
     * <p>调用方只需要传「坐标列表」即可；此方法会自动：做序号/颜色标注、画连线、下载 PNG、
     * 校验 PNG 魔数、写入本地文件（sha256 参数串为文件名）。
     *
     * @param title     标题（用于展示；不参与签名），如 "成都 Day1：机场→宽窄巷子→熊猫基地"
     * @param points    坐标列表，格式 [{name, lng, lat}]，至少 1 个；≥2 个时自动画折线。
     * @param zoom      地图缩放等级（3-18），0 表示自动（交给高德）
     * @param sizeW     图片宽度，最大 1024
     * @param sizeH     图片高度，最大 1024
     * @return 结果：{ok, cacheKey, localPath, error, previewUrl}
     *         cacheKey 是 PNG 文件名（不含 .png），Controller 通过 GET /api/amap/static/{cacheKey}.png 代理访问。
     */
    public StaticMapResult staticMap(String title,
                                     List<Point> points,
                                     int zoom,
                                     int sizeW,
                                     int sizeH) {
        if (!isConfigured()) {
            return StaticMapResult.fail("未配置高德地图 API Key（AMAP_API_KEY），请在 .env.local 中配置后重试。");
        }
        if (points == null || points.isEmpty()) {
            return StaticMapResult.fail("staticMap 缺少坐标点。请传入至少 1 个 {name, lng, lat}。");
        }
        int w = clamp(sizeW, 200, 1024);
        int h = clamp(sizeH, 200, 1024);
        int z = zoom > 0 ? clamp(zoom, 1, 17) : 0;  // 高德文档：zoom 取值 [1,17]

        // ========== 1) 构造 markers（按 style 分组） ==========
        // 文档格式：markers=size,color,label:loc1;loc2;...|size,color,label:loc3;...
        // 限制：
        //   - label 只能是【单字符】：[0-9]、[A-Z] 或【单个中文字】；size=small 时 label 不显示
        //   - markers 最多 10 个
        // 这里将 points 分类为 3 组：START（1个）、END（1个，若有多点）、MID（其它，按 1..n 编号）
        List<Point> pts = points.stream().limit(10).toList();
        java.util.LinkedHashMap<String, java.util.List<String>> styleGroup = new java.util.LinkedHashMap<>();
        for (int i = 0; i < pts.size(); i++) {
            Point p = pts.get(i);
            boolean isStart = (i == 0 && pts.size() > 1);
            boolean isEnd   = (i == pts.size() - 1 && pts.size() > 1);
            String label;
            String style;
            if (pts.size() == 1) {
                label = "P";
                style = "large,0xFF7D00,P";      // 单个：橙色 + P 标记
            } else if (isStart) {
                label = "S";
                style = "large,0x00B42A,S";      // 起点：绿色 + S
            } else if (isEnd) {
                label = "E";
                style = "large,0xF53F3C,E";      // 终点：红色 + E
            } else {
                // 中间点：label=数字(1..9)，注意：label 必须是单字符，所以超过 9 的写成字母
                int idx = i; // 中间点的序数（从 1 开始）
                label = idx < 10 ? String.valueOf(idx) : "M";
                style = "large,0x1677FF," + label; // 中间：蓝色 + 数字
            }
            String coord = String.format(java.util.Locale.US, "%.6f,%.6f", p.lng(), p.lat());
            styleGroup.computeIfAbsent(style, k -> new java.util.ArrayList<>()).add(coord);
        }
        StringBuilder markers = new StringBuilder();
        for (var e : styleGroup.entrySet()) {
            if (!markers.isEmpty()) markers.append("|");
            markers.append(e.getKey()).append(":").append(String.join(";", e.getValue()));
        }

        // ========== 2) 构造 paths（折线）—— 5 段参数必填，缺一不可 ==========
        // 文档格式：paths=weight,color,transparency,fillcolor,fillTransparency:loc1;loc2;...
        //   weight：[2,15] 线条粗细
        //   color：0xRRGGBB（6 位 hex，不能带透明度 A，否则 UNKNOWN_ERROR 20003）
        //   transparency：0~1
        //   fillcolor：空 = 不构成多边形
        //   fillTransparency：0~1，fillcolor 为空时也必须写（写空字符串）
        // 官方示例：paths=10,0x0000ff,1,,:points  （后两空用 ,, 占位）
        String pathsParam = "";
        if (pts.size() >= 2) {
            StringBuilder coords = new StringBuilder();
            for (int i = 0; i < pts.size(); i++) {
                if (coords.length() > 0) coords.append(";");
                coords.append(String.format(java.util.Locale.US, "%.6f,%.6f", pts.get(i).lng(), pts.get(i).lat()));
            }
            pathsParam = "&paths=2,0x1677FF,1,,:" + coords;
        }

        // 3) location（可选，有覆盖物时可省略）
        String location = "";
        if (z > 0 && pts.size() == 1) {
            Point p = pts.get(0);
            location = "&location=" + String.format(java.util.Locale.US, "%.6f,%.6f", p.lng(), p.lat());
        }

        // 4) 拼完整 URL
        String size = w + "*" + h;
        String zoomParam = z > 0 ? "&zoom=" + z : "";
        String rawUrl = STATICMAP_URL
                + "?key=" + urlEncode(apiKey)
                + "&size=" + size
                + zoomParam
                + location
                + "&markers=" + urlEncode(markers.toString())
                + pathsParam;

        // 4) 以 URL 参数（除 key 外）做稳定 sha256：相同坐标/zoom/size 命中缓存
        String cacheKey = sha256(rawUrl.substring(rawUrl.indexOf('?') + 1));
        Path cacheFile = STATIC_CACHE_DIR.resolve(cacheKey + ".png");

        try {
            if (Files.exists(cacheFile) && Files.size(cacheFile) > 200) {
                return StaticMapResult.ok(title, cacheKey, cacheFile);
            }

            // 5) 下载 PNG 字节流
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(rawUrl))
                    .timeout(Duration.ofSeconds(20))
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                String hint = "高德静态图 HTTP " + resp.statusCode()
                        + "：请检查 Key 是否为 Web 服务类型（10009）、是否开启了白名单（10003/10019）。";
                log.warn(hint + "  url={}", maskKey(rawUrl));
                return StaticMapResult.fail(hint);
            }
            byte[] body = resp.body();
            if (body == null || body.length < 32) {
                return StaticMapResult.fail("高德静态图返回空内容（<32字节）。请检查 Key。");
            }

            // 6) PNG 魔数校验（经验 1192414 提醒：要 & 0xFF 比较，避免 Java signed byte 负值）
            for (int i = 0; i < PNG_MAGIC.length; i++) {
                if ((body[i] & 0xFF) != (PNG_MAGIC[i] & 0xFF)) {
                    // 非 PNG，很可能是 JSON 错误页。尝试解析 infocode。
                    String bodyStr = new String(body, 0, Math.min(body.length, 256), StandardCharsets.UTF_8);
                    String explain = "";
                    try {
                        JsonNode root = objectMapper.readTree(bodyStr);
                        String info = root.path("infocode").asText();
                        String detail = AMAP_ERROR_HELP.getOrDefault(info, root.path("info").asText(""));
                        if (!detail.isBlank()) explain = "。infocode=" + info + "：" + detail;
                    } catch (Exception ignored) {}
                    return StaticMapResult.fail("高德静态图返回的不是 PNG 图片，而是错误响应" + explain
                            + "。原始前256字节：" + bodyStr);
                }
            }

            // 7) 写磁盘缓存，并发安全：写到临时文件再原子 move
            Path tmp = STATIC_CACHE_DIR.resolve(cacheKey + ".part");
            Files.write(tmp, body);
            try { Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE); } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, cacheFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            log.info("高德静态图下载成功：{} × {} 像素，{} 字节 → cacheKey={}", w, h, body.length, cacheKey);
            return StaticMapResult.ok(title, cacheKey, cacheFile);
        } catch (java.net.ConnectException | java.net.UnknownHostException ce) {
            return StaticMapResult.fail("高德静态图下载失败（网络连接问题）：" + ce.getMessage());
        } catch (Exception e) {
            return StaticMapResult.fail("高德静态图下载异常：" + e.getClass().getSimpleName() + " " + e.getMessage());
        }
    }

    /** 静态图调用结果（cacheKey 用于 Controller 代理访问）。 */
    public record StaticMapResult(boolean ok,
                                  String title,
                                  String cacheKey,
                                  java.nio.file.Path localPath,
                                  String error,
                                  String previewUrl) {
        static StaticMapResult ok(String t, String ck, java.nio.file.Path p) {
            return new StaticMapResult(true, t, ck, p, null, "/api/amap/static/" + ck + ".png");
        }
        static StaticMapResult fail(String e) { return new StaticMapResult(false, null, null, null, e, null); }
    }

    /** 调用方传入的坐标点。 */
    public record Point(String name, double lng, double lat) {}

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    /** SHA-256（小写 hex），URL/内容都用这个。 */
    private static String sha256(String s) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    /** 给 AmapController 用：返回缓存文件路径（或 null 表示不存在）。 */
    public Path staticCacheFile(String cacheKey) {
        if (cacheKey == null) return null;
        // 防目录穿越
        if (cacheKey.contains("/") || cacheKey.contains("\\") || cacheKey.contains("..")) return null;
        if (!cacheKey.matches("[a-f0-9]{64}")) return null; // sha256 = 64 hex
        Path p = STATIC_CACHE_DIR.resolve(cacheKey + ".png");
        return Files.exists(p) ? p : null;
    }
}
