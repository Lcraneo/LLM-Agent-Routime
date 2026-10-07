package com.youkeda.project.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 天气服务：基于 Open-Meteo 免费 API 实现，无需 API Key。
 *
 * <p>流程：城市名（或 lat:lon）→ 地理编码获取经纬度 → 查询实时天气 / 预报。
 * 保留原有的 WeatherNow / WeatherForecast 数据模型，确保 WeatherTool 无需改动。
 */
@Service
public class WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherService.class);

    private static final String GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search";
    private static final String FORECAST_URL = "https://api.open-meteo.com/v1/forecast";

    private static final Pattern LATLON_PATTERN =
        Pattern.compile("^\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*[:：,，]\\s*(-?\\d{1,3}(?:\\.\\d+)?)\\s*$");

    @Value("${weather.timeout-seconds:15}")
    private long timeoutSeconds;

    private final HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Open-Meteo 无需 Key，始终可用。 */
    public boolean isConfigured() {
        return true;
    }

    public WeatherNow getNow(String location) {
        GeoResult geo = resolveLocation(location);
        JsonNode root = fetchForecastJson(geo, false, 0);
        JsonNode current = root.path("current");
        if (current.isMissingNode() || current.isNull()) {
            throw new WeatherException("Open-Meteo API returned no current weather");
        }
        int weatherCode = current.path("weather_code").asInt(0);
        String windDir = windDirectionToText(current.path("wind_direction_10m").asDouble(0));
        String windScale = windSpeedToScale(current.path("wind_speed_10m").asDouble(0));
        return new WeatherNow(
            geo.toLocation(),
            weatherCodeToText(weatherCode),
            String.valueOf(weatherCode),
            formatTemp(current.path("temperature_2m").asDouble(Double.NaN)),
            formatTemp(current.path("apparent_temperature").asDouble(Double.NaN)),
            current.path("relative_humidity_2m").asText(""),
            windDir,
            windScale,
            current.path("pressure_msl").asText(""),
            current.path("visibility").asText(""),
            current.path("time").asText(""));
    }

    public WeatherForecast getForecast(String location, int days) {
        int normalizedDays = Math.max(1, Math.min(15, days));
        GeoResult geo = resolveLocation(location);
        JsonNode root = fetchForecastJson(geo, true, normalizedDays);
        JsonNode daily = root.path("daily");
        if (daily.isMissingNode() || daily.isNull()) {
            throw new WeatherException("Open-Meteo API returned no daily forecast");
        }
        JsonNode times = daily.path("time");
        JsonNode codes = daily.path("weather_code");
        JsonNode tMax = daily.path("temperature_2m_max");
        JsonNode tMin = daily.path("temperature_2m_min");
        JsonNode hum = daily.path("relative_humidity_2m_max");
        JsonNode windDir = daily.path("wind_direction_10m_dominant");
        JsonNode windSpeed = daily.path("wind_speed_10m_max");

        List<WeatherDaily> dailyList = new ArrayList<>();
        int count = times.isArray() ? times.size() : 0;
        for (int i = 0; i < count; i++) {
            int code = codes.path(i).asInt(0);
            double wdir = windDir.path(i).asDouble(0);
            double wspeed = windSpeed.path(i).asDouble(0);
            dailyList.add(
                new WeatherDaily(
                    times.path(i).asText(""),
                    weatherCodeToText(code),
                    String.valueOf(code),
                    weatherCodeToText(code),
                    String.valueOf(code),
                    formatTemp(tMax.path(i).asDouble(Double.NaN)),
                    formatTemp(tMin.path(i).asDouble(Double.NaN)),
                    hum.path(i).asText(""),
                    windDirectionToText(wdir),
                    windSpeedToScale(wspeed)));
        }
        return new WeatherForecast(geo.toLocation(), root.path("current").path("time").asText(""), dailyList);
    }

    public String formatNow(WeatherNow now) {
        if (now == null || now.location() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append(now.location().name()).append(" ");
        text.append(now.text()).append(" ");
        text.append(now.temperature()).append("℃");
        if (now.feelsLike() != null && !now.feelsLike().isBlank()) {
            text.append("，体感").append(now.feelsLike()).append("℃");
        }
        if (now.humidity() != null && !now.humidity().isBlank()) {
            text.append("，湿度").append(now.humidity()).append("%");
        }
        if (now.windDirection() != null && !now.windDirection().isBlank()) {
            text.append("，").append(now.windDirection()).append("风");
        }
        if (now.windScale() != null && !now.windScale().isBlank()) {
            text.append(now.windScale()).append("级");
        }
        return text.toString();
    }

    public String formatForecast(WeatherForecast forecast) {
        if (forecast == null || forecast.location() == null || forecast.daily() == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append(forecast.location().name()).append("未来").append(forecast.daily().size()).append("天：");
        for (WeatherDaily day : forecast.daily()) {
            text.append("\n")
                .append(day.date())
                .append(" ")
                .append(day.textDay())
                .append(" ")
                .append(day.low())
                .append("~")
                .append(day.high())
                .append("℃");
            if (day.humidity() != null && !day.humidity().isBlank()) {
                text.append("，湿度").append(day.humidity()).append("%");
            }
            if (day.windDirection() != null && !day.windDirection().isBlank()) {
                text.append("，").append(day.windDirection()).append("风");
            }
            if (day.windScale() != null && !day.windScale().isBlank()) {
                text.append(day.windScale()).append("级");
            }
        }
        return text.toString();
    }

    public String describeNow(String location) {
        return formatNow(getNow(location));
    }

    public String describeForecast(String location, int days) {
        return formatForecast(getForecast(location, days));
    }

    // ==================== 内部实现 ====================

    /** 地理编码结果 */
    private record GeoResult(String name, String country, double latitude, double longitude, String timezone) {
        WeatherLocation toLocation() {
            return new WeatherLocation("", name, country, "", timezone, "");
        }
    }

    /**
     * 将 location 解析为经纬度。
     * 支持两种输入：
     * 1. "lat:lon" 或 "lat,lon" 直接解析
     * 2. 城市名（中文/英文/拼音）通过 Open-Meteo 地理编码 API 查询
     */
    private GeoResult resolveLocation(String location) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("location is required");
        }
        String trimmed = location.trim();
        Matcher m = LATLON_PATTERN.matcher(trimmed);
        if (m.matches()) {
            double lat = Double.parseDouble(m.group(1));
            double lon = Double.parseDouble(m.group(2));
            return new GeoResult(trimmed, "", lat, lon, "Asia/Shanghai");
        }
        return geocode(trimmed);
    }

    private GeoResult geocode(String name) {
        String url = GEOCODE_URL
            + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8)
            + "&count=1&language=zh&format=json";
        JsonNode root = getJson(url);
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
            throw new WeatherException("未找到城市：" + name + "，请检查城市名是否正确");
        }
        JsonNode first = results.path(0);
        return new GeoResult(
            first.path("name").asText(name),
            first.path("country").asText(""),
            first.path("latitude").asDouble(0),
            first.path("longitude").asDouble(0),
            first.path("timezone").asText("Asia/Shanghai"));
    }

    /**
     * 调用 Open-Meteo forecast API。
     *
     * @param geo 地理编码结果
     * @param includeDaily 是否包含每日预报
     * @param days 预报天数（仅 includeDaily=true 时生效）
     */
    private JsonNode fetchForecastJson(GeoResult geo, boolean includeDaily, int days) {
        StringBuilder url = new StringBuilder(FORECAST_URL);
        url.append("?latitude=").append(geo.latitude())
            .append("&longitude=").append(geo.longitude())
            .append("&timezone=").append(URLEncoder.encode(geo.timezone(), StandardCharsets.UTF_8))
            .append("&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,"
                + "wind_speed_10m,wind_direction_10m,pressure_msl,surface_pressure");
        if (includeDaily) {
            url.append("&daily=weather_code,temperature_2m_max,temperature_2m_min,"
                + "relative_humidity_2m_max,wind_direction_10m_dominant,wind_speed_10m_max")
                .append("&forecast_days=").append(days);
        }
        return getJson(url.toString());
    }

    private JsonNode getJson(String url) {
        try {
            HttpRequest request =
                HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response =
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new WeatherException(
                    "Open-Meteo API returned HTTP " + response.statusCode() + ": " + response.body());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new WeatherException("天气 API 请求失败: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new WeatherException("天气 API 请求被中断", e);
        }
    }

    /** WMO 天气代码 → 中文描述 */
    private static String weatherCodeToText(int code) {
        return switch (code) {
            case 0 -> "晴";
            case 1 -> "大部晴朗";
            case 2 -> "多云";
            case 3 -> "阴";
            case 45, 48 -> "雾";
            case 51, 53, 55 -> "毛毛雨";
            case 56, 57 -> "冻毛毛雨";
            case 61 -> "小雨";
            case 63 -> "中雨";
            case 65 -> "大雨";
            case 66, 67 -> "冻雨";
            case 71 -> "小雪";
            case 73 -> "中雪";
            case 75 -> "大雪";
            case 77 -> "雪粒";
            case 80 -> "阵雨";
            case 81 -> "中阵雨";
            case 82 -> "强阵雨";
            case 85 -> "阵雪";
            case 86 -> "强阵雪";
            case 95 -> "雷暴";
            case 96, 99 -> "雷暴伴冰雹";
            default -> "未知";
        };
    }

    /** 风向角度（0-360）→ 中文方位 */
    private static String windDirectionToText(double degree) {
        String[] dirs = {"北", "北东北", "东北", "东东北", "东", "东东南", "东南", "南东南",
            "南", "南西南", "西南", "西西南", "西", "西西北", "西北", "北西北"};
        int idx = (int) Math.round(degree / 22.5) % 16;
        return dirs[idx];
    }

    /** 风速（km/h）→ 蒲福风级 */
    private static String windSpeedToScale(double kmh) {
        double ms = kmh / 3.6;
        double[] thresholds = {0, 0.3, 1.6, 3.4, 5.5, 8.0, 10.8, 13.9, 17.2, 20.8, 24.5, 28.5, 32.7};
        for (int i = thresholds.length - 1; i >= 0; i--) {
            if (ms >= thresholds[i]) {
                return String.valueOf(i);
            }
        }
        return "0";
    }

    /** 温度格式化：去掉小数末尾的 .0，NaN 返回空串 */
    private static String formatTemp(double value) {
        if (Double.isNaN(value)) {
            return "";
        }
        if (value == Math.floor(value)) {
            return String.valueOf((int) value);
        }
        return String.valueOf(Math.round(value * 10) / 10.0);
    }
}
