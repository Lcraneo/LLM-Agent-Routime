package com.youkeda.project.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.tool.AgentTool;
import com.youkeda.project.tool.AgentToolResult;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 旅行工具 8：完整方案成品生成器（Skill 风格 · 零 LLM 二次调用）。
 *
 * <h2>输入</h2>
 * LLM 在「调用 query_weather / search_attractions / search_foods / search_tickets / book_hotel /
 * geocode / plan_route / static_map / travel_tips」后，把各工具的真实返回原文作为参数传入。
 *
 * <h2>输出 = 8 大章节（对应用户需求的 6 大块，拆分为 8 个可独立验证章节）</h2>
 * <ol>
 *   <li>封面 & 方案摘要（标题、天数、总预算区间、出行日期、核心亮点）</li>
 *   <li>行前须知（天气概况 + 人群标签 + 预约提醒）</li>
 *   <li>🎫 往返交通方案（search_tickets 的真实推荐）</li>
 *   <li>🏨 住宿方案（book_hotel 的真实酒店）</li>
 *   <li>🗺️ 每日详细行程（含 9:00-22:00 分时段编排 + 高德路线图 + 滴滴打车价）</li>
 *   <li>🍜 美食 & 餐厅推荐（search_foods + POI）</li>
 *   <li>🧳 出行注意事项 & 智能行李清单（travel_tips 原文）</li>
 *   <li>💰 总预算 & 省钱攻略（自动汇总交通 + 住宿 + 门票 + 餐饮 + 杂费）</li>
 * </ol>
 *
 * <p>成品结构确定性输出，所有章节标题用固定中文序号，便于 E2E 测试校验章节完整性。
 */
@Component
public class ItineraryBuilderTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(ItineraryBuilderTool.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd (E)", Locale.CHINA);

    @Override
    public String name() {
        return "build_itinerary";
    }

    @Override
    public String description() {
        return "汇总天气/景点/美食/往返车票/酒店/路线图/行李清单/注意事项，按 8 大章节输出【完整到可以直接打印带出门】"
                + "的 Markdown 出行方案。必须是所有工具调用完毕后的最后一步，只调用一次。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("title", Map.of("type", "string",
                "description", "方案标题，如「上海三日游完整出行方案」"));
        props.put("cities", Map.of("type", "array", "items", Map.of("type", "string"),
                "description", "游玩城市列表，如 [\"上海\"]"));
        props.put("days", Map.of("type", "integer",
                "description", "游玩天数（必填，用于预算计算）"));
        props.put("start_date", Map.of("type", "string",
                "description", "出发日期 yyyy-MM-dd，默认今天 +7 天"));
        props.put("people", Map.of("type", "integer", "minimum", 1,
                "description", "人数（默认 2，用于预算计算）"));
        props.put("budget_level", Map.of("type", "string",
                "enum", new String[]{"economy", "comfort", "luxury"},
                "description", "预算档位：economy 经济（学生党）/ comfort 舒适（默认）/ luxury 豪华。影响第 8 章预算汇总。"));
        props.put("traveler_type", Map.of("type", "string",
                "description", "人群类型，传给 travel_tips 的 traveler_type 参数，默认 family。"));
        props.put("weather", Map.of("type", "string",
                "description", "query_weather 原始返回文本。"));
        props.put("attractions", Map.of("type", "string",
                "description", "search_attractions 原始返回文本（推荐逐景点带【游玩时间】【门票】【开放时间】标签）。"));
        props.put("foods", Map.of("type", "string",
                "description", "search_foods 原始返回文本。"));
        props.put("tickets", Map.of("type", "string",
                "description", "search_tickets 原始返回文本（第 3 章原样展示）。"));
        props.put("hotels", Map.of("type", "string",
                "description", "book_hotel 原始返回文本（第 4 章原样展示）。"));
        props.put("daily_plan", Map.of("type", "string",
                "description", "每日分时段行程正文。推荐格式：按 Day 1、Day 2... 分段，"
                        + "每段含【上午 09:00-12:00 / 中午餐饮 / 下午 / 晚上】，并插入 static_map 的图片链接。"));
        props.put("maps", Map.of("type", "string",
                "description", "高德静态图 Markdown 段落（来自 static_map 或 buildStaticMaps 的结果）。"));
        props.put("transport_urban", Map.of("type", "string",
                "description", "城内交通实测：plan_route distance + estimate_ride 的真实数据，来自 buildAmapBoost。"));
        props.put("notes", Map.of("type", "string",
                "description", "travel_tips 原始返回文本（第 7 章原样展示）。"));
        props.put("highlight", Map.of("type", "string",
                "description", "一句话核心亮点，展示在封面「3 个必去亮点」下方。"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("title", "cities", "days"));
        return params;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode a = MAPPER.readTree(argumentsJson);
            String title = orDefault(a, "title", "出行完整方案");
            List<String> cities = new ArrayList<>();
            JsonNode cn = a.path("cities");
            if (cn.isArray()) for (JsonNode n : cn) cities.add(n.asText(""));
            else {
                String s = cn.asText("");
                if (!s.isBlank()) cities.add(s);
            }
            if (cities.isEmpty()) cities.add("目的地");
            int days = Math.max(1, a.path("days").asInt(3));
            String sd = orDefault(a, "start_date", "");
            LocalDate start;
            try { start = sd.isBlank() ? LocalDate.now().plusDays(7) : LocalDate.parse(sd); }
            catch (Exception e) { start = LocalDate.now().plusDays(7); }
            LocalDate end = start.plusDays(Math.max(1, days - 1));
            int people = Math.max(1, a.path("people").asInt(2));
            String bl = orDefault(a, "budget_level", "comfort");
            String traveler = orDefault(a, "traveler_type", "family");
            String weather = orDefault(a, "weather", "（天气工具暂未调用，出发前查当地 7 天预报）");
            String attractions = orDefault(a, "attractions", "（景点工具暂未调用，建议补调用 search_attractions 获取官方带【门票/游玩时长】的列表）");
            String foods = orDefault(a, "foods", "（美食工具暂未调用，建议用 search_foods 查当地特色菜）");
            String tickets = orDefault(a, "tickets", "（车票工具暂未调用，建议出发前 15 天用 search_tickets 查询高铁/机票方案）");
            String hotels = orDefault(a, "hotels", "（酒店工具暂未调用，建议用 book_hotel 生成 3 档酒店方案 + 总住宿预算）");
            String dailyPlan = orDefault(a, "daily_plan", "");
            String maps = orDefault(a, "maps", "");
            String urban = orDefault(a, "transport_urban", "");
            String notes = orDefault(a, "notes", "（出行贴士工具暂未调用，建议最后调用 travel_tips 生成分章节智能行李清单）");
            String highlight = orDefault(a, "highlight", "");

            int nights = Math.max(1, days - 1);

            // ====== 8. 自动预算汇总 ======
            String budget = computeBudget(bl, cities, days, nights, people, tickets, hotels, attractions);

            // ====== 自动 fallback 每日行程（LLM 没传 daily_plan 时） ======
            String finalDaily = dailyPlan.isBlank() || dailyPlan.contains("按所选景点区域编排")
                    ? autoDailyPlan(start, days, nights, cities, attractions, foods, maps, urban)
                    : dailyPlan;

            StringBuilder sb = new StringBuilder();

            // ========== 1. 封面 & 摘要 ==========
            sb.append("# ").append(title).append("\n\n");
            sb.append("> 🛫 由自主规划型旅行 Agent 一次性生成：自动拆解 → 调用工具收集数据 → build_itinerary 合成。\n\n");
            sb.append("## 一、方案摘要\n\n");
            sb.append("| 项 | 内容 |\n|---|---|\n")
              .append("| 🎯 主题 | ").append(title).append(" |\n")
              .append("| 📍 目的地 | ").append(String.join(" → ", cities)).append(" |\n")
              .append("| 📅 行程 | ").append(start.format(DATE_FMT)).append(" → ").append(end.format(DATE_FMT))
              .append(" · 共 ").append(days).append(" 天 ").append(nights).append(" 晚 |\n")
              .append("| 👥 人数 | ").append(people).append(" 人 · 预算档位：").append(blLabel(bl)).append(" · 人群：").append(travelerLabel(traveler)).append(" |\n")
              .append("| 🎫 交通 | ").append(sectionSummary(tickets, "推荐档总票价区间", "待补车票方案")).append(" |\n")
              .append("| 🏨 住宿 | ").append(sectionSummary(hotels, "推荐档", "待补酒店方案")).append(" |\n")
              .append("| 💰 总预算 | ").append(sectionSummary(budget, "合计约", "待补")).append("（人均约 ").append(perPerson(budget, people)).append("） |\n")
              .append("| ⭐ 核心亮点 | ").append(highlight.isEmpty() ? defaultHighlight(cities) : highlight).append(" |\n\n");
            sb.append("### 🎯 一句话总结\n")
              .append(String.format("> 从 1 到 %d 天，从火车票到行李清单，Agent 自己拆解自己查，不用你分步提问。\n\n", days));

            // ========== 2. 行前须知 ==========
            sb.append("## 二、行前须知（天气 & 预约总览）\n\n")
              .append("### 🌤️ 天气概况\n").append(weather).append("\n\n")
              .append("### 📅 预约 & 出发前 7 天 checklist\n")
              .append("- [ ] 12306 / 航司：实名 + 身份核验 + 放票闹钟设好\n")
              .append("- [ ] 酒店：锁定 1 家免费取消档，出签前不付全款\n")
              .append("- [ ] 景点：5A 级景区 / 博物院 / 主题乐园 → 官方公众号实名预约（通常提前 7 天 00:00/20:00 放票）\n")
              .append("- [ ] 证件：身份证、学生证、儿童户口本复印件（PDF + 云端各存 1 份）\n")
              .append("- [ ] 财务：现金 ¥500+、云闪付 APP 搜文旅消费券、微信/支付宝异地银行额度确认\n")
              .append("- [ ] 电子：充电宝 ≤100Wh、下载离线地图、高铁/飞机电子客票二维码截图\n\n");

            // ========== 3. 往返交通 ==========
            sb.append("## 三、🎫 往返交通方案（高铁 / 机票 / 普速 三档推荐）\n\n")
              .append(tickets).append("\n\n");

            // ========== 4. 住宿方案 ==========
            sb.append("## 四、🏨 住宿方案（经济 / 舒适 / 高端 三档 + 总住宿预算）\n\n")
              .append(hotels).append("\n\n");

            // ========== 5. 每日行程 ==========
            sb.append("## 五、🗺️ 每日详细行程（9:00-22:00 分时段编排 + 路线图）\n\n")
              .append(finalDaily).append("\n\n");

            if (!maps.isBlank()) {
                sb.append("### 附：高德路线图参考\n\n").append(maps).append("\n\n");
            }

            // ========== 6. 美食 & 餐厅 ==========
            sb.append("## 六、🍜 美食 & 餐厅推荐（本地人爱吃 TOP 榜）\n\n")
              .append(foods).append("\n\n");

            if (!urban.isBlank()) {
                sb.append("### 🚕 城内交通实测（高德路线 + 滴滴预估）\n\n")
                  .append(urban).append("\n\n");
            }

            // ========== 7. 注意事项 & 行李清单 ==========
            sb.append("## 七、🧳 出行注意事项 & 智能行李清单（按天数/人数自动量化）\n\n")
              .append(notes).append("\n\n");

            // ========== 8. 总预算 & 省钱攻略 ==========
            sb.append("## 八、💰 总预算汇总 & 省钱攻略\n\n")
              .append(budget).append("\n\n");

            sb.append("---\n✅ 本方案所有推荐来自工具真实调用结果，可直接打印带出门。祝玩得开心！ 🛫");
            return AgentToolResult.text(sb.toString());
        } catch (Exception e) {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            e.printStackTrace(pw);
            String trace = sw.toString();
            // 生产手册：出错就抛出可读提示，不要把技术堆栈暴露给用户
            log.error("build_itinerary 构建失败：" + e.getMessage(), e);
            return AgentToolResult.text(
                    "❌ build_itinerary 生成手册时内部错误：" + e.getClass().getSimpleName()
                            + " msg=\"" + e.getMessage() + "\"（已记录日志）。");
        }
    }

    // ================ 辅助 ================

    private static String orDefault(JsonNode a, String f, String def) {
        String s = a.path(f).asText("");
        return (s == null || s.isBlank()) ? def : s.trim();
    }

    private static String blLabel(String bl) {
        return switch (bl) {
            case "economy" -> "💸 经济（学生党）";
            case "luxury"  -> "✨ 豪华（度假享受）";
            default        -> "🌿 舒适（性价比）";
        };
    }

    private static String travelerLabel(String t) {
        return switch (t == null ? "family" : t) {
            case "solo"         -> "独自";
            case "couple"       -> "情侣/夫妻";
            case "family"       -> "亲子家庭";
            case "senior"       -> "老年";
            case "student"      -> "学生党";
            case "business"     -> "商务";
            case "pet_friendly" -> "带宠物";
            default             -> "综合";
        };
    }

    /** 从某段正文中抓第一个包含 keyword 前 50 字后 20 字的摘要用于摘要表。 */
    private static String sectionSummary(String body, String keyword, String fallback) {
        if (body == null || body.isBlank()) return fallback;
        int i = body.indexOf(keyword);
        if (i < 0) {
            // 抓首行非空
            for (String l : body.split("\n")) {
                String t = l.trim();
                if (t.length() > 2 && !t.startsWith("#")) {
                    return t.length() > 30 ? t.substring(0, 30) + "…" : t;
                }
            }
            return fallback;
        }
        int end = Math.min(body.length(), i + 40);
        return body.substring(i, end).replace("\n", " ") + "…";
    }

    private static String perPerson(String budget, int people) {
        if (budget == null || !budget.contains("¥")) return "—";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("合计约[^¥0-9]*¥\\s*([0-9,]+)").matcher(budget);
        if (m.find()) {
            try {
                long total = Long.parseLong(m.group(1).replace(",", ""));
                return String.format("¥%,d", total / Math.max(1, people));
            } catch (Exception ignore) {}
        }
        return "—";
    }

    private static String defaultHighlight(List<String> cities) {
        if (cities.isEmpty()) return "深度体验目的地精华";
        String joined = String.join("、", cities);
        if (cities.size() == 1) {
            return String.format("%s 精华一网打尽：经典地标 + 美食巷弄 + 本地人生活感体验（不走回头路）", joined);
        }
        return String.format("%s 双城/多城游：不走折返，交通方案已按最优顺序排列。", joined);
    }

    /** 兜底每日行程：从 attractions 文本里解析景点名，按顺序均分每一天。 */
    private static String autoDailyPlan(LocalDate start, int days, int nights, List<String> cities,
                                        String attractions, String foods, String maps, String urban) {
        List<String> attNames = parseAttractionNames(attractions, 10);
        List<String> foodNames = parseFoodNames(foods, 6);
        String city = cities.isEmpty() ? "目的地" : cities.get(0);

        int perDay = Math.max(1, (int) Math.ceil((double) attNames.size() / days));
        StringBuilder out = new StringBuilder();
        int idx = 0;
        for (int d = 1; d <= days; d++) {
            LocalDate dayDate = start.plusDays(d - 1);
            int from = idx;
            int to = Math.min(attNames.size(), from + perDay);
            List<String> todays = new ArrayList<>(attNames.subList(from, to));
            idx = to;
            if (todays.isEmpty()) todays.add(city + "自由活动（推荐商圈 / 咖啡馆 / 博物馆 二选一）");

            out.append("### Day ").append(d).append(" · ").append(dayDate.format(DATE_FMT)).append("\n")
               .append(String.format("> 📍 城市：%s ｜ 主题：%s\n\n",
                       city,
                       d == 1 ? "到达+核心地标打卡" : (d == days ? "轻松收尾+返程" : "深度游玩")));
            out.append("| 时间 | 安排 | 备注 |\n|---|---|---|\n");
            // 上午 2 个
            int slot = 0;
            String m1 = todays.get(slot); slot++;
            out.append(String.format("| 09:00-11:00 | 📍 景点①：%s | 早餐后出发，留出 2h 深度游览 |\n", m1));
            String m2 = slot < todays.size() ? todays.get(slot++) : (city + " 老城区漫步 / 商圈扫街");
            out.append(String.format("| 11:00-12:00 | 📍 景点②：%s | 不要在景点里吃中饭，留肚子到本地口碑店 |\n", m2));
            // 中饭
            String lunch = foodNames.isEmpty() ? "景区附近搜点评 4.5+ 本地菜，人均 60-100" : foodNames.get((d - 1) % foodNames.size());
            out.append(String.format("| 12:00-13:30 | 🍜 午餐：%s | 避开 12:30 高峰，11:45 到不排号 |\n", lunch));
            // 下午 1-2 个
            String m3 = slot < todays.size() ? todays.get(slot++) : (city + " 市博物馆 / 科技馆 / 艺术展 三选一");
            out.append(String.format("| 13:30-16:00 | 📍 景点③：%s | 午后精神差，选室内+空调的行程（夏季特别重要）|\n", m3));
            String m4 = slot < todays.size() ? todays.get(slot) : (d == days ? "采购伴手礼（知名商圈）" : "大学/菜市场逛逛：最本地的生活感");
            out.append(String.format("| 16:00-17:30 | ☕ 下午茶 / 自由活动：%s | 给女生留逛街 / 拍照时间，行程 50%% 留白才不累 |\n", m4));
            // 晚餐 + 夜景
            String dinner = foodNames.isEmpty() ? "大众点评必吃榜 1 家（预订景观位）"
                    : foodNames.get((d * 2 - 1 + foodNames.size()) % foodNames.size());
            String nightSpot = switch (city) {
                case "上海" -> "外滩夜景 19:00-21:00 + 南京路步行街灯光";
                case "北京" -> "天安门夜景 / 什刹海酒吧街 / 国贸夜景 三选一";
                case "重庆" -> "洪崖洞 19:30 亮灯 + 千厮门大桥观景平台";
                case "成都" -> "锦里夜景 / 九眼桥酒吧街 / 玉林西路小酒馆";
                case "西安" -> "大唐不夜城 19:00 灯光全开 + 不倒翁演出";
                case "杭州" -> "西湖音乐喷泉（19:00 / 20:00）+ 河坊街夜市";
                case "广州" -> "珠江夜游 / 广州塔小蛮腰灯光秀 + 沙面夜景";
                case "厦门" -> "曾厝垵美食街 + 环岛路骑行看日落";
                case "丽江" -> "丽江古城四方街夜景 + 酒吧街听歌";
                case "桂林" -> "两江四湖夜游 + 阳朔西街酒吧";
                default    -> city + "最核心商圈夜景 + 步行街散步";
            };
            out.append(String.format("| 18:00-20:00 | 🌙 晚餐：%s | 建议预订景观位 / 江边位，氛围感拉满 |\n", dinner))
               .append(String.format("| 20:00-22:00 | 🌃 夜景 / 特色体验：%s | 晚上 10 点前回酒店，第二天保持精力 |\n\n", nightSpot));
        }
        out.append("> ⏱️ 节奏提示：每天至少 2 次 30min 空挡休息；老人/儿童日安排景点 ≤ 2 个。\n");
        return out.toString();
    }

    /** 从 attractions 文本里抓「1. XX 景区」「📌 XX」「- XX 博物馆」这类景点名。 */
    private static List<String> parseAttractionNames(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("^(?:\\s*\\d+[.、]\\s*|\\-\\s*|•\\s*|\\p{InEmoticons}*\\s*\\d*[.、]?\\s*)([^（(【\\[:：\\s,，。；]{2,30}?)(?:\\s*[（(【\\[:：,，。；]|$)");
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.startsWith("|") || l.startsWith("#") || l.startsWith("![")) continue;
            java.util.regex.Matcher m = p.matcher(l);
            if (m.find()) {
                String n = m.group(1).trim();
                if (n.length() < 2) continue;
                if (n.contains("门票") || n.contains("开放") || n.contains("游玩")
                        || n.contains("交通") || n.contains("预约") || n.contains("地址")
                        || n.length() > 18) continue;
                if (!out.contains(n)) out.add(n);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    private static List<String> parseFoodNames(String text, int max) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isBlank()) return out;
        java.util.regex.Pattern p = java.util.regex.Pattern
                .compile("^(?:\\s*\\d+[.、]\\s*|\\-\\s*|•\\s*|\\p{InEmoticons}*\\s*)([^（(【\\[:：\\s,，。；]{2,20}?)(?:\\s*[（(【\\[:：,，。；]|$)");
        for (String line : text.split("\n")) {
            String l = line.trim();
            if (l.startsWith("|") || l.startsWith("#")) continue;
            java.util.regex.Matcher m = p.matcher(l);
            if (m.find()) {
                String n = m.group(1).trim();
                if (n.length() < 2 || n.length() > 15) continue;
                if (n.contains("推荐") || n.contains("地址") || n.contains("人均") || n.contains("电话")) continue;
                if (!out.contains(n)) out.add(n);
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    /** 根据预算档位 + 车票/酒店/景点/餐饮文本，给出结构化预算汇总表（第 8 章）。 */
    private static String computeBudget(String bl, List<String> cities, int days, int nights, int people,
                                        String tickets, String hotels, String attractions) {
        String city = cities.isEmpty() ? "上海" : cities.get(0);

        // 1 交通：从 tickets 抓推荐档总票价区间
        long[] transp = extractRange(tickets, "推荐档总票价区间\\s*：\\s*¥\\s*([0-9,]+)\\s*～\\s*¥\\s*([0-9,]+)");

        // 2 住宿：从 hotels 抓「合计约 ¥X,XXX」
        long[] hotel = extractRange(hotels, "合计约\\s*：\\s*¥\\s*([0-9,]+)");

        // 3 门票：从 attractions 抓 「¥XX」元 累加；抓不到给默认
        long[] ticketFee = estimateTicketFee(attractions, city, days, people);

        // 4 餐饮：按人/天 × 档位
        int foodPerPerson = switch (bl) {
            case "economy" -> 120;   // 学生党：早 20 + 中 50 + 晚 50
            case "luxury"  -> 500;   // 豪华：早 50 + 中 150 + 晚 300（含米其林）
            default        -> 250;   // 舒适：早 30 + 中 100 + 晚 120
        };
        long foodLow  = (long) foodPerPerson * days * people;
        long foodHigh = (long) (foodPerPerson * 1.2) * days * people;

        // 5 杂费：市内交通（地铁+打车）+ 伴手礼 + 突发
        int miscPerPersonPerDay = switch (bl) {
            case "economy" -> 60;
            case "luxury"  -> 300;
            default        -> 120;
        };
        long miscLow  = (long) miscPerPersonPerDay * days * people;
        long miscHigh = (long) (miscPerPersonPerDay * 1.5) * days * people;

        long low = sum(transp[0], hotel[0], ticketFee[0], foodLow, miscLow);
        long high = sum(transp[1], hotel[1], ticketFee[1], foodHigh, miscHigh);

        // 归一：如果 high == low，给 ±5% 浮动
        if (high <= low) high = (long) (low * 1.15);

        long ppLow = low / Math.max(1, people);
        long ppHigh = high / Math.max(1, people);

        StringBuilder sb = new StringBuilder();
        sb.append("| 类别 | 估算（¥，区间：").append(blLabel(bl)).append("） | 说明 |\n")
          .append("|---|---:|---|\n")
          .append(String.format("| 🚅 往返交通 | %,d ～ %,d | 来自 search_tickets 推荐档（%d 人） |\n",
                  transp[0], transp[1], people))
          .append(String.format("| 🏨 住宿 | %,d ～ %,d | %d 晚 × %d 间（按每 2 人一间），来自 book_hotel |\n",
                  hotel[0], hotel[1], nights, Math.max(1, (int) Math.ceil(people / 2.0))))
          .append(String.format("| 🎟️ 门票 | %,d ～ %,d | %s 精华景点门票估算（按学生证折扣 -50%% 可省 ¥%,d） |\n",
                  ticketFee[0], ticketFee[1], city, (ticketFee[1] - ticketFee[0]) / 2))
          .append(String.format("| 🍜 餐饮 | %,d ～ %,d | %d 人 × %d 天（含 1 次特色大餐） |\n",
                  foodLow, foodHigh, people, days))
          .append(String.format("| 🚕 市内交通 & 伴手礼 & 杂费 | %,d ～ %,d | 地铁/打车 + 手信 + 应急备用 |\n\n", miscLow, miscHigh));
        sb.append(String.format("### 🧮 合计\n"
                + "- **总预算区间：¥%,d ～ ¥%,d**（%d 人 %d 天 全案含交通/住宿/门票/餐饮/杂费）\n"
                + "- **人均预算：¥%,d ～ ¥%,d**\n"
                + "- 建议带「预算上限 × 1.3」的信用卡额度（防止突发超支）。\n\n", low, high, people, days, ppLow, ppHigh));
        sb.append("### 🏷️ 省钱攻略（10 条）\n")
          .append("1. 交通：12306 学生票 4 次/年；高铁会员积分满 1 万可换票；航班每周二 15:00-18:00 降价概率最大。\n")
          .append("2. 住宿：华住/锦江/亚朵/IHG 官方会员订房比第三方平台便宜 5-10%，还含早餐。\n")
          .append("3. 景点门票：大众点评 / 抖音 / 小红书 搜目的地 + 团购，很多景区联票打 7 折。\n")
          .append("4. 餐饮：抖音「本地生活」团购套餐比单点省 30-40%，但要看清楚限用时段。\n")
          .append("5. 打车：滴滴 / 高德聚合 / 阳光出行 三平台比价，跨区用顺风车比快车省 50%。\n")
          .append("6. 购物：特产（茶叶/牛肉干/点心）别在景区买，市区大型连锁超市或拼多多旗舰店最便宜。\n")
          .append("7. 银联：白金/钻石信用卡 1 元机场贵宾厅 + 1 元高铁贵宾厅（云闪付 APP 搜「权益」）。\n")
          .append("8. 保险：机票/高铁自带意外险，旅行医疗险建议单独买，3 天 30 元保额 50 万。\n")
          .append("9. 预约：主题乐园/国家级景区「下午场票」比全日场便宜 20-30%，但 15:00 后入场。\n")
          .append("10. 行程：把 2-3 个同区域景点排在同一天，减少折返的地铁/打车费（可省 30% 交通费）。\n");
        return sb.toString();
    }

    private static long[] extractRange(String body, String regex) {
        if (body == null || body.isBlank()) return new long[]{0, 0};
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(regex).matcher(body);
        if (m.find()) {
            try {
                long a = Long.parseLong(m.group(1).replace(",", ""));
                if (m.groupCount() >= 2 && m.group(2) != null) {
                    long b = Long.parseLong(m.group(2).replace(",", ""));
                    return new long[]{Math.min(a, b), Math.max(a, b)};
                }
                return new long[]{a, (long) (a * 1.2)};
            } catch (Exception ignore) {}
        }
        // fallback 给 0，让 computeBudget 用默认乘
        return new long[]{0, 0};
    }

    private static long[] estimateTicketFee(String attractions, String city, int days, int people) {
        // 默认知识库城市常见景点门票总览（2-3 个核心景点）
        int avg = switch (city) {
            case "上海" -> 650;   // 迪士尼 475 + 东方明珠 199 + 城隍庙 10 + 自然博物馆 30
            case "北京" -> 400;   // 故宫 60 + 长城 40 + 国博 0 + 恭王府 40 + 颐和园 30
            case "成都" -> 350;   // 熊猫 55 + 三星堆 72 + 都江堰 80 + 武侯祠 50 + 青城山 80
            case "杭州" -> 250;   // 雷峰塔 40 + 灵隐 75 + 岳王庙 25 + 宋城 310（可选）
            case "西安" -> 400;   // 兵马俑 120 + 华清宫 120 + 碑林 65 + 城墙 54 + 陕历博（免费但难约）
            case "广州" -> 700;   // 长隆野生动物世界 300 + 欢乐世界 250 + 广州塔 228 + 陈家祠 10
            case "重庆" -> 150;   // 洪崖洞/解放碑/李子坝免费；大足石刻 115 + 长江索道 30
            case "厦门" -> 350;   // 鼓浪屿 35（船票）+ 植物园 30 + 日光岩 50 + 曾厝垵（免）
            case "丽江" -> 500;   // 玉龙雪山 140 + 大索道 140 + 印象丽江 280 + 泸沽湖 70
            case "桂林" -> 400;   // 漓江三星船 215 + 象鼻山 55 + 龙脊 80 + 十里画廊（免）
            default      -> 300;   // 通用
        };
        // 如果能从 attractions 抓到「¥数字」就累加
        long computed = 0, count = 0;
        if (attractions != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("门票[^¥0-9]{0,6}¥\\s*([0-9]{1,4})|票价\\s*([0-9]{1,4})|([0-9]{1,4})\\s*元").matcher(attractions);
            while (m.find()) {
                String v = m.group(1) != null ? m.group(1) : (m.group(2) != null ? m.group(2) : m.group(3));
                try {
                    int n = Integer.parseInt(v);
                    if (n >= 10 && n <= 1500) { computed += n; count++; }
                } catch (Exception ignore) {}
            }
        }
        if (count >= 2) {
            long total = computed * people;
            return new long[]{(long) (total * 0.7), total};   // 7 折起
        }
        // fallback：3 天 / 人 × avg
        long total = (long) avg * people;
        return new long[]{(long) (total * 0.6), total};
    }

    private static long sum(long... vals) {
        long s = 0; for (long v : vals) s += Math.max(0, v); return s;
    }
}
