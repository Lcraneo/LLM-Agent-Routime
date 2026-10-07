package com.youkeda.project.travel;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youkeda.project.tool.AgentTool;
import com.youkeda.project.tool.AgentToolResult;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 旅行工具 7：出行注意事项 & 智能行李清单生成器。
 *
 * <p>输入：城市 / 日期范围 / 天气文本（来自 query_weather） / 天数 / 人数 /
 *        出行类型（亲子 / 老人 / 情侣 / 商务 / 学生 / 带宠 / 独自出行）/ 特殊场景
 *       （高原 / 海岛 / 雪山 / 城市 / 古镇 / 邮轮 / 温泉 / 滑雪）。
 *
 * <p>输出：
 *   1) 🧳 【基础清单】证件/电子/衣物/洗漱/药品/日用品（已按天数自动生成数量）
 *   2) 🌤️ 【天气着装建议】根据最低/最高温给出穿衣搭配 + 雨具/防晒提醒
 *   3) 🌆 【城市专属注意事项】（例：北京故宫周一闭馆、上海迪士尼需 7 点到、
 *      成都熊猫基地早上 8 点前活跃、重庆山城穿徒步鞋、丽江高原反应…）
 *   4) 👨‍👩‍👧 【人群专属建议】亲子/老人/商务人群的特殊提醒
 *   5) 💡 【避坑 & 省钱 & 预约攻略】热门景点预约链接、官方小程序、黄牛避坑
 */
@Component
public class TravelTipsTool implements AgentTool {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Map<String, String[]> CITY_TIPS = Map.ofEntries(
            Map.entry("北京", new String[]{
                    "故宫/国家博物馆/恭王府：官网或「故宫博物院观众服务」公众号提前 7 天 20:00 放票，秒光，务必设闹钟！",
                    "升旗仪式时间每天不同：出发前在「北京本地宝」或天安门管委会官网查准确时间（冬天约 07:20、夏天约 04:50）。",
                    "长城选线：慕田峪（外国人少、有缆车/滑道，推荐）> 八达岭（人多但高铁20min直达）> 居庸关。",
                    "老北京小吃别去王府井小吃街：推荐牛街（清真）、簋街（夜宵）、护国寺小吃（早点）、方砖厂69号炸酱面。",
                    "雍和宫/国子监：周一闭馆，法物流通处手串要排队 30-60min，想要心海/吉祥款早点去。"}),
            Map.entry("上海", new String[]{
                    "迪士尼：提前下「上海迪士尼度假区」APP，抢「预约等候卡」+「剧场演出预约」，7:30 到园区门口排队。",
                    "迪士尼尊享卡：1000+元/人，不建议旺季买；单人通道比早享卡还快（社恐/单人必备）。",
                    "外滩灯光：5-10 月 19:00-23:00，11-4 月 18:00-22:00，每 30 分钟一次亮灯仪式；最佳机位是外滩观景平台中山东一路 18 号旁。",
                    "上海博物馆/上海天文馆：公众号提前 7 天 00:00 抢号，天文馆秒杀。",
                    "本地美食别去城隍庙小吃区：去铜川路海鲜、云南路小吃街、或者老盛昌/大壶春（生煎）、鲜得来（排骨年糕）。"}),
            Map.entry("成都", new String[]{
                    "熊猫基地：必须早上 7:30 前入园！熊猫中午 11 点后开始睡觉，啥都看不到；新区「冒险溪谷」比老区看到熊猫概率高 2 倍。",
                    "火锅别去宽窄巷子！推荐：玉林片区（成都姑娘小区门口老火锅）、建设路小吃街、魁星楼街、电台巷。",
                    "三星堆：离成都 1.5h，门票 72 元，建议早上 8:30 出发；青铜大立人、黄金面具、青铜神树都在同一展馆，给 3 小时慢慢看。",
                    "都江堰+青城山：一天刚好，建议早上成都东→犀浦→青城山（地铁+城际 40min），先游青城山前山，下午再逛都江堰。"}),
            Map.entry("重庆", new String[]{
                    "洪崖洞亮灯 19:30-23:00，最佳机位不是洪崖洞 11 楼，而是【千厮门大桥人行道】或【大剧院江边】。",
                    "李子坝轻轨观景台：坐 2 号线时【选第一节车厢最前面】，进洞穿出楼的视角超震撼，必拍慢动作。",
                    "长江索道：来回 30 元，上班高峰（7:00-9:00）去坐不用排队；其他时间排队 1-2h 不值得。",
                    "重庆老火锅推荐：晓宇火锅总店、二火锅总店、赵美丽火锅、邓莽子；九宫格不要乱烫——毛肚鸭肠涮中间格。",
                    "导航在重庆经常失灵！遇到岔路口问路人，比高德靠谱 10 倍。"}),
            Map.entry("杭州", new String[]{
                    "西湖免门票，但雷峰塔 40 元、灵隐飞来峰 45+30 元、岳王庙 25 元、宋城 310 元。",
                    "西湖一日经典步行线：断桥→白堤→平湖秋月→曲院风荷→苏堤春晓→花港观鱼→雷峰塔→长桥公园（看夕照最佳）。",
                    "灵隐寺：一定要在公众号「杭州西湖风景名胜区」提前 3 天约；飞来峰单独买门票后，灵隐寺还要另买 30 元香花券。",
                    "杭州美食别去河坊街！推荐：滨江垃圾街、杭帮菜馆「外婆家/绿茶/新白鹿」其实性价比很高但要排号。"}),
            Map.entry("西安", new String[]{
                    "兵马俑：门票 120 元，公众号「秦始皇帝陵博物院」提前 7 天约；一定要请讲解（官方 30/人），不然你看到的只是一堆陶人。",
                    "兵马俑别在火车站外面坐「游5」黑车：在纺织城站坐地铁 9 号线直达兵马俑站，40min。",
                    "回民街主街贵，拐到旁边的大皮院、洒金桥、北广济街吃：马洪小炒泡馍、马二酸汤水饺、定家小酥肉。",
                    "大唐不夜城：19:00 后灯光才全开，不倒翁/盛唐密盒/敦煌飞天要提前 30 分钟去占位置。"}),
            Map.entry("广州", new String[]{
                    "长隆度假区：至少预留一整天，欢乐世界+飞鸟乐园+野生动物园联票更划算；大马戏必须占 A 区 15 排以内 VIP 座。",
                    "早茶热门店 7-8 点到不排号：陶陶居、莲香楼、广州酒家、点都德、炳胜公馆。",
                    "广州塔小蛮腰 433m 白云观光厅 228 元（送摩天轮 + 速降体验另加钱）；夜景比白天值。",
                    "沙面岛、陈家祠：免费+免费，适合拍照和看岭南建筑。"}),
            Map.entry("厦门", new String[]{
                    "鼓浪屿：提前 7 天在「厦门轮渡」公众号或小程序买船票（35 元），东渡邮轮中心码头→三丘田码头；国庆/五一 10 分钟售罄。",
                    "岛上别买网红「苏小糖」/ 赵小姐的店，性价比低；去龙头路小吃摊吃海蛎煎、沙茶面、土笋冻。",
                    "环岛路骑行：从曾厝垵出发 → 黄厝海滩 → 椰风寨，18 公里 4-5h；下午去黄厝海滩看日落最赞。"}),
            Map.entry("丽江", new String[]{
                    "玉龙雪山：海拔 4680m，提前 3 天公众号抢大索道票（140 元）+ 氧气瓶 1 瓶/人（山下买 10 元，山上 60 元）。",
                    "丽江古城维护费 50 元一般由客栈代收；古城入口有检查点。",
                    "古城石板路 + 海拔高，鞋子一定要穿软底徒步鞋，别穿高跟鞋！",
                    "泸沽湖 2-3 天行程才值，单天往返太赶；从丽江坐车 4h，建议跟团或拼车。",
                    "高原反应预防：提前 3 天吃红景天、到丽江第一天别洗澡喝酒、多喝水少剧烈运动。"}),
            Map.entry("桂林", new String[]{
                    "漓江竹筏/游船：一定要在正规渠道购买「磨盘山码头→阳朔」全程 4.5h（官方三星船约 215 元，含中饭）。",
                    "兴坪古镇段（20 元人民币背景）最经典，从杨堤→兴坪竹筏 1.5h，适合怕坐船久的。",
                    "阳朔十里画廊：租电动车 30 元/天骑行，不要听信路边「免费景点」的三轮车夫。",
                    "龙脊梯田：秋天（9月中-10月中）金黄、5-6 月灌水期最美；单程 2.5h，建议住一晚看日出。"})
    );

    // 通用基础清单（出行人通用）
    private static final List<String> BASE_DOC = List.of(
            "身份证 / 护照（身份证必带，且建议拍照片存手机云端备用）",
            "学生证 / 军人证 / 残疾证 / 教师证（门票半价优惠利器）",
            "2 寸白底照片 2 张（临时证件补办用）",
            "现金 500-1000 元（部分偏远景点/山区手机支付失灵）"
    );
    private static final List<String> BASE_ELEC = List.of(
            "手机充电器 + 充电宝（≤100Wh，2 万毫安以下可带上飞机）",
            "Type-C / Lightning 数据线 × 2（一根放随身，一根放行李箱）",
            "蓝牙耳机 / 降噪耳机（长途高铁/飞机必备）",
            "转换插头 / 插线板（酒店插口紧张时派上用场）"
    );
    private static final List<String> BASE_TOILET = List.of(
            "旅行装牙膏牙刷 / 电动牙刷（部分环保酒店不提供一次性用品）",
            "洗发水 / 沐浴露 / 护发素 分装瓶（各 ≤100ml 可登机）",
            "洁面巾 1 包（比毛巾卫生）+ 浴巾 1 条（自备更安心）",
            "防晒霜 SPF50+ PA++++（任何季节都需要）+ 晒后修复",
            "卸妆油/卸妆巾、润唇膏、护手霜、面膜"
    );
    private static final List<String> BASE_MED = List.of(
            "感冒/退烧药（布洛芬 / 泰诺）、肠胃药（蒙脱石散 / 黄连素 / 奥美拉唑）",
            "创可贴、碘伏棉签、晕车药 / 晕机糖",
            "过敏药（氯雷他定、西替利嗪）、肌肉酸痛贴膏",
            "特殊慢性病患者：处方药 + 英文说明书（带齐 2 天以上备用量）"
    );
    private static final List<String> BASE_MISC = List.of(
            "晴雨两用伞（挡雨+防晒）",
            "一次性内衣 / 一次性内裤 / 一次性马桶垫（酒店卫生）",
            "便携衣架 2 个 + 晾衣绳（连住多天用）",
            "保温杯（高铁/飞机/景区喝热水方便）",
            "垃圾袋 × 5 + 湿纸巾 × 2 + 免洗洗手液"
    );

    @Override
    public String name() {
        return "travel_tips";
    }

    @Override
    public String description() {
        return "生成【分章节 · 可按天数自动量化】的出行注意事项：证件清单、电子装备、洗漱用品、药品、行李衣物"
                + "（按温度自动推荐）、城市专属避坑贴士、人群专属提醒（亲子/老人/商务）、热门景点预约攻略。"
                + "最终一步【build_itinerary 前调用】，把生成结果作为 notes 参数传入 build_itinerary。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("cities", Map.of("type", "array",
                "items", Map.of("type", "string"),
                "description", "经过的城市列表，例如 [\"上海\"]、[\"成都\",\"重庆\"]"));
        props.put("start_date", Map.of("type", "string",
                "description", "出行开始日期 yyyy-MM-dd，默认 7 天后。"));
        props.put("days", Map.of("type", "integer",
                "description", "行程天数，默认 3。用于自动计算需要带多少件内衣/袜子、消耗品数量。"));
        props.put("people", Map.of("type", "integer",
                "description", "出行人数，默认 2。用于药品/洗漱用品等的乘数量化。"));
        props.put("weather_summary", Map.of("type", "string",
                "description", "query_weather 返回的天气文本（或至少包含最高温/最低温/晴雨关键词，可选）。"
                        + "不给则按季节默认推荐。"));
        props.put("traveler_type", Map.of("type", "string",
                "enum", new String[]{"solo", "couple", "family", "senior", "student", "business", "pet_friendly"},
                "description", "人群类型：solo 独自、couple 情侣、family 亲子、senior 老人、student 学生、business 商务、pet_friendly 带宠物。默认 family。"));
        props.put("scenario", Map.of("type", "string",
                "enum", new String[]{"city", "mountain", "island", "snow", "plateau", "ancient_town", "hot_spring", "cruise"},
                "description", "场景类型：city 城市、mountain 山岳、island 海岛、snow 滑雪、plateau 高原、ancient_town 古镇、"
                        + "hot_spring 温泉、cruise 邮轮。默认 city。"));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("type", "object");
        params.put("properties", props);
        params.put("required", List.of("cities"));
        return params;
    }

    @Override
    public AgentToolResult execute(String argumentsJson) {
        try {
            JsonNode args = MAPPER.readTree(argumentsJson);
            JsonNode citiesNode = args.path("cities");
            List<String> cities = new ArrayList<>();
            if (citiesNode.isArray()) {
                for (JsonNode n : citiesNode) cities.add(n.asText(""));
            } else {
                String c = citiesNode.asText("");
                if (!c.isBlank()) cities.add(c);
            }
            if (cities.isEmpty()) return AgentToolResult.text("❌ 参数错误：必须给 cities（至少 1 个城市名）。");
            String sd = args.path("start_date").asText("");
            LocalDate start;
            try { start = sd.isBlank() ? LocalDate.now().plusDays(7) : LocalDate.parse(sd); }
            catch (Exception e) { start = LocalDate.now().plusDays(7); }
            int days = Math.max(1, Math.min(30, args.path("days").asInt(3)));
            int people = Math.max(1, Math.min(30, args.path("people").asInt(2)));
            String weather = args.path("weather_summary").asText("");
            String traveler = args.path("traveler_type").asText("family");
            String scenario = args.path("scenario").asText("city");

            // 解析温度
            int[] temps = parseTemperature(weather, start);
            int minT = temps[0], maxT = temps[1];
            boolean rainy = weather.contains("雨") || weather.contains("雷") || weather.contains("Storm");
            boolean sunny = weather.contains("晴") || weather.contains("Sunny");
            boolean hot = maxT >= 28;
            boolean cold = minT <= 8;

            StringBuilder sb = new StringBuilder();
            sb.append("### 📝 出行注意事项 & 智能行李清单\n");
            sb.append("- 日期：").append(start).append(" 起 · 共 ").append(days).append(" 天 · ").append(people).append(" 人\n")
              .append("- 城市：").append(String.join(" → ", cities)).append("\n")
              .append("- 温度区间：约 ").append(minT).append("°C ~ ").append(maxT)
              .append("°C（").append(hot ? "较热" : (cold ? "寒冷" : "舒适"))
              .append("）｜降雨概率：").append(rainy ? "🌧️ 高，务必带伞" : "正常")
              .append("｜紫外线：").append(sunny ? "☀️ 强" : "一般")
              .append("｜人群：").append(travelerLabel(traveler)).append("｜场景：").append(scenarioLabel(scenario)).append("\n\n");

            // 1 证件
            sb.append("#### 🆔 1. 证件 & 财务\n");
            for (String s : BASE_DOC) sb.append("- ").append(s).append("\n");
            sb.append(specialDocFor(traveler));
            sb.append("\n");

            // 2 电子
            sb.append("#### 📱 2. 电子装备\n");
            for (String s : BASE_ELEC) sb.append("- ").append(s).append("\n");
            if ("business".equals(traveler)) sb.append("- 笔记本电脑 + 65W PD 快充头 + 无线鼠标（办公方便）\n");
            sb.append("\n");

            // 3 衣物（按温度自动推荐数量）
            sb.append("#### 👕 3. 衣物（按 ").append(days).append(" 天 ").append(people).append(" 人 自动量化）\n");
            sb.append(clothingList(days, people, minT, maxT, scenario, traveler));
            sb.append("\n");

            // 4 洗漱
            sb.append("#### 🧴 4. 洗漱 / 护肤 / 防晒\n");
            for (String s : BASE_TOILET) sb.append("- ").append(s).append("\n");
            if (rainy) sb.append("- 防水鞋套 / 一次性雨衣（不要用雨伞，山区雨天不安全）\n");
            if ("island".equals(scenario)) sb.append("- 泳衣 1 套 + 浮潜袜 + 速干浴巾（不要穿酒店拖鞋去沙滩）\n");
            if ("snow".equals(scenario)) sb.append("- 护脸面罩 + 防雪盲墨镜 + 防水手套（滑雪必备 3 件套）\n");
            sb.append("\n");

            // 5 药品（按人数 × 天数乘）
            sb.append("#### 💊 5. 药品 & 急救（已按 ").append(people).append(" 人 ").append(days).append(" 天 估算）\n");
            for (String s : BASE_MED) sb.append("- ").append(s).append("\n");
            if ("plateau".equals(scenario)) sb.append("- 红景天（提前 3 天服用）+ 氧气瓶（2L/人，到当地买更划算）\n");
            if ("family".equals(traveler)) sb.append("- 儿童退烧药（美林/泰诺林）+ 益生菌 + 防蚊液（儿童专用）\n");
            if ("senior".equals(traveler)) sb.append("- 血压计 + 降压药（备量 2 份）+ 硝酸甘油（心脏应急）\n");
            sb.append("\n");

            // 6 其它
            sb.append("#### 🧰 6. 日用品 & 杂项\n");
            for (String s : BASE_MISC) sb.append("- ").append(s).append("\n");
            sb.append(specialMiscFor(traveler, scenario));
            sb.append("\n");

            // 7 天气穿衣建议
            sb.append("#### 🌤️ 7. 天气穿搭建议\n");
            sb.append(dressAdvice(minT, maxT, rainy, sunny, scenario, traveler));
            sb.append("\n");

            // 8 城市专属
            sb.append("#### 🌆 8. 城市专属注意事项\n");
            for (String c : cities) {
                String[] tips = CITY_TIPS.get(c);
                sb.append("**").append(c).append("**：\n");
                if (tips == null) {
                    sb.append("- 暂无深度知识库，推荐用 web_search 搜「").append(c).append(" 避坑攻略」补充细节。\n");
                } else {
                    for (String t : tips) sb.append("- ").append(t).append("\n");
                }
            }
            sb.append("\n");

            // 9 人群专属
            sb.append("#### 👨‍👩‍👧 9. 人群专属提醒\n");
            sb.append(travelerAdvice(traveler, days, cities));
            sb.append("\n");

            // 10 预约 & 避坑
            sb.append("#### 💡 10. 预约 & 省钱 & 避坑总览\n");
            sb.append("- 所有热门景点：请在【各自官方公众号/小程序】实名预约，不要在第三方黄牛处买溢价票。\n");
            sb.append("- 交通：12306 提前 15 天放票、机场/高铁站 VIP 厅可刷银联白金卡进。\n");
            sb.append("- 住宿：行程不确定时选「免费取消」，一般只贵 10-30 元/晚，性价比高。\n");
            sb.append("- 避坑：所有「出租车司机推荐饭店」基本都有返佣，别去；所有景区门口卖的特产都比市区贵 30-50%。\n");
            sb.append("- 省钱：下载「云闪付 APP」搜「文旅消费券」，很多城市景区/餐饮有 20-30% 满减。\n");
            return AgentToolResult.text(sb.toString());
        } catch (Exception e) {
            return AgentToolResult.text("❌ travel_tips 执行失败：" + e.getMessage());
        }
    }

    // ========== 辅助 ==========

    private static String travelerLabel(String t) {
        return switch (t) {
            case "solo"         -> "独自出行";
            case "couple"       -> "情侣/夫妻";
            case "family"       -> "亲子家庭";
            case "senior"       -> "老年团";
            case "student"      -> "学生党";
            case "business"     -> "商务差旅";
            case "pet_friendly" -> "带宠物";
            default             -> "综合出行";
        };
    }

    private static String scenarioLabel(String s) {
        return switch (s) {
            case "city"         -> "城市观光";
            case "mountain"     -> "山岳徒步";
            case "island"       -> "海岛度假";
            case "snow"         -> "滑雪";
            case "plateau"      -> "高原";
            case "ancient_town" -> "古镇漫步";
            case "hot_spring"   -> "温泉";
            case "cruise"       -> "邮轮";
            default             -> "综合";
        };
    }

    private static int[] parseTemperature(String weather, LocalDate start) {
        // 匹配 "25°C/18°C" "最高 28 最低 17" "20~30度" 等常见表达
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(最高\\s*)?([0-9]{1,2})\\s*°?\\s*[CＣ度]?\\s*[～~/-]\\s*(最低\\s*)?([0-9]{1,2})")
                .matcher(weather);
        if (m.find()) {
            try {
                int a = Integer.parseInt(m.group(2));
                int b = Integer.parseInt(m.group(4));
                return new int[]{Math.min(a, b), Math.max(a, b)};
            } catch (Exception ignore) {}
        }
        java.util.regex.Matcher m2 = java.util.regex.Pattern.compile("([0-9]{1,2})\\s*°?[CＣ]").matcher(weather);
        if (m2.find()) {
            int t = Integer.parseInt(m2.group(1));
            return new int[]{t - 5, t + 3};
        }
        // fallback：按月给默认
        int mo = start.getMonthValue();
        return switch (mo) {
            case 12, 1, 2 -> new int[]{-2, 6};
            case 3, 4     -> new int[]{8, 20};
            case 5, 6     -> new int[]{18, 28};
            case 7, 8     -> new int[]{25, 34};
            case 9, 10    -> new int[]{15, 25};
            case 11       -> new int[]{6, 16};
            default       -> new int[]{15, 25};
        };
    }

    private static String specialDocFor(String traveler) {
        return switch (traveler) {
            case "family"       -> "- 儿童出生证明 / 户口本复印件（儿童机票/高铁核验）\n";
            case "senior"       -> "- 老人高血压/糖尿病处方笺 + 医保卡（异地备案建议提前办）\n";
            case "pet_friendly" -> "- 宠物疫苗本 + 航空箱 + 火车托运/空运检疫合格证明（托运要提前 3 天办）\n";
            default             -> "";
        };
    }

    private static String specialMiscFor(String traveler, String scenario) {
        StringBuilder s = new StringBuilder();
        if ("family".equals(traveler)) {
            s.append("- 儿童推车（轻便折叠款，可登机）+ 防走失背包\n")
             .append("- 防水围兜 × 5 + 免洗消毒湿巾（儿童款）\n");
        }
        if ("pet_friendly".equals(traveler)) {
            s.append("- 宠物牵引绳（带反光）+ 便携水壶 + 拾便袋\n");
        }
        if ("mountain".equals(scenario)) {
            s.append("- 登山杖（碳纤维 2 根）+ 硬壳冲锋衣 + 专业徒步鞋（Vibram 底）+ 头灯\n");
        }
        if ("ancient_town".equals(scenario)) {
            s.append("- 汉服/旗袍 1 套（古镇出片神器）+ 自拍杆 / 三脚架\n");
        }
        if ("cruise".equals(scenario)) {
            s.append("- 正装 1 套（船长晚宴）、拖鞋、晕船药（盐酸苯海拉明）\n");
        }
        return s.toString();
    }

    private static String clothingList(int days, int people, int minT, int maxT, String scenario, String traveler) {
        // 基础量：内衣 = days+2 件/人，袜子 = days+3 双/人，T恤 = 每天 1 件/人
        StringBuilder sb = new StringBuilder();
        int perPerson = people;
        sb.append(String.format("- 一次性内裤 %d 条（每人每天 1 条 + 备用 %d）\n", (days + 2) * perPerson, 2 * perPerson));
        sb.append(String.format("- 袜子 %d 双（每人每天 1 双 + 备用 %d）\n", (days + 3) * perPerson, 3 * perPerson));
        if (maxT >= 22) {
            sb.append(String.format("- 短袖 / T恤 %d 件（每人每天 1 件）\n", days * perPerson));
        }
        if (maxT <= 15 || minT <= 10) {
            sb.append(String.format("- 长袖 / 卫衣 %d 件（早晚温差大）\n", Math.max(2, perPerson)));
        }
        if (minT <= 8) {
            sb.append(String.format("- 羽绒服 / 厚外套 %d 件（每人 1 件）\n", perPerson));
        } else if (minT <= 18) {
            sb.append(String.format("- 薄外套 / 风衣 %d 件（每人 1 件）\n", perPerson));
        }
        // 裤子
        sb.append(String.format("- 长裤 %d 条（可搭配 2 条牛仔裤 + 1 条运动裤）\n", Math.max(2, perPerson * 2)));
        if (maxT >= 28 || "island".equals(scenario)) {
            sb.append(String.format("- 短裤 / 半身裙 %d 条\n", Math.max(1, perPerson)));
        }
        if ("family".equals(traveler)) {
            sb.append(String.format("- 儿童备用衣物 %d 套（防止玩水/弄脏）\n", Math.max(1, perPerson)));
        }
        // 睡眠
        sb.append(String.format("- 睡衣 %d 套\n", perPerson));
        // 内衣袜子清单结束
        // 配饰
        sb.append("- 墨镜 1 副（夏天/高原/海岛必带）\n");
        if (maxT >= 25) sb.append("- 遮阳帽 + 防晒冰袖 1 套/人\n");
        if ("plateau".equals(scenario)) sb.append("- 魔术头巾 / 面巾（防晒防风沙）\n");
        return sb.toString();
    }

    private static String dressAdvice(int minT, int maxT, boolean rainy, boolean sunny, String scenario, String traveler) {
        StringBuilder sb = new StringBuilder();
        if (maxT >= 30) {
            sb.append("🔥 气温 ≥ 30°C：短袖 + 短裤/薄长裙 + 凉鞋。防晒优先：SPF50+ 每 2 小时补涂，戴遮阳帽，避免正午 11-14 点长时间暴晒。");
        } else if (maxT >= 22 && minT >= 15) {
            sb.append("😊 气温舒适（").append(minT).append("~").append(maxT).append("°C）：短袖 + 薄外套或 T 恤 + 长牛仔裤。早晚有温差，随身携带一件薄开衫。");
        } else if (maxT >= 15 && minT >= 6) {
            sb.append("🍂 秋季型气温：薄卫衣 + 风衣 / 牛仔外套。下身建议长裤 + 薄袜子。");
        } else if (minT <= 5) {
            sb.append("🥶 寒冷：羽绒服 + 加绒保暖内衣 + 抓绒衣，三件套。帽子、围巾、手套三件套别忘。");
        } else {
            sb.append("🌤️ 中等气温：薄卫衣 + 夹克，里面穿长袖 T 恤，外层可脱。");
        }
        if (rainy) {
            sb.append("\n🌧️ 有雨：一定要穿防水鞋 + 带长柄伞（折叠伞风大挡不住）；手机装防水袋。");
        }
        if ("senior".equals(traveler)) {
            sb.append("\n🧓 老人：穿宽松透气易穿脱的拉链款外套；足部防滑运动鞋（避免湿滑）；可备护膝。");
        }
        if ("family".equals(traveler)) {
            sb.append("\n👶 儿童：出行多备 1-2 套长袖长裤替换（玩水枪/淋雨/坐地上都会脏）。");
        }
        return sb.append("\n").toString();
    }

    private static String travelerAdvice(String traveler, int days, List<String> cities) {
        String c = String.join("/", cities);
        return switch (traveler) {
            case "family" -> "👨‍👩‍👧 亲子家庭：\n"
                    + "- 每天安排最多 2 个核心景点 + 1 次午睡 / 自由活动，别塞太满；\n"
                    + "- 迪士尼、欢乐谷、海洋公园等：建议买「快速通行卡」或走单人通道，减少排队；\n"
                    + "- 行程中安排一次【当地科技馆/自然博物馆/海洋馆】，孩子既玩又学。\n";
            case "senior" -> "🧓 老年出行：\n"
                    + "- 行程节奏减半，每日最多 3h 室外步行，午间强制回酒店午休；\n"
                    + "- 酒店选有电梯、早餐、离医院 ≤3km 的老城区店；\n"
                    + "- 门票老人优惠：60+ 半价，65+ 免费，带身份证直接到窗口核验。\n";
            case "business" -> "💼 商务差旅：\n"
                    + "- 酒店优先行政酒廊（早餐 + 下午茶 + Happy Hour 解决吃饭问题）；\n"
                    + "- 高铁选一等座或商务座（安静、可办公）+ 商务座送站服务；\n"
                    + "- 会议日当天不要安排景点，把游玩集中在周末前后。\n";
            case "solo" -> "🧑 独自出行：\n"
                    + "- 微信位置共享给家人，每到一个酒店把定位发朋友圈（分组可见）；\n"
                    + "- 打车用 APP 一键报警；不搭陌生人顺风车；深夜不坐地铁末节车厢；\n"
                    + "- 行程留 50% 空白，一个人最爽的是想走走想停停，不要把日程排满。\n";
            case "couple" -> "💕 情侣出行：\n"
                    + "- 酒店预订时备注「纪念日布置」，很多高端酒店会免费送气球/玫瑰/蛋糕；\n"
                    + "- 行程留一场日落/夜景/米其林餐厅的仪式感；\n"
                    + "- 所有景点预约放在同一个手机，同时抢，成功率翻倍。\n";
            case "student" -> "🎒 学生党：\n"
                    + "- 带学生证！所有景点基本半价（甚至免票）；\n"
                    + "- 交通：12306 学生票一年 4 次（寒暑假各 2 次），记得核验；\n"
                    + "- 住宿：青旅多人间（¥80-150/晚）或家庭民宿拼房，性价比高。\n";
            case "pet_friendly" -> "🐶 带宠物：\n"
                    + "- 提前电话确认所有酒店/景点是否「宠物友好」，90% 的 4A 景区和连锁酒店禁止宠物；\n"
                    + "- 高铁/飞机托运：提前 72h 给 12306/航司打电话预约有氧舱 + 办检疫合格证。\n";
            default -> "ℹ️ 通用建议：\n- 每天景点 ≤3 个，留 1 段「发呆时间」；\n- 重要门票/车票/酒店都提前截图保存离线。\n";
        };
    }
}
