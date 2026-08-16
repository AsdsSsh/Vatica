package com.example.vatica.tool;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Properties;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import jakarta.mail.Address;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Store;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.SubjectTerm;

import com.example.vatica.mail.UserMailService;

/**
 * 邮件工具（mail_query / mail_send）——迭代 3.5 PIM：邮件。
 *
 * <p>JavaMail（Angus 实现）：IMAP 收件箱查询/主题搜索 + SMTP 发送；生产环境按当前用户
 * 从 {@code UserMailService} 解析配置，旧 {@link MailProperties} 构造器仅供协议回归测试。集成测试用 GreenMail
 * 内存 SMTP/IMAP 服务器测真实协议行为（不 mock、不连真实邮箱）。
 *
 * <p><b>副作用护栏</b>：发送邮件是不可逆操作 → 必须传 {@code confirm="yes"}（模型须先征得
 * 用户确认）；完整"询问-授权"审批流在迭代 5 HITL 接入后取代该参数兜底。
 *
 * <p>错误约定与文件工具一致：参数问题抛 {@link IllegalArgumentException}（指引文案），
 * 协议/IO 异常包装为 {@link IllegalStateException}（严禁裸抛 checked 异常中断会话）。
 */
public final class MailTools {

    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int IO_TIMEOUT_MS = 30_000;

    private final MailProperties legacyProps;
    private final UserMailService userMailService;

    public MailTools(MailProperties props) {
        this.legacyProps = props;
        this.userMailService = null;
    }

    public MailTools(UserMailService userMailService) {
        this.legacyProps = null;
        this.userMailService = userMailService;
    }

    @Tool(name = "mail_query", description = "查询邮箱收件箱最近的邮件（默认 10 封，最多 50），可按主题关键词过滤。"
            + "返回发件人/主题/时间/大小；回答用户时请原样引用返回中的发件人与主题，不要自行编造邮件内容。")
    public String query(
            @ToolParam(description = "返回邮件封数（可选，默认 10，最多 50）", required = false) Integer limit,
            @ToolParam(description = "主题关键词（可选），只返回主题包含该词的邮件", required = false) String keyword) {
        MailProperties props = properties();
        ensureConfigured(props);
        int n = Math.min(Math.max(limit == null ? 10 : limit, 1), 50);
        try {
            Store store = session(props).getStore("imap");
            store.connect(props.imapHost(), props.imapPort(), props.username(), props.password());
            try (store) {
                Folder inbox = store.getFolder("INBOX");
                inbox.open(Folder.READ_ONLY);
                try (inbox) {
                    Message[] messages = keyword == null || keyword.isBlank()
                            ? inbox.getMessages()
                            : inbox.search(new SubjectTerm(keyword.trim()));
                    int total = messages.length;
                    int count = Math.min(n, total);
                    if (count == 0) {
                        return keyword == null || keyword.isBlank()
                                ? "收件箱为空。"
                                : "收件箱中没有主题包含 \"" + keyword.trim() + "\" 的邮件。";
                    }
                    StringBuilder sb = new StringBuilder("收件箱共 ").append(total).append(" 封邮件，返回最近 ")
                            .append(count).append(" 封：\n");
                    SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm");
                    for (int i = total - 1; i >= total - count; i--) {
                        Message m = messages[i];
                        String from = m.getFrom() != null && m.getFrom().length > 0
                                ? addressText(m.getFrom()[0]) : "（未知）";
                        String subject = m.getSubject() == null ? "（无主题）" : m.getSubject();
                        Date sent = m.getSentDate();
                        sb.append("- 发件人=").append(from).append('\n')
                                .append("  主题=").append(subject).append('\n')
                                .append("  时间=").append(sent == null ? "未知" : fmt.format(sent)).append('\n')
                                .append("  大小=").append(m.getSize()).append(" 字节\n");
                    }
                    return sb.toString().stripTrailing();
                }
            }
        } catch (MessagingException e) {
            throw new IllegalStateException("操作失败：查询邮件失败。" + e.getMessage(), e);
        }
    }

    @Tool(name = "mail_send", description = "发送一封纯文本邮件。发送是副作用操作：必须先向用户确认"
            + "（收件人、主题、正文），用户同意后才能调用，且 confirm 参数必须传 \"yes\"，否则会被拒绝。")
    public String send(
            @ToolParam(description = "收件人邮箱地址（单个或多个，逗号分隔）", required = true) String to,
            @ToolParam(description = "邮件主题", required = true) String subject,
            @ToolParam(description = "邮件正文（纯文本）", required = true) String body,
            @ToolParam(description = "用户确认标记：必须先征得用户同意，用户同意后传 \"yes\"（大小写不敏感），否则拒绝发送",
                    required = true) String confirm) {
        if (!"yes".equalsIgnoreCase(confirm == null ? "" : confirm.trim())) {
            throw new IllegalArgumentException("操作失败：发送邮件是副作用操作，需要用户确认。"
                    + "请先向用户展示收件人、主题与正文并征得同意，用户同意后再调用本工具并传 confirm=\"yes\"。");
        }
        MailProperties props = properties();
        ensureConfigured(props);
        if (to == null || to.isBlank() || subject == null || subject.isBlank() || body == null || body.isBlank()) {
            throw new IllegalArgumentException("操作失败：收件人、主题、正文均不能为空。");
        }
        try {
            MimeMessage message = new MimeMessage(session(props));
            message.setFrom(new InternetAddress(props.username()));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(to.trim()));
            message.setSubject(subject.trim(), "UTF-8");
            message.setText(body, "UTF-8");
            // 显式 connect 传账号密码：静态 Transport.send 依赖 Session 内嵌 Authenticator，
            // 环境变量场景下走显式认证最直白
            Transport transport = session(props).getTransport("smtp");
            try {
                transport.connect(props.smtpHost(), props.smtpPort(), props.username(), props.password());
                transport.sendMessage(message, message.getAllRecipients());
                return "已发送邮件：收件人=" + to.trim() + " 主题=" + subject.trim();
            } finally {
                // 迭代 10 I10-9：发送失败也要关闭 transport，避免 SMTP 连接泄漏
                try {
                    transport.close();
                } catch (MessagingException ignored) {
                    // 收尾阶段关闭失败不覆盖原始发送错误
                }
            }
        } catch (MessagingException e) {
            throw new IllegalStateException("操作失败：发送邮件失败。" + e.getMessage(), e);
        }
    }

    /** 邮箱设置页连通性测试：仅握手并认证，不读信、不发信。 */
    public String testConnection() {
        MailProperties props = properties();
        ensureConfigured(props);
        try {
            Store store = session(props).getStore("imap");
            store.connect(props.imapHost(), props.imapPort(), props.username(), props.password());
            store.close();
            Transport transport = session(props).getTransport("smtp");
            transport.connect(props.smtpHost(), props.smtpPort(), props.username(), props.password());
            transport.close();
            return "IMAP / SMTP 连接成功";
        } catch (MessagingException e) {
            throw new IllegalStateException("操作失败：邮箱连接测试失败。" + e.getMessage(), e);
        }
    }

    // ══════════════════════════════ 内部 ══════════════════════════════

    private MailProperties properties() {
        return userMailService == null ? legacyProps : userMailService.resolve();
    }

    private void ensureConfigured(MailProperties props) {
        if (!props.configured()) {
            String guidance = userMailService == null
                    ? "请设置 MAIL_IMAP_HOST / MAIL_SMTP_HOST / MAIL_USERNAME / MAIL_PASSWORD 后重启应用。"
                    : "请先在“我的邮箱”中设置 IMAP、SMTP、账号与密码。";
            throw new IllegalArgumentException("操作失败：邮箱未配置。" + guidance);
        }
    }

    /** 组装 JavaMail Session：默认端口映射 SSL/STARTTLS（993/465=SSL，587=STARTTLS），带连接与 IO 超时。 */
    private Session session(MailProperties props) {
        Properties p = new Properties();
        p.put("mail.imap.host", props.imapHost());
        p.put("mail.imap.port", String.valueOf(props.imapPort()));
        p.put("mail.imap.ssl.enable", String.valueOf(props.imapPort() == 993));
        p.put("mail.imap.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        p.put("mail.imap.timeout", String.valueOf(IO_TIMEOUT_MS));

        p.put("mail.smtp.host", props.smtpHost());
        p.put("mail.smtp.port", String.valueOf(props.smtpPort()));
        p.put("mail.smtp.auth", String.valueOf(!props.username().isBlank()));
        p.put("mail.smtp.ssl.enable", String.valueOf(props.smtpPort() == 465));
        p.put("mail.smtp.starttls.enable", String.valueOf(props.smtpPort() == 587));
        p.put("mail.smtp.connectiontimeout", String.valueOf(CONNECT_TIMEOUT_MS));
        p.put("mail.smtp.timeout", String.valueOf(IO_TIMEOUT_MS));
        p.put("mail.smtp.writetimeout", String.valueOf(IO_TIMEOUT_MS));
        return Session.getInstance(p);
    }

    private static String addressText(Address address) {
        if (address instanceof InternetAddress ia) {
            String personal = ia.getPersonal();
            return (personal == null || personal.isBlank()) ? ia.getAddress() : personal + " <" + ia.getAddress() + ">";
        }
        return address.toString();
    }
}
