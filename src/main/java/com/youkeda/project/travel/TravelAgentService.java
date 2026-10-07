package com.youkeda.project.travel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.llm.QwenChatService;
import com.youkeda.project.map.AmapService;
import com.youkeda.project.ride.DidiRideService;
import com.youkeda.project.tool.AgentToolResult;
import com.youkeda.project.tool.ToolChatResult;
import com.youkeda.project.tool.WeatherTool;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 自主规划型旅行 Agent 编排核心（全国范围）。
 *
 * <p>用户只输入一句话最终目标（如「做一份北京三日游」「成都+重庆双城」「杭州5天」），
 * 由 LLM 自主拆解步骤并调用工具，最终输出 {@link ItineraryBuilderTool} 确定性模板
 * 生成的 Markdown 成品手册。
 *
 * <h2>鲁棒性说明</h2>
 * 对 LLM 输出做三层兜底：
 * <ol>
 *   <li>System prompt 强制：第一轮必须调工具、最终必须 build_itinerary 收尾；</li>
 *   <li>{@link QwenChatService} 层：连续重复工具调用检测 + 成品结果缓存；</li>
 *   <li>本服务层：若最终 calledTools 不含 build_itinerary 或 handbook 出现异地串台
 *       （检测到明显不属于目的地城市的关键词），则代码层直接再次调用
 *       {@link ItineraryBuilderTool} 以确定性模板生成手册，零幻觉风险。</li>
 * </ol>
 */
@Service
public class TravelAgentService {

    private static final Logger log = LoggerFactory.getLogger(TravelAgentService.class);

    private static final String SYSTEM_PROMPT = """
            你是覆盖全国的【自主规划型旅行 Agent】。用户给一句最终目标（如「做一份上海三日游完整出行方案」「北京 5 天 亲子游」），
            你必须【自主拆解 6 大任务 → 自主调用对应工具 → 最后用 build_itinerary 汇总 8 章节成品】，不用用户分步提问。

            【你必须调用的工具清单（共 15 个，按 6 大块分组）】
            ① 天气（1 个）
              - query_weather(location, type=daily, days=N)：城市 7 天预报，最低/最高温/下雨/紫外线。

            ② 往返车票（1 个 · 新增 ⭐）
              - search_tickets(from, to, depart_date, return_date, days, people, budget_per_person, prefer)：
                按【高铁二等座 / 普速硬卧 / 经济舱】3 档输出，含推荐档总票价区间。
                调用要求：from=默认北京（若用户未给出发地，传 "北京" 并在 return 标注为示例出发地）、to=目标城市，days=用户给的天数。

            ③ 住宿订酒店（1 个 · 新增 ⭐）
              - book_hotel(city, checkin, checkout, days, budget_per_night, people, prefer)：
                输出【经济型 3 家 / 舒适型 3 家 / 高端型 3 家】酒店，含【酒店名+区位+房型+参考价+总住宿预算合计 ¥X,XXX】。
                默认 budget_per_night=500（舒适档）。

            ④ 景点 + 美食 + 餐厅（3 个）
              - search_attractions(city)：城市景点知识库（官方 5A/4A 列表，带【门票/开放时间/游玩时长】）。
              - search_foods(city)：城市特色菜 + 本地人 TOP 餐厅推荐。
              - search_poi(mode=keyword, keywords="特色餐厅 | 老字号", city)：搜景点周边 3km 内的餐厅（景点排程完顺便调用）。

            ⑤ 每日行程 + 路线图（地图 4 个 + 打车 1 个）
              - geocode(mode=geo, address, city)：景点/高铁站/酒店地址 → 经纬度。静态图 / 路线 / 打车之前必调。
              - plan_route(mode=distance|driving|transit, origin, destination, city)：两点间距离/耗时/详情。
              - static_map(title, points, zoom?, size_w?, size_h?)：生成高德静态 PNG 路线图。points=[{name,lng,lat}]
                ⇒ 至少生成 2 张：「城市总览图」+「Day 1 路线图」，把 Markdown ![](url) 贴进 daily_plan / maps。
              - estimate_ride(mode=estimate|recommend, city, distance_km, products, people_count, scenario)：
                核心路段（机场→酒店、景点A→B、高铁站→景点）至少调 2 次，输出快车/专车价格区间。

            ⑥ 出行注意事项 & 智能行李清单（1 个 · 新增 ⭐）
              - travel_tips(cities, start_date, days, people, weather_summary, traveler_type, scenario)：
                分 10 小节：证件 / 电子 / 衣物（按天数×人数 自动量化）/ 洗漱 / 药品 / 杂项 / 天气穿搭 / 城市专属避坑 /
                         人群专属提醒 / 预约省钱避坑。
                ⇒ 必须在 build_itinerary 之前调用，把返回原文作为 build_itinerary 的 notes 参数。

            【成品生成】
              - build_itinerary(title, cities, days, start_date, people, budget_level, traveler_type,
                                weather, attractions, foods, tickets, hotels, daily_plan, maps,
                                transport_urban, notes, highlight)
                ⇒ 汇总上述所有工具真实返回原文，按 8 大章节生成最终 Markdown 成品。
                ⇒ 只调用一次，且必须是最后一步。永远不手写正文代替 build_itinerary。

            【强制调用链（必须遵守，违者 LLM 打负分）】
            第一轮（并行 3 个）：query_weather + search_attractions + search_foods
            第二轮（并行 4 个）：search_tickets + book_hotel + geocode × N(景点坐标) + static_map(总览图)
            第三轮（编排阶段）：plan_route × M + estimate_ride × 2 + static_map(Day1/2..路线图) + travel_tips
            最终：build_itinerary 一次性收束所有参数。

            【结构硬性要求（最终成品必须能检测到 8 大章节标题）】
            ## 一、方案摘要
            ## 二、行前须知（天气 & 预约总览）
            ## 三、🎫 往返交通方案
            ## 四、🏨 住宿方案
            ## 五、🗺️ 每日详细行程
            ## 六、🍜 美食 & 餐厅推荐
            ## 七、🧳 出行注意事项 & 智能行李清单
            ## 八、💰 总预算汇总 & 省钱攻略

            【规则】
            - 第一轮绝对禁止输出正文，必须先并行调 3 个工具；
            - 最终必须调用 build_itinerary（1 次），且 notes 参数必须来自 travel_tips 的真实返回；
            - search_tickets 必须与 book_hotel 同时调用，不能缺失；
            - 行程 ≥2 天必须至少生成 2 张高德静态图（总览图 + Day1 路线图）；
            - 用户未给出发地时，search_tickets 的 from 传 "北京"，并在后续说明中标注「若更换出发地可重述您的目标」。
            - build_itinerary 返回即最终回复，永远不再加前后缀，不再调工具。
            """;

    // 常见中文数字表（用于识别「三日游」「五天四夜」等表达式）
    private static final Map<String, Integer> CN_NUM = new LinkedHashMap<>();
    static {
        CN_NUM.put("零", 0); CN_NUM.put("一", 1); CN_NUM.put("二", 2); CN_NUM.put("两", 2);
        CN_NUM.put("三", 3); CN_NUM.put("四", 4); CN_NUM.put("五", 5); CN_NUM.put("六", 6);
        CN_NUM.put("七", 7); CN_NUM.put("八", 8); CN_NUM.put("九", 9); CN_NUM.put("十", 10);
    }

    // 识别「北京三日游」「做一份 X 三日 / Y 5 天」这种天数表达
    private static final Pattern DAYS_PATTERN =
            Pattern.compile("(?:(?:玩|旅游|出行|行程|游玩|方案|攻略)?\\s*([0-9零一二两三四五六七八九十]+)\\s*(?:天|日))");

    // 支持的全国知识库城市（与知识库文件实际覆盖一致）
    static final List<String> COVERED_CITIES = List.of(
            "上海", "北京", "杭州", "成都", "西安", "广州", "重庆",
            "厦门", "丽江", "桂林");

    // 异地地标指纹：用来检测 handbook 是否把 A 城市内容写到了 B 城市里。
    // 取强区分度的典型特征词。
    private static final Map<String, List<String>> CITY_FINGERPRINT = Map.of(
            "北京", List.of("故宫", "天安门", "长城", "颐和园", "天坛", "圆明园", "王府井", "南锣鼓巷", "雍和宫", "鸟巢", "八达岭"),
            "上海", List.of("外滩", "东方明珠", "迪士尼", "豫园", "朱家角", "武康路", "陆家嘴", "城隍庙", "弄堂", "石库门"),
            "杭州", List.of("西湖", "灵隐", "龙井", "西溪", "千岛湖", "雷峰塔", "断桥", "河坊街", "宋城"),
            "成都", List.of("熊猫", "宽窄巷子", "锦里", "武侯祠", "春熙路", "都江堰", "青城山", "火锅", "兔头", "玉林", "串串香"),
            "西安", List.of("兵马俑", "大雁塔", "回民街", "钟鼓楼", "西安城墙", "华清宫", "永兴坊", "华山", "biangbiang", "泡馍"),
            "广州", List.of("广州塔", "小蛮腰", "沙面", "陈家祠", "长隆", "白云山", "早茶", "双皮奶", "艇仔粥", "煲仔饭"),
            "重庆", List.of("洪崖洞", "李子坝", "长江索道", "解放碑", "磁器口", "一棵树", "大足石刻", "武隆", "九宫格", "小面", "抄手"),
            "厦门", List.of("鼓浪屿", "沙茶面", "曾厝垵", "环岛路", "土笋冻", "南普陀", "沙坡尾", "姜母鸭", "海蛎煎"),
            "丽江", List.of("玉龙雪山", "丽江古城", "束河", "腊排骨", "黑山羊", "纳西", "泸沽湖"),
            "桂林", List.of("漓江", "阳朔", "龙脊", "米粉", "啤酒鱼", "十八酿", "象山", "恭城油茶")
    );

    private final QwenChatService qwenChatService;
    private final ItineraryBuilderTool itineraryBuilderTool;
    private final AttractionRagTool attractionRagTool;
    private final FoodRagTool foodRagTool;
    private final WeatherTool weatherTool;
    private final TrainTicketTool trainTicketTool;
    private final HotelBookingTool hotelBookingTool;
    private final TravelTipsTool travelTipsTool;
    private final AmapService amapService;
    private final DidiRideService didiRideService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int maxRounds;

    // 结果缓存：相同 goal 24h 内直接返回缓存结果，零 LLM 调用
    private static final Path CACHE_DIR = Path.of("./data/travel-cache");
    private static final long CACHE_TTL_MS = 24 * 60 * 60 * 1000;

    public TravelAgentService(
            QwenChatService qwenChatService,
            ItineraryBuilderTool itineraryBuilderTool,
            AttractionRagTool attractionRagTool,
            FoodRagTool foodRagTool,
            WeatherTool weatherTool,
            TrainTicketTool trainTicketTool,
            HotelBookingTool hotelBookingTool,
            TravelTipsTool travelTipsTool,
            AmapService amapService,
            DidiRideService didiRideService,
            @Value("${travel.agent-max-rounds:12}") int maxRounds) {
        this.qwenChatService = qwenChatService;
        this.itineraryBuilderTool = itineraryBuilderTool;
        this.attractionRagTool = attractionRagTool;
        this.foodRagTool = foodRagTool;
        this.weatherTool = weatherTool;
        this.trainTicketTool = trainTicketTool;
        this.hotelBookingTool = hotelBookingTool;
        this.travelTipsTool = travelTipsTool;
        this.amapService = amapService;
        this.didiRideService = didiRideService;
        this.maxRounds = Math.max(10, maxRounds);
    }

    /** 规划入口：一句话目标 → 完整出行手册。 */
    public TravelPlanResult plan(String goal) {
        if (goal == null || goal.isBlank()) {
            throw new IllegalArgumentException("goal is required");
        }
        String trimmedGoal = goal.trim();

        // 1) 结果缓存：相同目标 24h 内直接返回
        String cacheKey = sha256(trimmedGoal);
        Path cacheFile = CACHE_DIR.resolve(cacheKey + ".json");
        TravelPlanResult cached = loadCache(cacheFile);
        if (cached != null) {
            log.info("命中结果缓存，跳过 LLM 调用：{}", trimmedGoal);
            return cached;
        }

        log.info("TravelAgent 开始自主规划：{}", trimmedGoal);
        ToolChatResult result;
        try {
            result = qwenChatService.chatWithTools(SYSTEM_PROMPT, null, trimmedGoal, maxRounds);
        } catch (Exception e) {
            // LLM 调用出错（超时、网络错误、Key 失效等），不抛到上层——直接走确定性 fallbackBuild，
            // 让用户即使 LLM 不可用也能拿到 8 章节完整方案。
            log.warn("LLM 调用失败（{}），切换确定性模板 fallbackBuild 直接产出方案",
                    e.getClass().getSimpleName() + ": " + e.getMessage());
            List<String> goalCities = extractCities(trimmedGoal);
            int days = extractDays(trimmedGoal);
            AgentToolResult built = fallbackBuild(trimmedGoal, goalCities, days);
            String handbook = built.content() == null ? "" : built.content();
            List<String> calledTools = new ArrayList<>();
            calledTools.add("build_itinerary*"); // 标记为代码兜底调用
            log.info("TravelAgent 兜底规划完成（LLM 不可用），最终 calledTools = {}", calledTools);
            TravelPlanResult planResult = new TravelPlanResult(handbook, calledTools);
            saveCache(cacheFile, trimmedGoal, planResult);
            return planResult;
        }
        String handbook = result == null ? "" : result.content();
        List<String> calledTools = result == null ? List.of() : new ArrayList<>(result.calledTools());

        // ========== 代码层强兜底 ==========
        // 触发条件：
        //   1) 未调用 build_itinerary
        //   2) 异地串台（如重庆请求出现故宫/天安门）
        //   3) 空壳模板（LLM传空JSON，导致 ItineraryBuilderTool 全用占位符）
        List<String> goalCities = extractCities(trimmedGoal);
        int days = extractDays(trimmedGoal);
        boolean noBuilder = !calledTools.contains("build_itinerary");
        boolean crossTalk = isCrossTalk(handbook, goalCities);
        boolean emptyTemplate = isEmptyTemplate(handbook, goalCities);
        if (noBuilder || crossTalk || emptyTemplate) {
            log.warn(
                    "LLM 未按规范输出（noBuilder={}, crossTalk={}, emptyTemplate={}），切换确定性模板兜底",
                    noBuilder, crossTalk, emptyTemplate);
            AgentToolResult built = fallbackBuild(trimmedGoal, goalCities, days);
            handbook = built.content() == null ? "" : built.content();
            if (noBuilder) {
                calledTools.add("build_itinerary*"); // * 号标记为代码兜底调用
            } else {
                // 原 LLM 的 build_itinerary 调了但产出不对，替换标记
                calledTools.remove("build_itinerary");
                calledTools.add("build_itinerary*");
            }
        }

        log.info("TravelAgent 规划完成，调用工具链：{}", calledTools);
        TravelPlanResult planResult = new TravelPlanResult(handbook, calledTools);
        // 2) 保存结果缓存
        saveCache(cacheFile, trimmedGoal, planResult);
        return planResult;
    }

    /**
     * 确定性兜底：直接调用 AttractionRagTool / FoodRagTool / WeatherTool /
     * TrainTicketTool / HotelBookingTool / TravelTipsTool / 高德 + 滴滴 + 静态地图
     * 获取真实城市数据，再由新版 ItineraryBuilderTool（17 参数 8 章节结构）生成成品手册。
     * 完全不依赖 LLM 草稿，零幻觉风险。
     */
    private AgentToolResult fallbackBuild(String goal, List<String> cities, int days) {
        String primaryCity = cities.isEmpty() ? "上海" : cities.get(0);
        List<String> workCities = cities.isEmpty() ? List.of(primaryCity) : cities;
        String title = String.join("+", workCities)
                + (days <= 0 ? "" : days + "日")
                + "完整出行方案";
        int actualDays = Math.max(1, days);
        int nights = Math.max(1, actualDays - 1);
        int people = 2; // 默认亲子 2 人
        String budgetLevel = "comfort";
        String travelerType = "family";
        LocalDate startDate = LocalDate.now().plusDays(7);
        LocalDate endDate = startDate.plusDays(nights);
        String startStr = startDate.toString();
        String endStr = endDate.toString();

        // 1) 逐个城市调 RAG 工具
        StringBuilder attractionsAll = new StringBuilder();
        StringBuilder foodsAll = new StringBuilder();
        for (String c : workCities) {
            try {
                AgentToolResult ar = attractionRagTool.execute(
                        objectMapper.writeValueAsString(Map.of("city", c)));
                attractionsAll.append("### ").append(c).append(" 景点\n")
                        .append(safeText(ar)).append("\n\n");
            } catch (Exception e) { log.warn("fallback 景点查询失败 city={}", c, e); }
            try {
                AgentToolResult fr = foodRagTool.execute(
                        objectMapper.writeValueAsString(Map.of("city", c)));
                foodsAll.append("### ").append(c).append(" 美食\n")
                        .append(safeText(fr)).append("\n\n");
            } catch (Exception e) { log.warn("fallback 美食查询失败 city={}", c, e); }
        }

        // 2) 天气（主城市 daily forecast）
        String weatherText;
        try {
            AgentToolResult wr = weatherTool.execute(objectMapper.writeValueAsString(Map.of(
                    "location", primaryCity, "type", "daily",
                    "days", Math.min(7, Math.max(3, actualDays)))));
            weatherText = safeText(wr);
        } catch (Exception e) {
            log.warn("fallback 天气查询失败 city={}", primaryCity, e);
            weatherText = "天气工具暂不可用，出行前请查当地天气预报。";
        }

        // 3) ⭐ 往返车票：from 优先从 goal 中抽取「从 X 出发」/「X→」，否则默认北京
        String fromCity = extractDepartureCity(goal, workCities);
        String ticketsText;
        try {
            AgentToolResult tr = trainTicketTool.execute(objectMapper.writeValueAsString(Map.of(
                    "from", fromCity, "to", primaryCity,
                    "depart_date", startStr, "return_date", endStr,
                    "days", actualDays, "people", people,
                    "budget_per_person", 1500, "prefer", "auto")));
            ticketsText = safeText(tr);
            if (!"北京".equals(fromCity) || goal.contains("北京")) {
                // 正常
            } else {
                ticketsText = "💡 **提示**：你未指定出发地，以下车票方案以「北京 → " + primaryCity
                        + "」为示例。如需替换为你所在的城市，请重新提问「从【XX】出发做一份"
                        + primaryCity + actualDays + "日游方案」。\n\n" + ticketsText;
            }
        } catch (Exception e) {
            log.warn("fallback 车票查询失败 from={} to={}", fromCity, primaryCity, e);
            ticketsText = "⚠️ 车票工具暂不可用，建议出发前 15 天 12306 查 " + fromCity + " → " + primaryCity + " 高铁 / 机票。";
        }

        // 4) ⭐ 酒店方案
        String hotelsText;
        try {
            AgentToolResult hr = hotelBookingTool.execute(objectMapper.writeValueAsString(Map.of(
                    "city", primaryCity, "checkin", startStr, "checkout", endStr,
                    "days", actualDays, "budget_per_night", 500,
                    "people", people, "prefer", travelerType)));
            hotelsText = safeText(hr);
        } catch (Exception e) {
            log.warn("fallback 酒店查询失败 city={}", primaryCity, e);
            hotelsText = "⚠️ 酒店工具暂不可用，建议携程/美团 搜索 " + primaryCity + " 人民广场/核心商圈 500元/晚。";
        }

        // 5) ⭐ 出行贴士（智能行李清单）
        String notesText;
        try {
            AgentToolResult nr = travelTipsTool.execute(objectMapper.writeValueAsString(Map.of(
                    "cities", workCities,
                    "start_date", startStr,
                    "days", actualDays,
                    "people", people,
                    "weather_summary", weatherText,
                    "traveler_type", travelerType,
                    "scenario", "city")));
            notesText = safeText(nr);
        } catch (Exception e) {
            log.warn("fallback travel_tips 失败", e);
            notesText = "⚠️ 贴士工具暂不可用，通用清单：身份证+充电宝≤100Wh + 晴雨伞 + 洗漱分装 + 常用药品。";
        }

        // 6) 高德 + 滴滴增强：城内交通实测（机场/高铁站 → 市中心酒店区 距离 / 打车价格 / 周边POI）
        String urbanText = buildAmapBoost(workCities);

        // 7) 静态地图（城市总览图 + Day1..N 路线图，最多 3 天 × 3 张）
        String mapsText = buildStaticMaps(workCities, actualDays, goal);

        // 8) 核心亮点（一句话）
        String highlight = buildHighlight(workCities, actualDays, fromCity);

        // 9) 组装 build_itinerary 的 17 个参数（除了 daily_plan 传空串由 itinerary 兜底 autoDailyPlan）
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("title", title);
        args.put("cities", workCities);
        args.put("days", actualDays);
        args.put("start_date", startStr);
        args.put("people", people);
        args.put("budget_level", budgetLevel);
        args.put("traveler_type", travelerType);
        args.put("weather", weatherText);
        args.put("attractions", attractionsAll.toString().isBlank() ? "景点信息待补充" : attractionsAll.toString().trim());
        args.put("foods", foodsAll.toString().isBlank() ? "美食信息待补充" : foodsAll.toString().trim());
        args.put("tickets", ticketsText);
        args.put("hotels", hotelsText);
        args.put("daily_plan", "");                // 传空，由 itineraryBuilderTool 自动生成 autoDailyPlan
        args.put("maps", mapsText);
        args.put("transport_urban", urbanText);
        args.put("notes", notesText);
        args.put("highlight", highlight);
        try {
            return itineraryBuilderTool.execute(objectMapper.writeValueAsString(args));
        } catch (Exception e) {
            log.warn("fallbackBuild 新版 itinerary 参数序列化失败，退化为手写摘要返回", e);
            return AgentToolResult.text("# " + title + "\n\n"
                    + "天气：\n" + weatherText + "\n\n"
                    + "景点：\n" + attractionsAll + "\n"
                    + "美食：\n" + foodsAll + "\n"
                    + "车票：\n" + ticketsText + "\n"
                    + "酒店：\n" + hotelsText + "\n"
                    + "地图：\n" + mapsText + "\n"
                    + "注意事项：\n" + notesText);
        }
    }

    /** 从 goal 里抽取出发城市（若找不到返回「北京」作为默认示例出发地）。 */
    private String extractDepartureCity(String goal, List<String> destCities) {
        if (goal == null || goal.isBlank()) return "北京";
        // 1) 「从 X 出发」
        java.util.regex.Matcher m1 = Pattern.compile("从\\s*([\\u4e00-\\u9fa5]{2,6}?)(?:\\s*出发|去|到|玩)").matcher(goal);
        if (m1.find()) {
            String r = cleanCity(m1.group(1));
            if (!r.isBlank() && !destCities.contains(r)) return r;
        }
        // 2) 「X → YY」「X-YY」「X 到 YY」
        java.util.regex.Matcher m2 = Pattern.compile(
                "([\\u4e00-\\u9fa5]{2,6}?)\\s*(?:→|->|--?|到|飞|去)\\s*(" + String.join("|", COVERED_CITIES) + ")")
                .matcher(goal);
        if (m2.find()) {
            String r = cleanCity(m2.group(1));
            if (!r.isBlank() && !destCities.contains(r)) return r;
        }
        return "北京";
    }

    private static String cleanCity(String s) {
        if (s == null) return "";
        return s.trim().replace("市", "").replace("站", "").replace("机场", "");
    }

    /** 一句话核心亮点。 */
    private static String buildHighlight(List<String> cities, int days, String fromCity) {
        String joined = String.join("、", cities);
        if (cities.size() == 1) {
            String c = cities.get(0);
            return switch (c) {
                case "上海" -> String.format(
                        "上海 %d 日精华：迪士尼 1 整天 + 外滩夜景 + 武康路citywalk + 本帮菜/小笼包，不走回头路。", days);
                case "北京" -> String.format(
                        "北京 %d 日深度：故宫/长城/天坛 3 大世界遗产 + 什刹海夜生活 + 烤鸭/炸酱面本地人攻略。", days);
                case "成都" -> String.format(
                        "成都 %d 日巴适：熊猫基地（早 8 点前入园！）+ 都江堰青城山 + 玉林/建设路小吃吃遍。", days);
                case "杭州" -> String.format(
                        "杭州 %d 日诗意：西湖步行一圈 + 灵隐寺禅意 + 龙井村喝茶 + 宋城千古情。", days);
                case "西安" -> String.format(
                        "西安 %d 日穿越：兵马俑深度 3 小时 + 大雁塔 + 大唐不夜城不倒翁演出 + 回民街小众巷。", days);
                case "重庆" -> String.format(
                        "重庆 %d 日 3D 魔幻：洪崖洞夜景 + 李子坝穿楼 + 长江索道 + 九宫格老火锅。", days);
                default    -> String.format(
                        "%s %d 日精华：经典地标 + 美食巷弄 + 本地人生活感体验（含高德路线图 + 智能行李清单）。", joined, days);
            };
        }
        return String.format("%s 双城 %d 日：交通顺序已按最少折返编排，跨城通勤预留半天。", joined, days);
    }

    private static String safeText(AgentToolResult r) {
        if (r == null || r.content() == null) return "";
        return r.content().trim();
    }

    /** 交通住宿提示——通用模板但结合城市名称，避免空占位。 */
    private static String buildTransportHint(List<String> cities, int days) {
        String joined = String.join(" / ", cities);
        StringBuilder sb = new StringBuilder();
        sb.append("### 往返交通\n");
        sb.append("- 出发地 → ").append(joined).append("：优先选高铁直达（建议用 12306 / 飞猪 查询），");
        sb.append("若无高铁可关注特价机票。\n");
        sb.append("- 城内交通：地铁APP（Metro Man / 所在城市地铁官方APP）+ 打车（滴滴 / 高德），");
        sb.append("老城区景点建议步行 + 共享单车结合。\n");
        if (cities.size() > 1) {
            sb.append("- 城际：").append(String.join(" → ", cities)).append(" 间推荐高铁或城际大巴，预留至少半天通勤。\n");
        }
        sb.append("\n### 住宿建议\n");
        sb.append("- 建议住在主城区核心商圈/地铁枢纽附近（").append(joined).append(" 热门景点集中区）。\n");
        sb.append("- 入住 ").append(Math.max(1, days - 1)).append(" 晚：提前在 携程 / 美团 / Booking 比价订房，周末/节假日至少提前 2 周。\n");
        return sb.toString();
    }

    /** 通用出行注意事项 + 结合城市指纹给城市特有提示。 */
    private static String buildNotes(List<String> cities) {
        StringBuilder sb = new StringBuilder();
        sb.append("- 证件：身份证必带，学生证/军人证享门票优惠。\n");
        sb.append("- 预约：热门景点（故宫/兵马俑/熊猫基地/迪士尼/鼓浪屿等）均需提前官网或小程序实名预约。\n");
        sb.append("- 财物：随身小包放贵重物品，人多处防扒，酒店行李锁好。\n");
        sb.append("- 电子：充电宝 ≤100Wh、下载离线地图、开通异地流量或买当地电话卡。\n");
        // 城市特有提示
        for (String c : cities) {
            List<String> fp = CITY_FINGERPRINT.get(c);
            if (fp == null) continue;
            if (c.equals("重庆")) {
                sb.append("- ").append(c).append("：山城地形起伏大，穿舒适徒步鞋；夏季高温高湿注意防暑；火锅配凉水解辣。\n");
            } else if (c.equals("成都")) {
                sb.append("- ").append(c).append("：熊猫基地早上 8 点前去看熊猫最活跃；火锅/串串建议选老小区口碑店。\n");
            } else if (c.equals("西安")) {
                sb.append("- ").append(c).append("：兵马俑景区较大，预留半天；回民街主街贵，往巷子里走性价比更高。\n");
            } else if (c.equals("北京")) {
                sb.append("- ").append(c).append("：故宫周一闭馆！升旗仪式时间随季节变化，提前查官网。\n");
            } else if (c.equals("杭州")) {
                sb.append("- ").append(c).append("：西湖景区免门票但周边景点单独收费；梅雨季带好雨具。\n");
            } else if (c.equals("厦门")) {
                sb.append("- ").append(c).append("：鼓浪屿船票需提前在「厦门轮渡」小程序购买，夏日注意防晒。\n");
            } else if (c.equals("丽江")) {
                sb.append("- ").append(c).append("：高原紫外线强，防晒霜SPF50+；玉龙雪山提前租氧气瓶+羽绒服。\n");
            } else if (c.equals("桂林")) {
                sb.append("- ").append(c).append("：漓江竹筏夏季丰水期体验好；阳朔租电动车骑行十里画廊更自由。\n");
            } else if (c.equals("广州")) {
                sb.append("- ").append(c).append("：早茶热门店需排号，建议 7-8 点到；长隆度假区至少预留一天。\n");
            } else if (c.equals("上海")) {
                sb.append("- ").append(c).append("：迪士尼提前下官方APP抢预约等候卡；外滩夜景推荐 19:00-21:00 灯光全开时段。\n");
            }
        }
        return sb.toString();
    }

    /**
     * 高德地图 + 滴滴打车增强：针对每个城市，
     * 尝试查找机场/高铁站 → 市中心酒店区 或 2 个核心景点之间的路线、打车预估，
     * 以及景点周边 POI。
     */
    private String buildAmapBoost(List<String> cities) {
        if (!amapService.isConfigured()) {
            return "### 🗺️ 地图与打车\n"
                    + "- 未配置高德 API Key，以下功能未启用：精确路线规划、距离测量、POI 周边搜索、滴滴打车预估。\n"
                    + "- 配置方式：在 application.properties 中设置 `amap.api-key=你的高德Web服务Key` 后重启。\n"
                    + "- 手动查询：打开高德地图 APP，搜景点名 → 「路线」→ 选出行方式。\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("### 🗺️ 城内交通实测（高德+滴滴）\n");
        try {
            for (String c : cities) {
                // 1) 机场/高铁站 → 市中心酒店区
                String[] anchors = CITY_ANCHORS.get(c);
                if (anchors != null && anchors.length >= 2) {
                    try {
                        String fromName = anchors[0];
                        String toName = anchors[1];
                        String fromLoc = amapService.geocode(fromName, c);
                        String toLoc = amapService.geocode(toName, c);
                        if (fromLoc != null && toLoc != null) {
                            String dist = amapService.measureDistance(fromLoc, toLoc);
                            sb.append("\n#### ").append(c).append("：").append(fromName).append(" → ").append(toName).append("\n");
                            sb.append(dist).append("\n");
                            // 解析 km 和 min 做打车预估
                            double km = parseKmFromDistance(dist);
                            int min = parseMinFromDistance(dist);
                            if (km > 0) {
                                String estimate = didiRideService.estimatePrice(c, km, min,
                                        List.of("快车", "舒适专车"), -1, -1);
                                // 只取前几行摘要
                                String[] lines = estimate.split("\n");
                                StringBuilder shortEst = new StringBuilder();
                                int taken = 0;
                                for (String l : lines) {
                                    if (l.startsWith("拼车") || l.startsWith("滴滴轻享")
                                            || l.startsWith("快车") || l.startsWith("舒适专车")
                                            || l.startsWith("【出行策略建议】")) {
                                        if (l.startsWith("【出行策略建议】")) break;
                                    }
                                    if (l.startsWith("拼车") || l.startsWith("快车") || l.startsWith("舒适专车")) {
                                        // 车型行
                                        shortEst.append("  ").append(l).append("\n");
                                        taken++;
                                        if (taken >= 2) break;
                                    }
                                }
                                if (shortEst.length() > 0) {
                                    sb.append("🚕 滴滴打车预估（本地模型）：\n").append(shortEst);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("城市 {} 路线增强失败: {}", c, e.getMessage());
                    }
                }
                // 2) 景点周边 POI 搜索（酒店/餐厅 各 3 条）
                try {
                    String poi = amapService.searchPoiText("酒店 经济型 舒适", c, 3);
                    if (poi != null && !poi.contains("未搜索到结果") && !poi.contains("提示】")) {
                        sb.append("\n#### ").append(c).append(" 住宿参考（高德 POI）\n");
                        String[] lines = poi.split("\n");
                        for (int i = 0; i < Math.min(lines.length, 5); i++) {
                            if (lines[i].startsWith("共找到")) continue;
                            sb.append("  ").append(lines[i]).append("\n");
                        }
                    }
                } catch (Exception e) {
                    log.warn("城市 {} POI增强失败: {}", c, e.getMessage());
                }
            }
            sb.append("\n> 💡 以上价格/距离来自实时地图 API，若使用滴滴真实凭证可获得精确到分的官方预估价。\n");
            return sb.toString().trim();
        } catch (Exception e) {
            log.warn("buildAmapBoost 失败: {}", e.getMessage());
            return "### 🗺️ 地图与打车\n- 地图/打车工具暂不可用，建议使用高德/滴滴APP实时查询。\n";
        }
    }

    /** 城市锚点：[0]=机场或高铁站，[1]=市中心或核心景区，用于兜底示例路线。 */
    private static final Map<String, String[]> CITY_ANCHORS = Map.ofEntries(
            Map.entry("北京", new String[]{"北京首都国际机场", "天安门广场"}),
            Map.entry("上海", new String[]{"上海虹桥站", "上海外滩观景平台"}),
            Map.entry("杭州", new String[]{"杭州东站", "西湖断桥"}),
            Map.entry("成都", new String[]{"成都双流国际机场", "成都宽窄巷子"}),
            Map.entry("西安", new String[]{"西安北站", "西安钟楼"}),
            Map.entry("广州", new String[]{"广州白云国际机场", "广州塔"}),
            Map.entry("重庆", new String[]{"重庆江北国际机场", "重庆解放碑"}),
            Map.entry("厦门", new String[]{"厦门高崎国际机场", "厦门鼓浪屿"}),
            Map.entry("丽江", new String[]{"丽江三义机场", "丽江古城四方街"}),
            Map.entry("桂林", new String[]{"桂林两江国际机场", "桂林象鼻山"})
    );

    /**
     * 兜底代码路线：用高德 staticMap() 生成「城市总览图」+ 每日路线图。
     * <p>策略（与 buildAmapBoost 一致，确保不重复 geocode 请求）：
     * <ul>
     *   <li>对每个城市：先 geocode 城市锚点(A/B) + 景点 RAG 解析出的 2-3 个 Top 景点名 → 坐标；</li>
     *   <li>把这些坐标按顺序组成 points，调用 staticMap(title, points, 0=auto, 900*560)；</li>
     *   <li>总览图（City Overview）包含 A、B + 热门景点；Day 图简单模拟（均匀分配坐标）。</li>
     * </ul>
     *
     * @return Markdown 格式的图片插入段落，可直接 append 到 notes 顶部；API 未配置/失败返回空串。
     */
    private String buildStaticMaps(List<String> cities, int days, String goal) {
        if (!amapService.isConfigured()) return "";
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("### 🗺️ 地图路线参考\n");
            sb.append("> 由高德地图 Web 服务 API 自动生成，标注颜色：").append("🟩起点").append(" / ").append("🟥终点").append(" / ").append("🟦途经\n\n");

            java.util.Set<String> geocoded = java.util.concurrent.ConcurrentHashMap.newKeySet();
            java.util.Map<String, AmapService.Point> geoCache = new java.util.concurrent.ConcurrentHashMap<>();

            for (String city : cities) {
                // A) 城市锚点 + RAG 热门景点
                java.util.List<String> names = new java.util.ArrayList<>();
                String[] anchors = CITY_ANCHORS.get(city);
                if (anchors != null) {
                    names.add(anchors[0]);            // 机场/高铁站（起点语义）
                    names.add(anchors[1]);            // 市中心/核心景区
                }
                // 从 attractionsRagText 里尝试解析 2-3 个景点名（粗规则，不必精准）
                List<String> rag = extractRagTop(city);
                names.addAll(rag.stream().limit(3).toList());
                if (names.size() < 2) names.addAll(List.of(city + "市中心", city + "博物馆"));

                // B) 并行 geocode（去重）
                java.util.List<AmapService.Point> pts = new java.util.ArrayList<>();
                for (String nm : names) {
                    AmapService.Point cached = geoCache.get(nm);
                    if (cached != null) { pts.add(cached); continue; }
                    if (!geocoded.add(nm)) continue;
                    String loc = amapService.geocode(nm, city);
                    if (loc == null) continue;
                    String[] ll = loc.split(",");
                    if (ll.length != 2) continue;
                    try {
                        AmapService.Point p = new AmapService.Point(
                                nm.length() > 10 ? nm.substring(0, 10) : nm,
                                Double.parseDouble(ll[0]), Double.parseDouble(ll[1]));
                        geoCache.put(nm, p);
                        pts.add(p);
                    } catch (NumberFormatException ignored) {}
                }
                if (pts.size() < 1) continue;

                // C) 生成「城市总览图」
                AmapService.StaticMapResult r = amapService.staticMap(city + "景点/交通总览", pts, 0, 900, 560);
                if (r.ok()) {
                    sb.append(String.format("- **%s 总览**：%d 个点位\n", city, pts.size()));
                    sb.append(String.format("  ![%s总览图](%s)\n", city, r.previewUrl()));
                }

                // D) 生成每日路线图（简单模拟：按天数等分点位；只有 1 天就生成 Day1=总览子集；为省API配额这里最多生成 3 张）
                int maxMaps = Math.min(days, 3);
                if (pts.size() >= 2) {
                    for (int d = 1; d <= maxMaps; d++) {
                        // 第 1 天用最前 3 个点；第 2 天用中间；第 3 天用最后 3 个
                        int start = (d - 1) * Math.max(1, Math.min(3, (pts.size() / Math.max(1, maxMaps))));
                        int end = Math.min(pts.size(), start + Math.max(2, pts.size() / maxMaps + 1));
                        if (end - start < 2) { start = Math.max(0, pts.size() - 3); end = pts.size(); }
                        if (end - start < 2) break;
                        List<AmapService.Point> dayPts = new java.util.ArrayList<>(pts.subList(start, end));
                        AmapService.StaticMapResult rr = amapService.staticMap(
                                city + " Day" + d + " 路线图", dayPts, 0, 900, 560);
                        if (rr.ok()) {
                            sb.append(String.format("- **Day %d：%s**：%s → %s（共 %d 站）\n",
                                    d, city, dayPts.get(0).name(), dayPts.get(dayPts.size()-1).name(), dayPts.size()));
                            sb.append(String.format("  ![Day%d路线](%s)\n", d, rr.previewUrl()));
                        }
                    }
                }
                if (!r.ok()) {
                    // 至少输出一段错误提示（不污染整体手册）
                    sb.append("- **").append(city).append(" 地图暂不可用**：").append(r.error() == null ? "" : r.error()).append("\n");
                }
            }

            sb.append("\n> ℹ️ 图片展示说明：在浏览器中打开完整手册（访问 http://localhost:8080）后，地图图片可直接渲染；").append("微信对话如果不支持图片，点开链接 /api/amap/static/XXX.png 也能查看原图。\n");
            String out = sb.toString().trim();
            return out.length() > 40 ? out : "";
        } catch (Exception e) {
            log.warn("buildStaticMaps 失败: {}", e.getMessage());
            return "";
        }
    }

    /** 从 AttractionRagTool 拿到该城市的热门景点名（作为静态图点位的补充）。 */
    private List<String> extractRagTop(String city) {
        try {
            String json = objectMapper.writeValueAsString(Map.of("city", city));
            String content = safeText(attractionRagTool.execute(json));
            if (content.isBlank()) return List.of();
            java.util.List<String> names = new java.util.ArrayList<>();
            for (String line : content.split("\n")) {
                String l = line.trim();
                // 常见格式：「1. 锦里古街」或「- 都江堰景区」或「📌 1. 青城山-都江堰景区」
                java.util.regex.Matcher m = java.util.regex.Pattern
                        .compile("^(?:\\s*\\d+\\.|\\-|\\p{InEmoticons}*\\s*\\d+[.、])\\s*([^（(【\\s:：,，]{2,25}))").matcher(l);
                if (m.find()) {
                    String n = m.group(1).trim();
                    if (n.length() >= 2 && n.length() <= 20 && !names.contains(n)) names.add(n);
                }
                if (names.size() >= 5) break;
            }
            return names;
        } catch (Exception e) {
            return List.of();
        }
    }

    private static double parseKmFromDistance(String distText) {
        if (distText == null) return 0;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("距离\\s*([0-9]+(?:\\.[0-9]+)?)\\s*公里")
                .matcher(distText);
        if (m.find()) return Double.parseDouble(m.group(1));
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("([0-9]+(?:\\.[0-9]+)?)\\s*公里").matcher(distText);
        if (m2.find()) return Double.parseDouble(m2.group(1));
        return 0;
    }

    private static int parseMinFromDistance(String distText) {
        if (distText == null) return 0;
        // 匹配 "耗时 X 小时 Y 分钟" 或 "耗时 X 分钟"
        java.util.regex.Matcher mh = java.util.regex.Pattern
                .compile("耗时\\s*(?:([0-9]+)\\s*小时)?\\s*(?:([0-9]+)\\s*分钟)?")
                .matcher(distText);
        if (mh.find()) {
            int h = mh.group(1) != null ? Integer.parseInt(mh.group(1)) : 0;
            int m = mh.group(2) != null ? Integer.parseInt(mh.group(2)) : 0;
            return h * 60 + m;
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("([0-9]+)\\s*分钟").matcher(distText);
        if (m2.find()) return Integer.parseInt(m2.group(1));
        return 0;
    }

    /**
     * 空壳模板检测：LLM 调 build_itinerary 时传了非法 JSON，导致所有关键参数使用
     * 工具级 fallback 文本（「XX工具暂未调用」）、或标题依然是 ItineraryBuilderTool
     * 的默认「出行完整方案」且没有包含目标城市。
     * 规则：命中 ≥3 种「XX 工具暂未调用」即判定为空壳。
     */
    private static boolean isEmptyTemplate(String handbook, List<String> goalCities) {
        if (handbook == null || handbook.isBlank()) return true;
        int ph = 0;
        String[] placeholders = new String[] {
                "天气工具暂未调用",
                "景点工具暂未调用",
                "美食工具暂未调用",
                "车票工具暂未调用",
                "酒店工具暂未调用",
                "出行贴士工具暂未调用"
        };
        for (String p : placeholders) {
            if (handbook.contains(p)) ph++;
        }
        boolean genericTitle = handbook.startsWith("# 出行完整方案");
        // 标题里没有任何目标城市也认为是空壳
        boolean titleMissCity = true;
        if (goalCities != null) {
            for (String c : goalCities) {
                // 只看标题行（前 300 字内 = 摘要表）
                String head = handbook.length() < 300 ? handbook : handbook.substring(0, 300);
                if (head.contains(c)) {
                    titleMissCity = false;
                    break;
                }
            }
        }
        // 8 大章节缺任意 2 章也认为是空壳
        long missingChapter = 0;
        String[] eightChapters = new String[]{
                "## 一、方案摘要",
                "## 二、行前须知",
                "## 三、🎫 往返交通方案",
                "## 四、🏨 住宿方案",
                "## 五、🗺️ 每日详细行程",
                "## 六、🍜 美食 & 餐厅推荐",
                "## 七、🧳 出行注意事项",
                "## 八、💰 总预算汇总"
        };
        for (String ch : eightChapters) if (!handbook.contains(ch)) missingChapter++;

        if (ph >= 3 || genericTitle || (ph >= 1 && titleMissCity) || missingChapter >= 2) {
            log.warn("检测到空壳模板：缺省占位符数={}, 默认标题={}, 标题缺城市={}, 8章节缺失数={}",
                    ph, genericTitle, titleMissCity, missingChapter);
            return true;
        }
        return false;
    }

    /**
     * 异地串台检测：目标城市是 C，但 handbook 出现了其他城市的强指纹地标词。
     * 例：用户要重庆，handbook 出现「故宫」「天安门」= 确定串北京。
     */
    private static boolean isCrossTalk(String handbook, List<String> goalCities) {
        if (handbook == null || handbook.isBlank()) return false;
        if (goalCities == null || goalCities.isEmpty()) return false;
        Set<String> othersSeen = new HashSet<>();
        for (Map.Entry<String, List<String>> e : CITY_FINGERPRINT.entrySet()) {
            String city = e.getKey();
            if (goalCities.contains(city)) continue; // 目标城市自己的地标词不敏感
            for (String kw : e.getValue()) {
                if (handbook.contains(kw)) {
                    othersSeen.add(city + ":" + kw);
                }
            }
        }
        // 命中 ≥2 个异地指纹关键词，认为是串台
        if (othersSeen.size() >= 2) {
            log.warn("检测到异地串台指纹：{}", othersSeen);
            return true;
        }
        return false;
    }

    /** 从用户目标中提取候选城市名。 */
    private static List<String> extractCities(String goal) {
        List<String> hits = new ArrayList<>();
        if (goal == null) return hits;
        for (String c : COVERED_CITIES) {
            if (goal.contains(c)) hits.add(c);
        }
        return hits;
    }

    /** 从用户目标中提取天数（默认 3）。 */
    private static int extractDays(String goal) {
        if (goal == null) return 3;
        Matcher m = DAYS_PATTERN.matcher(goal);
        if (m.find()) {
            String tok = m.group(1);
            if (tok != null) {
                try {
                    if (tok.chars().allMatch(Character::isDigit)) {
                        return Integer.parseInt(tok);
                    }
                    return cnToInt(tok);
                } catch (Exception ignore) {
                    // fallthrough
                }
            }
        }
        return 3;
    }

    private static int cnToInt(String s) {
        if (s.length() == 1) return CN_NUM.getOrDefault(s, 3);
        // 处理 "十三"=10+3 等简单组合
        int total = 0;
        int section = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            Integer digit = CN_NUM.get(String.valueOf(c));
            if (digit == null) return 3;
            if (digit == 10) {
                section = section == 0 ? 1 : section;
                total += section * 10;
                section = 0;
            } else {
                section = section * 10 + digit;
            }
        }
        return total + section;
    }

    // ========== 结果缓存辅助方法 ==========

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            return Integer.toHexString(input.hashCode());
        }
    }

    @SuppressWarnings("unchecked")
    private TravelPlanResult loadCache(Path file) {
        if (!Files.exists(file)) return null;
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Map<String, Object> data = objectMapper.readValue(json, Map.class);
            long timestamp = ((Number) data.get("timestamp")).longValue();
            if (System.currentTimeMillis() - timestamp > CACHE_TTL_MS) return null;
            String handbook = (String) data.get("handbook");
            List<String> tools = (List<String>) data.get("calledTools");
            return new TravelPlanResult(handbook, tools == null ? List.of() : new ArrayList<>(tools));
        } catch (Exception e) {
            log.warn("读取结果缓存失败: {}", e.getMessage());
            return null;
        }
    }

    private void saveCache(Path file, String goal, TravelPlanResult result) {
        try {
            Files.createDirectories(file.getParent());
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("goal", goal);
            data.put("handbook", result.handbook());
            data.put("calledTools", result.calledTools());
            data.put("timestamp", System.currentTimeMillis());
            Files.writeString(file, objectMapper.writeValueAsString(data), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("保存结果缓存失败: {}", e.getMessage());
        }
    }

    /** 规划结果：手册正文 + Agent 实际调用的工具链（便于展示自主拆解过程）。 */
    public record TravelPlanResult(String handbook, List<String> calledTools) {}
}
