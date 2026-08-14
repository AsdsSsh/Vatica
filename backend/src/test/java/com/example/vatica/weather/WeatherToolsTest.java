package com.example.vatica.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

/**
 * 模拟天气工具单测（迭代 4）：确定性（同城市同日期结果稳定）、参数校验、输出口径
 * （键=值 + 数据来源标注）。不 mock：纯函数逻辑直接断言。
 */
class WeatherToolsTest {

    private final WeatherTools tools = new WeatherTools();

    /** 同城市同日期两次查询结果一致（确定性模拟，演示可复现） */
    @Test
    void getWeather_deterministic() {
        String first = tools.getWeather("杭州");
        String second = tools.getWeather("杭州");

        assertThat(first).isEqualTo(second)
                .contains("城市=杭州")
                .contains("日期=" + LocalDate.now())
                .contains("数据来源=第三方模拟天气服务（MCP）");
    }

    /** 不同城市通常得到不同天气（模拟数据的区分度） */
    @Test
    void getWeather_differsByCity() {
        assertThat(tools.getWeather("北京")).isNotEqualTo(tools.getWeather("广州"));
    }

    /** 空城市 → 指引错误 */
    @Test
    void getWeather_blankCity_throws() {
        assertThatThrownBy(() -> tools.getWeather("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("城市");
    }

    /** 预报天数边界：1-7 合法，0 和 8 拒绝 */
    @Test
    void forecast_validatesDays() {
        assertThat(tools.forecast("杭州", 1)).contains("未来 1 天预报");
        assertThat(tools.forecast("杭州", 7)).contains("未来 7 天预报");
        assertThatThrownBy(() -> tools.forecast("杭州", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-7");
        assertThatThrownBy(() -> tools.forecast("杭州", 8))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1-7");
    }

    /** 预报包含每一天的日期行与数据来源标注 */
    @Test
    void forecast_listsEveryDay() {
        String result = tools.forecast("上海", 3);

        assertThat(result).contains(LocalDate.now().plusDays(1).toString())
                .contains(LocalDate.now().plusDays(3).toString())
                .contains("数据来源=第三方模拟天气服务（MCP）");
    }
}
