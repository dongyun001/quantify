package com.quantify.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.quantify.score.ScoreAppeal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 钉钉群自定义机器人。
 *
 * <p>只用 Spring 自带的 {@link RestClient}，不新增依赖（R27）。
 * 连接和读取超时都是 3 秒——webhook 卡住不能把通知线程占死。
 *
 * <p>日志里 webhook 必须打码，不能把 access_token 完整写进日志。
 */
@Slf4j
@Component
public class DingTalkNotifier {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATETIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_ERROR = 500;

    private final DingTalkProperties props;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public DingTalkNotifier(DingTalkProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(3));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 发送结果。skipped 表示未启用 / 未配置 / 结果通知被关闭，不算失败。 */
    public record NotifyResult(boolean success, boolean skipped, String error) {

        static NotifyResult ofSkipped() {
            return new NotifyResult(false, true, null);
        }

        static NotifyResult ofSuccess() {
            return new NotifyResult(true, false, null);
        }

        static NotifyResult ofFail(String error) {
            String e = error == null ? "未知错误" : error;
            if (e.length() > MAX_ERROR) {
                e = e.substring(0, MAX_ERROR);
            }
            return new NotifyResult(false, false, e);
        }
    }

    public NotifyResult sendAppealCreated(ScoreAppeal a) {
        if (!props.ready()) {
            log.info("钉钉未启用或 webhook 为空，跳过申诉创建通知 appealId={}", a.getId());
            return NotifyResult.ofSkipped();
        }
        return send("新的量化申诉", buildCreatedText(a));
    }

    /** 是否配置了可用的 webhook（重试接口用它决定是否返回 502） */
    public boolean isReady() {
        return props.ready();
    }

    public NotifyResult sendAppealReviewed(ScoreAppeal a) {
        if (!props.ready()) {
            log.info("钉钉未启用或 webhook 为空，跳过申诉结果通知 appealId={}", a.getId());
            return NotifyResult.ofSkipped();
        }
        if (!props.isNotifyReviewResult()) {
            log.info("DINGTALK_NOTIFY_REVIEW=false，跳过申诉结果通知 appealId={}", a.getId());
            return NotifyResult.ofSkipped();
        }
        String title = a.getStatus() != null && a.getStatus() == 1 ? "申诉已受理" : "申诉已驳回";
        return send(title, buildReviewedText(a, title));
    }

    private NotifyResult send(String title, String text) {
        try {
            Map<String, Object> markdown = new LinkedHashMap<>();
            markdown.put("title", title);
            markdown.put("text", text);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("msgtype", "markdown");
            body.put("markdown", markdown);

            String resp = restClient.post()
                    .uri(buildWebhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode node = resp == null ? null : objectMapper.readTree(resp);
            int errcode = node == null ? -1 : node.path("errcode").asInt(-1);
            if (errcode == 0) {
                return NotifyResult.ofSuccess();
            }
            String errmsg = node == null ? "空响应" : node.path("errmsg").asText("未知错误");
            log.warn("钉钉返回失败 webhook={} errcode={} errmsg={}", maskedWebhook(), errcode, errmsg);
            return NotifyResult.ofFail("钉钉返回 " + errcode + "：" + errmsg);
        } catch (Exception e) {
            log.warn("钉钉通知发送异常 webhook={} : {}", maskedWebhook(), e.toString());
            return NotifyResult.ofFail(e.getClass().getSimpleName() + "：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    //  消息模板
    // ------------------------------------------------------------------

    private String buildCreatedText(ScoreAppeal a) {
        StringBuilder sb = new StringBuilder();
        sb.append("### 新的量化申诉\n");
        sb.append("- 班级：").append(nvl(a.getClassName())).append('\n');
        sb.append("- 学生：").append(nvl(a.getStudentName()))
                .append("（").append(nvl(a.getStudentNo())).append("）\n");
        sb.append("- 原记录：").append(scoreText(a)).append('\n');
        sb.append("- 申诉类型：").append(appealTypeText(a.getAppealType())).append('\n');
        sb.append("- 申诉理由：").append(nvl(a.getAppealReason())).append('\n');
        if (a.getEvidenceUrl() != null && !a.getEvidenceUrl().isBlank()) {
            sb.append("- 佐证：").append(a.getEvidenceUrl()).append('\n');
        }
        sb.append("- 申诉人：").append(nvl(a.getCreatedByName())).append("（三级）\n");
        sb.append("- 时间：").append(fmt(a.getCreatedAt())).append('\n');
        sb.append("- 处理入口：系统「申诉审批」页");
        return sb.toString();
    }

    private String buildReviewedText(ScoreAppeal a, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(title).append('\n');
        sb.append("- 班级：").append(nvl(a.getClassName())).append('\n');
        sb.append("- 学生：").append(nvl(a.getStudentName()))
                .append("（").append(nvl(a.getStudentNo())).append("）\n");
        sb.append("- 原记录：").append(scoreText(a)).append('\n');
        sb.append("- 申诉理由：").append(nvl(a.getAppealReason())).append('\n');
        sb.append("- 处理意见：").append(nvl(a.getReviewRemark())).append('\n');
        sb.append("- 处理人：").append(nvl(a.getReviewedByName())).append('\n');
        sb.append("- 时间：").append(fmt(a.getReviewedAt())).append('\n');
        if (a.getStatus() != null && a.getStatus() == 1) {
            sb.append("- 处理结果：已生成更正记录，原记录失效");
        } else {
            sb.append("- 处理结果：维持原记录");
        }
        return sb.toString();
    }

    private String scoreText(ScoreAppeal a) {
        if (a.getScoreCategory() == null || a.getScorePoints() == null) {
            return "原记录已不存在";
        }
        String cat = switch (a.getScoreCategory()) {
            case 1 -> "加分";
            case 2 -> "扣分";
            default -> "分值";
        };
        String pts = a.getScorePoints().stripTrailingZeros().toPlainString();
        String reason = nvl(a.getScoreReason());
        String date = a.getScoreOccurredOn() == null ? "" : " · " + a.getScoreOccurredOn().format(DATE);
        return cat + " " + pts + " 分 · " + reason + date;
    }

    // ------------------------------------------------------------------
    //  加签 / 打码
    // ------------------------------------------------------------------

    /** 有 secret 时按钉钉规则加签：timestamp + "\n" + secret → HMAC-SHA256 → Base64 → URL 编码 */
    String buildWebhookUrl() {
        String webhook = props.getWebhook();
        String secret = props.getSecret();
        if (secret == null || secret.isBlank()) {
            return webhook;
        }
        try {
            long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String sign = URLEncoder.encode(
                    Base64.getEncoder().encodeToString(raw), StandardCharsets.UTF_8);
            String sep = webhook.contains("?") ? "&" : "?";
            return webhook + sep + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception e) {
            log.warn("钉钉加签失败，退化为不带 sign 的请求", e);
            return webhook;
        }
    }

    private String maskedWebhook() {
        String wh = props.getWebhook();
        if (wh == null || wh.isBlank()) {
            return "(empty)";
        }
        int idx = wh.indexOf("access_token=");
        if (idx < 0) {
            return wh.length() <= 24 ? wh : wh.substring(0, 24) + "***";
        }
        String prefix = wh.substring(0, Math.min(idx + "access_token=".length() + 6, wh.length()));
        return prefix + "***";
    }

    private static String nvl(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String fmt(LocalDateTime t) {
        return t == null ? "—" : t.format(DATETIME);
    }

    private static String appealTypeText(Integer t) {
        if (t == null) {
            return "其他";
        }
        return switch (t) {
            case 1 -> "分数有误";
            case 2 -> "事由有误";
            case 3 -> "重复录入";
            default -> "其他";
        };
    }
}
