package com.example.vatica.weather;

import java.time.LocalDate;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 第三方模拟天气服务（迭代 4 MCP Client 演示目标）。
 *
 * <p>以独立 Spring Boot 进程运行（{@link WeatherMcpApplication}，端口 8081），通过
 * Spring AI MCP Server（Streamable HTTP，/mcp）暴露给主应用；主应用经 MCP Client 接入后，
 * Agent 即可通过 MCP 协议调用本服务的工具——协议互操作是真实的，天气数据是模拟的
 * （与 GreenMail 同源思路：不依赖外部服务，演示稳定）。
 *
 * <p>{@code @Profile("weather")}：主应用（默认 profile）不加载本类，避免模拟天气工具混入本地工具层；
 * 天气服务进程激活该 profile。
 */
@Component
@Profile("weather")
public class WeatherTools {

    private static final String[] CONDITIONS = { "晴", "多云", "阴", "小雨", "阵雨" };
    private static final String[] WINDS = { "东南风 2 级", "西北风 3 级", "南风 1 级", "东风 4 级", "北风 2 级" };

    /** 模拟数据确定性生成：同城市同日期结果稳定（演示可复现，面试可讲"确定性假数据"）。 */
    private static int seed(String city, LocalDate date) {
        return Math.abs((city.trim() + date).hashCode());
    }

    @Tool(name = "get_weather", description = "查询指定城市今天的天气（第三方模拟天气服务，经 MCP 协议接入；"
            + "数据为确定性模拟值，非真实天气，回答用户时请原样转述返回内容）。")
    public String getWeather(
            @ToolParam(description = "城市名，如\"杭州\"", required = true) String city) {
        String clean = validateCity(city);
        LocalDate today = LocalDate.now();
        int s = seed(clean, today);
        return "城市=" + clean + "\n日期=" + today + "\n天气=" + CONDITIONS[s % CONDITIONS.length]
                + "\n温度=" + (18 + s % 10) + "~" + (26 + s % 8) + "℃\n湿度=" + (45 + s % 40) + "%"
                + "\n风力=" + WINDS[s % WINDS.length] + "\n数据来源=第三方模拟天气服务（MCP）";
    }

    @Tool(name = "weather_forecast", description = "查询指定城市未来 N 天的天气预报（第三方模拟天气服务，经 MCP 协议接入；"
            + "数据为确定性模拟值，非真实天气，回答用户时请原样转述返回内容）。")
    public String forecast(
            @ToolParam(description = "城市名，如\"杭州\"", required = true) String city,
            @ToolParam(description = "预报天数，1-7 天", required = true) Integer days) {
        String clean = validateCity(city);
        if (days == null || days < 1 || days > 7) {
            throw new IllegalArgumentException("操作失败：预报天数须在 1-7 之间。");
        }
        LocalDate today = LocalDate.now();
        StringBuilder sb = new StringBuilder("城市=").append(clean).append("，未来 ").append(days).append(" 天预报：\n");
        for (int i = 1; i <= days; i++) {
            LocalDate d = today.plusDays(i);
            int s = seed(clean, d);
            sb.append("- ").append(d).append(" 天气=").append(CONDITIONS[s % CONDITIONS.length])
                    .append(" 温度=").append(18 + s % 10).append("~").append(26 + s % 8)
                    .append("℃ 风力=").append(WINDS[s % WINDS.length]).append('\n');
        }
        return sb.append("数据来源=第三方模拟天气服务（MCP）").toString();
    }

    private static String validateCity(String city) {
        if (city == null || city.isBlank()) {
            throw new IllegalArgumentException("操作失败：城市名不能为空。");
        }
        return city.trim();
    }
}
