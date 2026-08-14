package com.example.vatica.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

/**
 * 邮件工具集成测试（迭代 3.5）：GreenMail 内存 SMTP/IMAP 服务器，测真实协议收发（不 mock）。
 * 覆盖：confirm 兜底、未配置指引、发送-收件往返、主题过滤、条数上限。
 */
class MailToolsTest {

    private GreenMail greenMail;
    private MailTools mailTools;

    @BeforeEach
    void setUp() {
        greenMail = new GreenMail(new ServerSetup[] {
                new ServerSetup(3025, null, ServerSetup.PROTOCOL_SMTP),
                new ServerSetup(3143, null, ServerSetup.PROTOCOL_IMAP) });
        greenMail.setUser("user@example.com", "user@example.com", "password");
        greenMail.start();
        mailTools = new MailTools(new MailProperties(
                "localhost", 3143, "localhost", 3025, "user@example.com", "password"));
    }

    @AfterEach
    void tearDown() {
        greenMail.stop();
    }

    /** 未配置邮箱 → 查询返回指引错误 */
    @Test
    void query_unconfigured_guidance() {
        MailTools unconfigured = new MailTools(new MailProperties("", 0, "", 0, "", ""));

        assertThatThrownBy(() -> unconfigured.query(null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未配置")
                .hasMessageContaining("MAIL_IMAP_HOST");
    }

    /** 未配置邮箱 → 发送同样返回指引错误 */
    @Test
    void send_unconfigured_guidance() {
        MailTools unconfigured = new MailTools(new MailProperties("", 0, "", 0, "", ""));

        assertThatThrownBy(() -> unconfigured.send("a@b.com", "主题", "正文", "yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未配置");
    }

    /** 无 confirm → 拒绝发送（副作用护栏） */
    @Test
    void send_withoutConfirm_refused() {
        assertThatThrownBy(() -> mailTools.send("user@example.com", "主题", "正文", "no"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("确认");
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    /** confirm 大小写不敏感：YES 也可发送 */
    @Test
    void send_confirmCaseInsensitive() {
        String result = mailTools.send("user@example.com", "你好", "正文内容", "YES");

        assertThat(result).contains("已发送邮件").contains("你好");
        assertThat(greenMail.getReceivedMessages()).hasSize(1);
    }

    /** 发送 → 收件箱查询往返：发件人/主题/正文真实可读 */
    @Test
    void sendAndQuery_roundTrip() throws Exception {
        mailTools.send("user@example.com", "周会提醒", "明天 10 点项目周会", "yes");

        String queried = mailTools.query(10, null);

        assertThat(queried)
                .contains("收件箱共 1 封邮件")
                .contains("主题=周会提醒")
                .contains("user@example.com");
        assertThat(greenMail.getReceivedMessages()[0].getSubject()).isEqualTo("周会提醒");
    }

    /** 主题关键词过滤：只返回匹配的邮件 */
    @Test
    void query_keywordFilter() {
        mailTools.send("user@example.com", "周会提醒", "a", "yes");
        mailTools.send("user@example.com", "需求评审通知", "b", "yes");

        String queried = mailTools.query(10, "周会");

        assertThat(queried).contains("主题=周会提醒").doesNotContain("需求评审");
    }

    /** 无匹配关键词 → 明确提示 */
    @Test
    void query_keywordNoMatch_notice() {
        mailTools.send("user@example.com", "周会提醒", "a", "yes");

        assertThat(mailTools.query(10, "不存在的词")).contains("没有主题包含");
    }

    /** limit 生效：3 封邮件只返回最近 2 封 */
    @Test
    void query_limitCaps() {
        mailTools.send("user@example.com", "邮件1", "a", "yes");
        mailTools.send("user@example.com", "邮件2", "b", "yes");
        mailTools.send("user@example.com", "邮件3", "c", "yes");

        String queried = mailTools.query(2, null);

        assertThat(queried).contains("共 3 封邮件，返回最近 2 封")
                .contains("邮件2").contains("邮件3")
                .doesNotContain("邮件1");
    }

    /** 空收件箱 → 明确提示 */
    @Test
    void query_emptyInbox_notice() {
        assertThat(mailTools.query(10, null)).contains("收件箱为空");
    }

    /** 空收件人/主题/正文 → 报错 */
    @Test
    void send_blankFields_throws() {
        assertThatThrownBy(() -> mailTools.send(" ", "主题", "正文", "yes"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
    }
}
