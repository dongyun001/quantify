package com.quantify.notify;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 钉钉群机器人配置。
 *
 * <p><b>绝对不要把这些值放进 {@code sys_config}</b>：{@code GET /api/configs}
 * 所有登录用户都能读，等于把 webhook token 发给全院。
 * 只从 {@code application.yml} 或环境变量读取。
 */
@Data
@Component
@ConfigurationProperties(prefix = "quantify.dingtalk")
public class DingTalkProperties {

    /** 关闭时不发送任何消息，只记日志 */
    private boolean enabled = false;

    /** 群自定义机器人 Webhook，含 access_token */
    private String webhook = "";

    /** 机器人开启「加签」时的 SEC 开头密钥；没有可留空 */
    private String secret = "";

    /** 受理 / 驳回结果是否也发通知，默认发 */
    private boolean notifyReviewResult = true;

    /** 是否具备发送条件 */
    public boolean ready() {
        return enabled && webhook != null && !webhook.isBlank();
    }
}
