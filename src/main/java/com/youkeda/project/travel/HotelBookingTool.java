package com.youkeda.project.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.tool.AgentTool;
import com.youkeda.project.tool.AgentToolResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 旅行工具 6：酒店/住宿方案推荐。
 *
 * <p>输入：城市 / 入住日期 / 离店日期 / 间夜预算（元/晚·间）/ 出行人数 /
 *        特殊偏好（亲子 / 商务 / 情侣 / 老人 / 近地铁 / 近景区）。
 *
 * <p>输出：
 *   - 按预算分 3 档：【经济（¥<400）/ 舒适（¥400-800）/ 高端（¥>800）】
 *   - 每档 2-3 家代表性酒店（含【酒店名 + 区位 + 房型 + 参考价 + 推荐理由 + 订房链接】）
 *   - 每档给出「预订建议」：提前多少天订、哪个平台便宜、节假日加价提示
 *
 * <p>价格采用知识库基准价（2024-2025 各城市主流连锁品牌官网均价），
 * 周末 / 节假日按 1.3x ~ 2.0x 系数浮动，仅供参考。
 */
@Component
public class HotelBookingTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * 城市酒店知识库：
     *   KEY = 城市名
     *   VALUE[0] = 「核心商圈/景区」列表（用户不懂分区时默认推荐这些区）
     *   VALUE[1] = 经济型推荐酒店（3 家，分别：名称|区位|双床房基准价|标签+）
     *   VALUE[2] = 舒适型推荐酒店（3 家）
     *   VALUE[3] = 高端型推荐酒店（3 家）
     *
     * 注：酒店价格为「平日双床房不含早餐」参考价（2025 年数据），
     *     周末 / 节假日会乘以动态系数。
     */
    private static final Map<String, HotelDB> HOTEL_DB = Map.ofEntries(
            Map.entry("上海", new HotelDB(
                    List.of("人民广场/南京路", "外滩/豫园", "陆家嘴", "静安寺/南京西路", "徐家汇",
                            "迪士尼周边（川沙）", "武康路/衡山路", "虹桥枢纽"),
                    List.of(// 经济型
                            new H("如家酒店(上海人民广场店)", "人民广场 200m", "双床房 18㎡", 278, "近地铁1/2/8号线、楼下就是南京路步行街、亲子友好"),
                            new H("汉庭酒店(上海外滩南京东路店)", "外滩 800m", "双床房 20㎡", 318, "步行15min到外滩夜景、周边便利店密布"),
                            new H("7天连锁(上海虹桥火车站店)", "虹桥枢纽 1.2km", "双床房 16㎡", 218, "赶早班机/高铁首选、有免费班车送高铁站")),
                    List.of(// 舒适型
                            new H("全季酒店(上海静安寺店)", "静安寺 500m", "高级双床 28㎡", 528, "华住旗下中高端、房间安静、静安寺地铁上盖"),
                            new H("桔子水晶酒店(上海外滩豫园店)", "豫园 400m", "行政双床 32㎡", 628, "设计感强、步行5min到城隍庙/外滩"),
                            new H("亚朵酒店(上海徐家汇漕河泾店)", "徐家汇 1.5km", "几木双床房 30㎡", 498, "商务出差首选、阅读角+自助洗衣+免费早餐")),
                    List.of(// 高端型
                            new H("上海外滩华尔道夫酒店", "外滩 零距离", "豪华江景双床 55㎡", 2680, "百年建筑+江景露台、米其林餐厅、情侣/纪念日首选"),
                            new H("上海和平饭店", "外滩 零距离", "豪华房 50㎡", 1980, "上海地标、爵士吧、老年九国套房网红打卡"),
                            new H("浦东丽思卡尔顿酒店", "陆家嘴 国金中心", "外滩景观房 52㎡", 2480, "俯瞰外滩全景、顶楼Flair酒吧、商务+购物一体"))
            )),
            Map.entry("北京", new HotelDB(
                    List.of("天安门/王府井", "东单/建国门", "三里屯/国贸CBD", "西单", "颐和园/五道口学院路",
                            "鸟巢/水立方(亚运村)", "故宫周边", "首都机场/大兴机场"),
                    List.of(
                            new H("如家酒店(北京前门店)", "前门步行街 200m", "双床房 18㎡", 298, "步行到天安门20min、看升旗首选"),
                            new H("汉庭酒店(北京西单店)", "西单 400m", "双床房 20㎡", 318, "逛街方便、地铁1/4 号线"),
                            new H("7天连锁(首都机场T3店)", "T3 航站楼 5km", "双床房 16㎡", 238, "24h 免费接送机、红眼航班首选")),
                    List.of(
                            new H("全季酒店(北京王府井店)", "王府井 300m", "高级双床 28㎡", 558, "出门就是王府井步行街、近东四美食"),
                            new H("亚朵S酒店(北京三里屯店)", "三里屯 200m", "几木套房 38㎡", 688, "潮人聚集地、夜生活丰富、商务+休闲两用"),
                            new H("桔子水晶酒店(北京国贸CBD店)", "国贸 600m", "行政房 30㎡", 598, "出差首选、临近SKP、交通便利")),
                    List.of(
                            new H("北京王府井文华东方酒店", "王府井 50m", "豪华客房 50㎡", 2980, "文华品牌、出门进故宫、奢华标杆"),
                            new H("北京瑰丽酒店", "国贸 CBD", "豪华客房 52㎡", 2480, "设计师酒店、SenseSPA、纪念日首选"),
                            new H("颐和安缦酒店", "颐和园旁", "庭院客房 65㎡", 5680, "直连颐和园、明清庭院风、顶级避世"))
            )),
            Map.entry("成都", new HotelDB(
                    List.of("春熙路/IFS", "宽窄巷子/人民公园", "锦里/武侯祠", "太古里/大慈寺", "九眼桥/玉林",
                            "都江堰/青城山", "熊猫基地周边", "双流机场/天府机场"),
                    List.of(
                            new H("如家酒店(成都春熙路步行街店)", "春熙路 150m", "双床房 18㎡", 248, "下楼就是IFS大熊猫打卡点、地铁2/3号线"),
                            new H("汉庭酒店(宽窄巷子店)", "宽窄巷子 300m", "双床房 20㎡", 238, "步行逛宽窄巷子、附近老妈蹄花/串串香"),
                            new H("7天连锁(成都天府机场店)", "天府机场 10km", "双床房 16㎡", 198, "24h 接送机、适合早班机")),
                    List.of(
                            new H("全季酒店(成都太古里店)", "太古里 300m", "高级双床 28㎡", 458, "临大慈寺、太古里商圈步行、潮牌集中"),
                            new H("亚朵酒店(成都武侯祠锦里店)", "锦里 500m", "几木双床 32㎡", 428, "出门逛锦里夜景、近武侯祠、有自助洗衣"),
                            new H("桔子水晶酒店(成都玉林店)", "玉林西路 200m", "行政双床 30㎡", 418, "赵雷「成都」打卡地、小酒馆酒吧街")),
                    List.of(
                            new H("成都博舍酒店", "太古里", "豪华客房 55㎡", 2180, "太古里高端、百年建筑改造、The Temple House"),
                            new H("成都钓鱼台精品酒店", "宽窄巷子", "豪华套房 60㎡", 1980, "国宾馆品质、直接在宽窄巷子景区里"),
                            new H("青城山六善酒店", "青城山前山", "双卧泳池别墅 180㎡", 4680, "世界顶级、山林氧吧、家庭度假首选"))
            )),
            Map.entry("杭州", new HotelDB(
                    List.of("西湖(湖滨/断桥)", "灵隐寺/龙井", "河坊街/南宋御街", "武林广场", "西溪湿地",
                            "千岛湖(淳安)", "杭州东站", "萧山机场"),
                    List.of(
                            new H("如家酒店(杭州西湖湖滨店)", "西湖断桥 200m", "双床房 18㎡", 288, "出门就是西湖、音乐喷泉步行10min"),
                            new H("汉庭酒店(杭州武林广场店)", "武林广场 200m", "双床房 20㎡", 258, "市中心交通枢纽、近地铁1/3号线"),
                            new H("7天连锁(杭州东站店)", "杭州东站 800m", "双床房 16㎡", 228, "高铁到达首选、有接驳班车")),
                    List.of(
                            new H("全季酒店(杭州西湖断桥店)", "断桥 400m", "高级双床 28㎡", 468, "步行西湖、早餐好评、房间安静"),
                            new H("亚朵酒店(杭州灵隐寺店)", "灵隐寺 1km", "几木双床 32㎡", 438, "山林幽静、近龙井村、适合慢节奏"),
                            new H("桔子水晶酒店(杭州河坊街店)", "河坊街 200m", "行政双床 30㎡", 448, "逛南宋御街、吃片儿川、方便")),
                    List.of(
                            new H("杭州西湖国宾馆", "西湖 杨公堤", "湖景套房 80㎡", 3280, "西湖核心、园林酒店、毛泽东登船处"),
                            new H("安缦法云", "灵隐寺旁", "村庄客舍 70㎡", 5880, "世界顶级、古村落改造、禅意避世"),
                            new H("杭州柏悦酒店", "钱江新城", "江景豪华房 55㎡", 2080, "俯瞰钱塘江、下午茶网红、商务高端"))
            )),
            Map.entry("西安", new HotelDB(
                    List.of("钟楼/回民街", "大雁塔/曲江", "西安城墙内(永兴坊)", "兵马俑(临潼)",
                            "高铁西安北站", "咸阳机场"),
                    List.of(
                            new H("如家酒店(西安钟楼回民街店)", "钟楼 150m", "双床房 18㎡", 238, "楼下就是回民街、吃羊肉泡馍出门即到"),
                            new H("汉庭酒店(西安大雁塔店)", "大雁塔 500m", "双床房 20㎡", 228, "近大唐不夜城、夜景打卡、地铁3/4号线"),
                            new H("7天连锁(西安北站店)", "西安北站 1km", "双床房 16㎡", 198, "高铁到达首选、免费接送北客站")),
                    List.of(
                            new H("全季酒店(西安钟楼店)", "钟楼 300m", "高级双床 28㎡", 398, "城墙内核心、房间隔音好、回民街步行"),
                            new H("亚朵酒店(西安大唐不夜城店)", "大唐不夜城 300m", "几木双床 30㎡", 418, "夜景网红、出门即不倒翁表演"),
                            new H("桔子水晶酒店(西安永兴坊店)", "永兴坊 100m", "行政双床 28㎡", 408, "摔碗酒+小吃、近城墙东门")),
                    List.of(
                            new H("西安W酒店", "曲江池畔", "城景套房 55㎡", 1780, "潮流地标、亚洲最大W、夜景酒吧"),
                            new H("西安索菲特传奇酒店", "城墙内 省政府", "豪华房 50㎡", 2180, "百年传奇、雅高最高级、国宾待遇"),
                            new H("临潼悦椿温泉酒店", "兵马俑 15km", "温泉亲子套房 75㎡", 1580, "玩完兵马俑直接泡温泉、亲子首选"))
            ))
    );

    // 默认酒店（其他城市 fallback）
    private static final HotelDB DEFAULT_DB = new HotelDB(
            List.of("市中心商圈", "火车站/高铁站", "机场/新城", "主要景点周边"),
            List.of(
                    new H("如家酒店(市中心店)", "市中心 500m", "双床房 18㎡", 228, "连锁品质稳定、近地铁"),
                    new H("汉庭酒店(高铁站店)", "高铁站 1km", "双床房 20㎡", 208, "高铁到达首选"),
                    new H("7天连锁(汽车站店)", "长途汽车站 600m", "双床房 16㎡", 188, "性价比之王")),
            List.of(
                    new H("全季酒店(市中心店)", "市中心 300m", "高级双床 28㎡", 388, "华住中高端、商务+亲子"),
                    new H("亚朵酒店(景点店)", "景点 800m", "几木双床 32㎡", 418, "服务好、有自助洗衣+早餐"),
                    new H("桔子水晶酒店(商业街店)", "商业街 200m", "行政房 30㎡", 458, "设计型酒店、年轻人首选")),
            List.of(
                    new H("本地 5 星·皇冠假日酒店", "市中心地标", "豪华行政房 55㎡", 1180, "IHG 品牌、商务首选"),
                    new H("希尔顿花园酒店", "新城 CBD", "景观套房 52㎡", 980, "国际连锁、服务标准"),
                    new H("万豪系·福朋喜来登", "高铁站 2km", "尊贵双床房 50㎡", 888, "适合商务差旅"))
    );

    @Override
    public String name() {
        return "book_hotel";
    }

    @Override
    public String description() {
        return "根据城市、入住日期、离店日期、预算（每晚每间）、人数、特殊偏好，输出【经济型/舒适型/高端型】"
                + "各 2-3 家酒店推荐（含酒店名、区位、房型、参考价区间、推荐理由、预订建议）。并统计总间夜 × 预算汇总出总住宿费。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("city", Map.of("type", "string",
                "description", "城市名，例如 上海、成都、北京"));
        props.put("checkin", Map.of("type", "string",
                "description", "入住日期 yyyy-MM-dd，未填则默认 7 天后。"));
        props.put("checkout", Map.of("type", "string",
                "description", "离店日期 yyyy-MM-dd，未填则按 nights 或 days 推算。"));
        props.put("days", Map.of("type", "integer",
                "description", "游玩天数（默认 3），一般住 days-1 晚；若给了 checkout 则以 checkout 为准。"));
        props.put("budget_per_night", Map.of("type", "integer", "minimum", 100,
                "description", "每晚每间预算上限（元）。默认 500；<300=经济型、300-800=舒适、>800=高端。"));
        props.put("people", Map.of("type", "integer", "minimum", 1, "maximum", 20,
                "description", "总人数，默认 2。按「每 2 人一间房」计算需要订几间。"));
        props.put("rooms", Map.of("type", "integer", "minimum", 1, "maximum", 10,
                "description", "房间数。不给则按 ceil(people/2) 自动推算。"));
        props.put("prefer", Map.of("type", "string",
                "enum", new String[]{"auto", "family", "couple", "business", "senior", "near_subway", "near_scene"},
                "description", "偏好：auto=综合、family=亲子（要儿童设施/近乐园）、couple=情侣浪漫、business=商务、"
                        + "senior=老人出行、near_subway=地铁优先、near_scene=景区优先。默认 auto。"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("city"));
        return params;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = MAPPER.readTree(argumentsJson);
            String city = args.path("city").asText("").trim();
            if (city.isBlank()) return AgentToolResult.text("❌ 参数错误：必须给 city（城市名）。");
            String ciRaw = args.path("checkin").asText("");
            String coRaw = args.path("checkout").asText("");
            int days = Math.max(1, args.path("days").asInt(3));
            int budget = Math.max(100, args.path("budget_per_night").asInt(500));
            int people = Math.max(1, Math.min(20, args.path("people").asInt(2)));
            int rooms = Math.max(1, args.path("rooms").asInt((int) Math.ceil(people / 2.0)));
            String prefer = args.path("prefer").asText("auto");

            LocalDate today = LocalDate.now();
            LocalDate ci = parseDate(ciRaw, today.plusDays(7));
            LocalDate co = parseDate(coRaw, ci.plusDays(Math.max(1, days - 1)));
            if (!co.isAfter(ci)) co = ci.plusDays(Math.max(1, days - 1));
            int nights = (int) ChronoUnit.DAYS.between(ci, co);

            HotelDB db = HOTEL_DB.getOrDefault(city, DEFAULT_DB);
            double peak = peakMultiplier(ci, co);
            boolean holiday = peak > 1.05;

            String pLabel = preferLabel(prefer);

            StringBuilder sb = new StringBuilder();
            sb.append("### 🏨 住宿方案（").append(city).append("）\n")
              .append("- 入住：").append(ci).append(" ｜ 离店：").append(co).append("｜共 ").append(nights).append(" 晚\n")
              .append("- 人数：").append(people).append(" 人 · 房间数：").append(rooms).append(" 间 · 偏好：").append(pLabel).append("\n")
              .append("- 预算：¥").append(budget).append("/晚/间 · ").append(holiday ? "⚠️ 节假日/周末加价系数 " + String.format("×%.1f", peak) : "平日价")
              .append("\n- 推荐入住区域：").append(String.join("、", db.areas)).append("\n\n");

            // 三档输出
            sb.append("#### 档 1 · 💸 经济型（每间晚 ¥200-400）\n");
            sb.append(renderHotels(db.economy, peak, 200, 400, prefer));

            sb.append("#### 档 2 · 🌿 舒适型（每间晚 ¥400-800）\n");
            sb.append(renderHotels(db.comfort, peak, 400, 800, prefer));

            sb.append("#### 档 3 · ✨ 高端型（每间晚 ¥800+）\n");
            sb.append(renderHotels(db.luxury, peak, 800, 99999, prefer));

            // 总费用汇总
            int match = Math.min(3, budgetTier(budget));  // 1 2 3
            List<H> tier = match == 1 ? db.economy : (match == 2 ? db.comfort : db.luxury);
            int avgPerNight = avgPrice(tier, peak, 0, 999999);
            long total = (long) avgPerNight * nights * rooms;
            sb.append("#### 💰 总住宿预算（").append(nights).append("晚 × ").append(rooms).append("间）\n");
            sb.append(String.format("- 【推荐档】每间每晚参考：¥%,d → 合计约：¥%,d\n", avgPerNight, total));
            sb.append(String.format("- 【升级舒适型 +¥%,d】｜【升高端 +¥%,d】\n",
                    (long) avgPrice(db.comfort, peak, 400, 999999) * nights * rooms - total,
                    Math.max(0, (long) avgPrice(db.luxury, peak, 800, 999999) * nights * rooms - total)));
            sb.append("- 🛒 预订建议：").append(holiday
                    ? "节假日/周末建议提前 30 天在「携程/美团/Booking」比价；同一家酒店飞猪会员价常有 9 折。"
                    : "平日提前 7-10 天订即可；华住/锦江会员直接官网订最划算，含早 + 可取消。")
              .append("行程不确定建议选「免费取消」价格（一般高 10-30 元/晚，值）。\n");
            return AgentToolResult.text(sb.toString());
        } catch (Exception e) {
            return AgentToolResult.text("❌ book_hotel 执行失败：" + e.getMessage());
        }
    }

    // ============ 内部 ============

    private static LocalDate parseDate(String s, LocalDate fallback) {
        if (s == null || s.isBlank()) return fallback;
        try { return LocalDate.parse(s, DateTimeFormatter.ISO_LOCAL_DATE); }
        catch (Exception ignore) { return fallback; }
    }

    private static String preferLabel(String p) {
        return switch (p == null ? "auto" : p) {
            case "family"    -> "亲子出行（要儿童设施/双床房）";
            case "couple"    -> "情侣/浪漫（高楼层/江景/圆床）";
            case "business"  -> "商务（行政酒廊/会议室/打印）";
            case "senior"    -> "老人出行（低楼层/含早/安静）";
            case "near_subway" -> "近地铁/枢纽（少走路）";
            case "near_scene"  -> "近景点（步行可达）";
            default          -> "综合推荐";
        };
    }

    private static int budgetTier(int budget) {
        if (budget < 350) return 1;
        if (budget < 850) return 2;
        return 3;
    }

    private static double peakMultiplier(LocalDate ci, LocalDate co) {
        double m = 1.0;
        LocalDate cur = ci;
        int days = 0;
        while (!cur.isAfter(co)) {
            days++;
            int w = cur.getDayOfWeek().getValue();
            if (w == 6 || w == 7) m += 0.30;   // 周末 +30%
            else m += 0.0;
            // 粗略节假日
            int mo = cur.getMonthValue(); int d = cur.getDayOfMonth();
            if ((mo == 1 && d <= 3) || (mo == 10 && d <= 7) || (mo == 5 && d >= 1 && d <= 5)
                    || (mo == 2 && d >= 8 && d <= 22)) m += 0.70; // 节假日再 +70%
            cur = cur.plusDays(1);
        }
        return days == 0 ? 1.0 : Math.min(2.0, m / days);
    }

    private static String renderHotels(List<H> list, double peak, int lo, int hi, String prefer) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (H h : list) {
            int price = (int) Math.round(h.basePrice * peak);
            if (price < lo * 0.85 || price > hi * 1.5) continue;  // 允许轻微溢出
            String tag = tagOf(h.tag, prefer);
            sb.append(String.format("🏩 **%s**\n", h.name))
              .append("   - 📍 区位：").append(h.area).append("｜房型：").append(h.roomType).append("\n")
              .append("   - 💰 参考价：¥").append(price).append("/晚 · 涨幅系数：×").append(String.format("%.1f", peak)).append("\n")
              .append("   - ✨ 亮点：").append(h.tag).append(tag).append("\n")
              .append("   - 🛒 订房：携程 / 美团 / 飞猪 / 品牌官网比价（建议选「免费取消」）\n\n");
            count++;
            if (count >= 3) break;
        }
        if (count == 0) {
            sb.append("   （当前预算档位在").append(lo).append("~").append(hi)
              .append("暂无匹配，参考列表中任意一家并联系酒店前台加价。）\n\n");
        }
        return sb.toString();
    }

    private static String tagOf(String baseTag, String prefer) {
        if (prefer == null || "auto".equals(prefer)) return "";
        return switch (prefer) {
            case "family"    -> "；✅ 建议加儿童床 + 选择含早价";
            case "couple"    -> "；✅ 建议备注高楼层/江景房/纪念日布置";
            case "business"  -> "；✅ 建议选行政房/可开专票";
            case "senior"    -> "；✅ 建议选 1-3 楼 + 含早餐";
            case "near_subway" -> "；✅ 请在订房前搜「最近地铁口」距离";
            case "near_scene"  -> "；✅ 尽量选步行 1km 以内店";
            default          -> "";
        };
    }

    private static int avgPrice(List<H> list, double peak, int lo, int hi) {
        int sum = 0, n = 0;
        for (H h : list) {
            int p = (int) Math.round(h.basePrice * peak);
            if (p >= lo && p <= hi) { sum += p; n++; }
        }
        if (n == 0) return list.isEmpty() ? 400 : (int) Math.round(list.get(0).basePrice * peak);
        return sum / n;
    }

    // ==================== DB 数据类 ====================

    private record HotelDB(List<String> areas, List<H> economy, List<H> comfort, List<H> luxury) {}
    private record H(String name, String area, String roomType, int basePrice, String tag) {}
}
