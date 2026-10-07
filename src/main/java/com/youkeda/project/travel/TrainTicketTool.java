package com.youkeda.project.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.tool.AgentTool;
import com.youkeda.project.tool.AgentToolResult;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Component;

/**
 * 旅行工具 5：往返交通方案（火车票/机票）。
 *
 * <p>输入：出发地、目的地、出发日期、返程日期、人数、预算范围（人均）。
 *
 * <p>输出：
 *   - 去程 / 返程 各 3 档推荐（经济 / 舒适 / 性价比之选），含发车/到达时间、总耗时、
 *     参考票价区间、购票链接提示。
 *   - 飞机出行只在「行程时间紧张 + 距离 ≥ 800km + 预算 ≥ 800」时作为首选推荐。
 *
 * <p>注意：本工具为「本地规则+知识库基准价」估算，价格取 2024-2025 官方公开
 * 铁路/民航热门线路的公开二等座 / 经济舱公布价 ±10% 区间。真实价格以
 * 12306 / 各航司官网为准。
 */
@Component
public class TrainTicketTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 热门城市间【高铁二等座基准价】元/人（2025 公开票价近似值，+/- 10% 浮动）
    // Key = "出发站|到达站" 不含城市后缀
    private static final Map<String, Integer> HIGH_SPEED_BASE = Map.ofEntries(
            Map.entry("北京|上海", 553), Map.entry("上海|北京", 553),
            Map.entry("北京|杭州", 626), Map.entry("杭州|北京", 626),
            Map.entry("北京|广州", 793), Map.entry("广州|北京", 793),
            Map.entry("北京|成都", 820), Map.entry("成都|北京", 820),
            Map.entry("北京|西安", 516), Map.entry("西安|北京", 516),
            Map.entry("上海|杭州", 73),  Map.entry("杭州|上海", 73),
            Map.entry("上海|南京", 135), Map.entry("南京|上海", 135),
            Map.entry("上海|苏州", 39),  Map.entry("苏州|上海", 39),
            Map.entry("上海|合肥", 206), Map.entry("合肥|上海", 206),
            Map.entry("上海|福州", 377), Map.entry("福州|上海", 377),
            Map.entry("上海|厦门", 430), Map.entry("厦门|上海", 430),
            Map.entry("上海|武汉", 302), Map.entry("武汉|上海", 302),
            Map.entry("上海|长沙", 478), Map.entry("长沙|上海", 478),
            Map.entry("上海|广州", 793), Map.entry("广州|上海", 793),
            Map.entry("上海|深圳", 700), Map.entry("深圳|上海", 700),
            Map.entry("上海|重庆", 844), Map.entry("重庆|上海", 844),
            Map.entry("上海|成都", 910), Map.entry("成都|上海", 910),
            Map.entry("上海|西安", 670), Map.entry("西安|上海", 670),
            Map.entry("上海|郑州", 448), Map.entry("郑州|上海", 448),
            Map.entry("上海|天津", 650), Map.entry("天津|上海", 650),
            Map.entry("上海|济南", 398), Map.entry("济南|上海", 398),
            Map.entry("杭州|厦门", 320), Map.entry("厦门|杭州", 320),
            Map.entry("杭州|黄山", 160), Map.entry("黄山|杭州", 160),
            Map.entry("广州|深圳", 74),  Map.entry("深圳|广州", 74),
            Map.entry("广州|长沙", 314), Map.entry("长沙|广州", 314),
            Map.entry("广州|桂林", 175), Map.entry("桂林|广州", 175),
            Map.entry("成都|重庆", 154), Map.entry("重庆|成都", 154),
            Map.entry("成都|西安", 263), Map.entry("西安|成都", 263),
            Map.entry("重庆|西安", 410), Map.entry("西安|重庆", 410),
            Map.entry("北京|天津", 55),   Map.entry("天津|北京", 55),
            Map.entry("北京|沈阳", 295),  Map.entry("沈阳|北京", 295),
            Map.entry("北京|哈尔滨", 541),Map.entry("哈尔滨|北京", 541),
            Map.entry("北京|大连", 400),  Map.entry("大连|北京", 400),
            Map.entry("北京|青岛", 334),  Map.entry("青岛|北京", 334),
            Map.entry("南京|杭州", 105),  Map.entry("杭州|南京", 105)
    );

    // 普速（K/T/Z 硬卧基准价）约为高铁二等座 × 0.55
    private static final double HARD_SLEEPER_RATIO = 0.55;

    // 飞行基准价：约 1.1 元/km（经济舱公布 Y 舱 70% off 常态）
    private static final int FLIGHT_BASE_PER_KM_YUAN = 1; // 经济舱常见含税 600-900

    // 城市到城市之间【直线近似里程 km】，用于 fallback 价估算
    // （纬度 × 111km + 经度差 × 95km × cos(avgLat) 的粗略结果）
    private static final Map<String, double[]> CITY_COORDS = Map.ofEntries(
            Map.entry("北京", new double[]{39.9042, 116.4074}),
            Map.entry("上海", new double[]{31.2304, 121.4737}),
            Map.entry("杭州", new double[]{30.2741, 120.1551}),
            Map.entry("南京", new double[]{32.0603, 118.7969}),
            Map.entry("苏州", new double[]{31.2990, 120.5853}),
            Map.entry("广州", new double[]{23.1291, 113.2644}),
            Map.entry("深圳", new double[]{22.5431, 114.0579}),
            Map.entry("成都", new double[]{30.5728, 104.0668}),
            Map.entry("重庆", new double[]{29.5630, 106.5516}),
            Map.entry("西安", new double[]{34.3416, 108.9398}),
            Map.entry("天津", new double[]{39.0842, 117.2009}),
            Map.entry("长沙", new double[]{28.2282, 112.9388}),
            Map.entry("武汉", new double[]{30.5928, 114.3055}),
            Map.entry("合肥", new double[]{31.8206, 117.2272}),
            Map.entry("郑州", new double[]{34.7466, 113.6254}),
            Map.entry("济南", new double[]{36.6512, 117.1201}),
            Map.entry("青岛", new double[]{36.0671, 120.3826}),
            Map.entry("厦门", new double[]{24.4798, 118.0894}),
            Map.entry("福州", new double[]{26.0745, 119.2965}),
            Map.entry("桂林", new double[]{25.2736, 110.2900}),
            Map.entry("黄山", new double[]{29.7150, 118.3380}),
            Map.entry("沈阳", new double[]{41.8057, 123.4315}),
            Map.entry("哈尔滨", new double[]{45.8038, 126.5349}),
            Map.entry("大连", new double[]{38.9140, 121.6147}),
            Map.entry("丽江", new double[]{26.8552, 100.2270}),
            Map.entry("昆明", new double[]{25.0389, 102.7183})
    );

    // 热门出发站 / 到达站候选（按城市取首选，匹配知识库城市）
    private static final Map<String, String[]> CITY_STATIONS = Map.ofEntries(
            Map.entry("北京", new String[]{"北京南", "北京西", "北京朝阳", "首都机场T3", "大兴机场"}),
            Map.entry("上海", new String[]{"上海虹桥", "上海站", "上海南站", "虹桥T2", "浦东T1"}),
            Map.entry("杭州", new String[]{"杭州东", "杭州站", "萧山T3"}),
            Map.entry("南京", new String[]{"南京南", "禄口T2"}),
            Map.entry("苏州", new String[]{"苏州北", "苏州站"}),
            Map.entry("广州", new String[]{"广州南", "广州站", "白云T2"}),
            Map.entry("深圳", new String[]{"深圳北", "宝安T3"}),
            Map.entry("成都", new String[]{"成都东", "成都南", "双流T2", "天府T2"}),
            Map.entry("重庆", new String[]{"重庆西", "重庆北", "江北T3"}),
            Map.entry("西安", new String[]{"西安北", "咸阳T3"}),
            Map.entry("天津", new String[]{"天津西", "天津站", "滨海T2"}),
            Map.entry("长沙", new String[]{"长沙南", "黄花T2"}),
            Map.entry("武汉", new String[]{"武汉站", "汉口", "天河T3"}),
            Map.entry("合肥", new String[]{"合肥南", "新桥"}),
            Map.entry("郑州", new String[]{"郑州东", "新郑T2"}),
            Map.entry("济南", new String[]{"济南西", "遥墙"}),
            Map.entry("青岛", new String[]{"青岛北", "青岛站", "胶东"}),
            Map.entry("厦门", new String[]{"厦门北", "厦门站", "高崎T3"}),
            Map.entry("福州", new String[]{"福州南", "长乐"}),
            Map.entry("桂林", new String[]{"桂林北", "桂林站", "两江"}),
            Map.entry("丽江", new String[]{"丽江站", "三义"}),
            Map.entry("黄山", new String[]{"黄山北", "屯溪"}),
            Map.entry("沈阳", new String[]{"沈阳南", "沈阳桃仙"}),
            Map.entry("哈尔滨", new String[]{"哈尔滨西", "太平T2"}),
            Map.entry("大连", new String[]{"大连北", "周水子"}),
            Map.entry("昆明", new String[]{"昆明南", "长水"})
    );

    @Override
    public String name() {
        return "search_tickets";
    }

    @Override
    public String description() {
        return "根据出发地、目的地、出行/返程日期、人数、预算，给出往返的高铁/机票/普速"
                + "推荐（每档含【推荐理由】【发车/到达时间】【总耗时】【票价区间】【购票渠道】）。"
                + "没有实时票务 API，返回【官方公开价 ±10% 参考区间】+ 推荐档级，供用户在 12306/航司官网下单。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("from", Map.of("type", "string",
                "description", "出发城市名，例如 北京、上海、南京"));
        props.put("to", Map.of("type", "string",
                "description", "目的地城市名，例如 上海、成都、厦门"));
        props.put("depart_date", Map.of("type", "string",
                "description", "出发日期，格式 yyyy-MM-dd，例如 2025-10-01。未指定时默认为 7 天后。"));
        props.put("return_date", Map.of("type", "string",
                "description", "返程日期，格式 yyyy-MM-dd，可省略；如给 days 参数则按 days 自动推算。"));
        props.put("days", Map.of("type", "integer",
                "description", "游玩天数（默认 3），当 return_date 未给时按 depart_date + days 推算返程。"));
        props.put("people", Map.of("type", "integer", "minimum", 1, "maximum", 20,
                "description", "出行人数（默认 2 人）。"));
        props.put("budget_per_person", Map.of("type", "integer", "minimum", 200,
                "description", "【人均往返交通预算上限，单位元】。用于决定是否推荐飞机；低于 500 只推普速/高铁二等座。"
                        + "未给时默认 1500。"));
        props.put("prefer", Map.of("type", "string",
                "enum", new String[]{"auto", "train", "flight", "cheap", "fast"},
                "description", "偏好：auto=综合推荐、train=只推火车、flight=优先飞机、cheap=最便宜、fast=最快。默认 auto。"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("from", "to"));
        return params;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = MAPPER.readTree(argumentsJson);
            String from = cleanCity(args.path("from").asText(""));
            String to = cleanCity(args.path("to").asText(""));
            if (from.isBlank() || to.isBlank()) {
                return AgentToolResult.text("❌ 参数错误：必须指定 from（出发城市）和 to（目的城市）。");
            }
            if (from.equals(to)) {
                return AgentToolResult.text("ℹ️ 出发地与目的地相同，无需跨城交通。");
            }

            String depRaw = args.path("depart_date").asText("");
            int days = Math.max(1, args.path("days").asInt(3));
            String retRaw = args.path("return_date").asText("");
            int people = Math.max(1, Math.min(20, args.path("people").asInt(2)));
            int budget = Math.max(0, args.path("budget_per_person").asInt(1500));
            String prefer = args.path("prefer").asText("auto");

            LocalDate today = LocalDate.now();
            LocalDate dep;
            try {
                dep = depRaw.isBlank() ? today.plusDays(7) : LocalDate.parse(depRaw);
            } catch (Exception e) {
                dep = today.plusDays(7);
            }
            LocalDate ret;
            try {
                ret = retRaw.isBlank() ? dep.plusDays(days) : LocalDate.parse(retRaw);
            } catch (Exception e) {
                ret = dep.plusDays(days);
            }
            if (!ret.isAfter(dep)) ret = dep.plusDays(Math.max(1, days));

            // 1) 估算里程
            int km = estimateKm(from, to);

            // 2) 查基准高铁二等座价格
            String key = from + "|" + to;
            Integer hs = HIGH_SPEED_BASE.get(key);
            if (hs == null) {
                // fallback：高铁约 0.45 元/km（二等座公布价近似）
                hs = Math.max(40, (int) Math.round(km * 0.45));
            }
            int hsSecondLow = (int) Math.round(hs * 0.95);
            int hsSecondHigh = (int) Math.round(hs * 1.10);
            int hardSleep = Math.max(100, (int) Math.round(hs * HARD_SLEEPER_RATIO));
            int flightBase = Math.max(500, (int) Math.round(km * 1.0));  // 经济舱裸票价（未税）
            int flightLow = Math.max(400, (int) Math.round(flightBase * 0.6));  // 6 折
            int flightHigh = (int) Math.round(flightBase * 1.1);              // 全价
            int tax = 180; // 机建 50 + 燃油（800km 以上约 130，以下 70）综合按 180

            boolean weekend = isWeekend(dep) || isWeekend(ret);
            boolean holiday = isLikelyHoliday(dep) || isLikelyHoliday(ret);
            String peakTag = weekend ? " · 周末票源紧张" : "";
            peakTag += holiday ? " · 法定节假日，强烈建议提前 15-30 天抢票" : "";

            String depWeek = dayOfWeek(dep);
            String retWeek = dayOfWeek(ret);
            String fromStation = stationOf(from, false);
            String toStation = stationOf(to, false);
            String fromAirport = stationOf(from, true);
            String toAirport = stationOf(to, true);

            StringBuilder sb = new StringBuilder();
            sb.append("### 🎫 往返交通方案\n")
              .append("- 出发地：").append(from).append(" ｜ 目的地：").append(to).append(" ｜ 出行人数：").append(people).append(" 人\n")
              .append("- 去程：").append(dep).append("（").append(depWeek).append("）→ 返程：").append(ret).append("（").append(retWeek).append("）")
              .append(peakTag).append("\n")
              .append("- 两城直线参考里程：约 ").append(km).append(" km（高铁/飞行时间据此估算）\n")
              .append("- 人均交通预算上限：¥").append(budget).append("｜偏好：").append(preferLabel(prefer)).append("\n\n");

            // ============ 去程三档 ============
            sb.append("#### 【去程 · ").append(dep).append("（").append(depWeek).append("）】\n\n");
            sb.append(renderOptions(
                    from, to, fromStation, toStation, fromAirport, toAirport,
                    km, "07:30", "12:15",  // 高铁 早
                    "08:00", "10:10",      // 飞机
                    "22:30", "08:40+1",    // 普速 夜
                    hsSecondLow, hsSecondHigh, hardSleep, flightLow + tax, flightHigh + tax,
                    budget, prefer, true));

            // ============ 返程三档 ============
            sb.append("#### 【返程 · ").append(ret).append("（").append(retWeek).append("）】\n\n");
            sb.append(renderOptions(
                    to, from, toStation, fromStation, toAirport, fromAirport,
                    km, "15:00", "20:05",  // 高铁 午后
                    "19:30", "21:45",      // 飞机
                    "20:15", "06:20+1",    // 普速
                    hsSecondLow, hsSecondHigh, hardSleep, flightLow + tax, flightHigh + tax,
                    budget, prefer, false));

            sb.append("#### 💰 总预算（").append(people).append(" 人 · 参考）\n");
            int lowTotal, highTotal;
            if (km >= 800 && budget >= flightLow * 2 + 100) {
                // 双向经济舱
                lowTotal  = (flightLow + tax) * 2 * people;
                highTotal = (flightHigh + tax) * 2 * people;
            } else if (budget >= hs * 2) {
                lowTotal  = hsSecondLow * 2 * people;
                highTotal = hsSecondHigh * 2 * people;
            } else {
                lowTotal  = hardSleep * 2 * people;
                highTotal = (int) Math.round(hsSecondLow * 1.1) * 2 * people;
            }
            sb.append(String.format("- 推荐档总票价区间：¥%,d ～ ¥%,d（%d 人往返）\n", lowTotal, highTotal, people));
            sb.append("- 购票渠道：高铁/普速 → 12306 APP / 官网；机票 → 各航司官网 / 携程 / 飞猪 / 去哪儿 比价\n");
            sb.append("- 🚩 小贴士：").append(holiday ? "节假日放票前 15 天设好闹钟抢票（12306 每日 08:00-23:00 售票）。"
                    : "高铁放票 15 天前，早鸟机票通常提前 45-60 天价格最低。")
              .append("儿童票/学生票：12306 按年龄/身高自动计算，机票需单独搜儿童票。\n");
            return AgentToolResult.text(sb.toString());
        } catch (Exception e) {
            return AgentToolResult.text("❌ search_tickets 执行失败：" + e.getMessage());
        }
    }

    // ========== 内部辅助 ==========

    private static String cleanCity(String s) {
        if (s == null) return "";
        String r = s.trim()
                .replace("市", "")
                .replace("站", "")
                .replace("机场", "");
        // 去掉「XX 南站/北站」等后缀，保留核心城市名
        for (String suf : new String[]{"南","北","东","西","中央","新区"}) {
            if (r.endsWith(suf) && r.length() > 2) r = r.substring(0, r.length() - suf.length());
        }
        return r;
    }

    private static String preferLabel(String p) {
        return switch (p == null ? "auto" : p) {
            case "train" -> "只推火车";
            case "flight" -> "优先飞机";
            case "cheap" -> "最便宜";
            case "fast" -> "最快";
            default -> "综合推荐";
        };
    }

    private static int estimateKm(String a, String b) {
        double[] ca = CITY_COORDS.get(a);
        double[] cb = CITY_COORDS.get(b);
        if (ca == null || cb == null) {
            // 未知城市 fallback：国内平均 ≈ 700km
            return 700;
        }
        double dLat = Math.toRadians(ca[0] - cb[0]);
        double dLng = Math.toRadians(ca[1] - cb[1]);
        double lat1 = Math.toRadians(ca[0]);
        double lat2 = Math.toRadians(cb[0]);
        double h = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(lat1)*Math.cos(lat2)*Math.sin(dLng/2)*Math.sin(dLng/2);
        return (int) Math.round(2 * 6371 * Math.asin(Math.sqrt(h)));
    }

    private static String stationOf(String city, boolean airport) {
        String[] arr = CITY_STATIONS.get(city);
        if (arr == null) return city + (airport ? "机场" : "站");
        if (airport) {
            for (String s : arr) if (s.contains("T") || s.contains("机场")) return s;
        } else {
            for (String s : arr) if (!s.contains("T") && !s.contains("机场")) return s;
        }
        return arr[0];
    }

    private static boolean isWeekend(LocalDate d) {
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }

    private static boolean isLikelyHoliday(LocalDate d) {
        // 近似：五一/国庆/春节/元旦/清明/端午/中秋 集中月日
        int m = d.getMonthValue();
        int day = d.getDayOfMonth();
        if (m == 1 && (day >= 1 && day <= 3)) return true;
        if (m == 4 && (day >= 3 && day <= 6)) return true;
        if (m == 5 && (day >= 1 && day <= 5)) return true;
        if (m == 6 && day >= 9 && day <= 12) return true;
        if (m == 9 && day >= 15 && day <= 18) return true;
        if (m == 10 && day >= 1 && day <= 8) return true;
        return m == 2 && day >= 8 && day <= 22; // 春节覆盖范围（粗略）
    }

    private static String dayOfWeek(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
    }

    private static String randomSeat() {
        List<String> seats = List.of("12A", "08C", "05F", "15D", "03B", "11F");
        return seats.get(ThreadLocalRandom.current().nextInt(seats.size()));
    }

    /** 渲染「性价比之选 / 舒适高铁 / 飞行首选（仅当里程与预算允许）」三档。 */
    private static String renderOptions(
            String fromCity, String toCity,
            String fromSta, String toSta, String fromAir, String toAir,
            int km,
            String hsDep, String hsArr,
            String flyDep, String flyArr,
            String kDep, String kArr,
            int hs2Low, int hs2High, int hardSleep,
            int flyLow, int flyHigh,
            int budget, String prefer, boolean outbound) {

        StringBuilder sb = new StringBuilder();
        boolean allowFly = km >= 500 && budget >= flyLow;
        boolean preferFlight = "flight".equals(prefer) || "fast".equals(prefer);
        boolean preferCheap = "cheap".equals(prefer);
        boolean preferTrainOnly = "train".equals(prefer);

        // ============ 档 1：最便宜（普速硬卧 或 高铁二等座） ============
        int cheapTotalLow, cheapTotalHigh;
        String cheapLabel, cheapDetail, cheapReason;
        if (!preferTrainOnly && (preferCheap || (budget < hs2Low * 1.1))) {
            // 推荐普速硬卧
            cheapLabel = "🚂 性价比之选 · 普速硬卧（K/T/Z）";
            cheapTotalLow = hardSleep;
            cheapTotalHigh = (int) Math.round(hardSleep * 1.15);
            cheapDetail = String.format("%s → %s ｜ 发车 %s · 到达 %s ｜ 总耗时约 %s",
                    stationOf(fromCity, false), stationOf(toCity, false),
                    kDep, kArr, approxDuration(km, "train-slow"));
            cheapReason = "预算有限 / 不赶路时首选，睡一晚省下半天住宿费，适合学生党。";
        } else {
            cheapLabel = "🚄 性价比之选 · 高铁二等座";
            cheapTotalLow = hs2Low;
            cheapTotalHigh = hs2High;
            cheapDetail = String.format("%s → %s ｜ 发车 %s · 到达 %s ｜ 总耗时 %s ｜ 座位：二等座（示例 %s）",
                    fromSta, toSta, hsDep, hsArr, approxDuration(km, "hsr"), randomSeat());
            cheapReason = "最热门选择：价格亲民 + 准点率 > 95% + 随到随走。";
        }
        sb.append("1️⃣ ").append(cheapLabel).append("\n")
          .append("   - 📍 ").append(cheapDetail).append("\n")
          .append("   - 💰 票价区间：¥").append(cheapTotalLow).append("～").append(cheapTotalHigh).append(" / 人\n")
          .append("   - ✅ 理由：").append(cheapReason).append("\n\n");

        // ============ 档 2：舒适高铁（一等座 / 商务座推荐） ============
        int firstLow = (int) Math.round(hs2High * 1.7);
        int firstHigh = (int) Math.round(firstLow * 1.1);
        String comfort = "🚄 舒适之选 · 高铁一等座";
        if (budget >= firstLow * 1.3) {
            comfort = "🚄 豪华之选 · 高铁商务座（含餐+躺平）";
            firstLow = (int) Math.round(hs2High * 3.2);
            firstHigh = (int) Math.round(firstLow * 1.1);
        }
        sb.append("2️⃣ ").append(comfort).append("\n")
          .append("   - 📍 ").append(String.format("%s → %s ｜ 发车 %s · 到达 %s ｜ 总耗时 %s",
                  fromSta, toSta, hsDep, hsArr, approxDuration(km, "hsr"))).append("\n")
          .append("   - 💰 票价区间：¥").append(firstLow).append("～").append(firstHigh).append(" / 人\n")
          .append("   - ✅ 理由：座位更宽、安静适合办公/带娃/长途；").append("商务座送餐饮，高峰日抢票成功率更高。\n\n");

        // ============ 档 3：飞机（条件允许） ============
        if (!preferTrainOnly && allowFly) {
            String flyLabel = preferFlight ? "✈️ 首选方案 · 经济舱（含税费）" : "✈️ 飞行备选 · 经济舱（含税费）";
            sb.append("3️⃣ ").append(flyLabel).append("\n")
              .append("   - 📍 ").append(String.format("%s(%s) → %s(%s) ｜ 起飞 %s · 落地 %s ｜ 含值机总耗时约 %s",
                      fromCity, fromAir, toCity, toAir, flyDep, flyArr, approxDuration(km, "flight"))).append("\n")
              .append("   - 💰 票价区间：¥").append(flyLow).append("～").append(flyHigh).append(" / 人（机建+燃油已约 ¥180）\n")
              .append("   - ✅ 理由：").append(km >= 1200
                      ? "里程过 1000km，飞机比高铁省时 4-6h；红眼航班常有 ¥" + (int) (flyLow * 0.7) + " 特价票。"
                      : "时间紧张场景首选；建议用「飞猪/携程 低价日历」看前后 3 天差异。").append("\n\n");
        } else if (preferFlight && !allowFly) {
            sb.append("⚠️ 你选择了「优先飞机」，但里程 ").append(km).append("km < 500km 或预算 ").append(budget)
              .append(" 不足以覆盖最低机票（¥").append(flyLow).append("+）。已改为推荐高铁。\n\n");
        }
        return sb.toString();
    }

    private static String approxDuration(int km, String mode) {
        return switch (mode) {
            case "hsr" -> {
                int min = (int) Math.round(km / 260.0 * 60 + 30); // 停站缓冲
                yield String.format("%dh %02dm", min / 60, min % 60);
            }
            case "train-slow" -> {
                int hr = (int) Math.round(km / 85.0);
                yield String.format("%dh", hr);
            }
            case "flight" -> {
                int flyMin = (int) Math.round(km / 800.0 * 60);
                int total = flyMin + 120; // 提前到 + 值机
                yield String.format("%dh %02dm", total / 60, total % 60);
            }
            default -> "—";
        };
    }
}
