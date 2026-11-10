package com.quantify.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉钉加签算法单测。
 *
 * <p>用固定的 secret 和从 URL 里取出的 timestamp，独立再算一遍 HMAC-SHA256，
 * 比对 sign 是否一致。这样不依赖真实 webhook，也能验证「timestamp + \n + secret →
 * HMAC-SHA256 → Base64 → URL 编码」这条链路。
 */
class DingTalkNotifierTest {

    private static final Pattern TS = Pattern.compile("[?&]timestamp=(\\d+)");
    private static final Pattern SIGN = Pattern.compile("[?&]sign=([^&]+)");

    @Test
    void signMatchesIndependentHmac() throws Exception {
        DingTalkProperties props = new DingTalkProperties();
        props.setEnabled(true);
        props.setWebhook("https://oapi.dingtalk.com/robot/send?access_token=abc123");
        props.setSecret("SECknownSecretForTest");

        String url = new DingTalkNotifier(props, new ObjectMapper()).buildWebhookUrl();

        Matcher tm = TS.matcher(url);
        Matcher sm = SIGN.matcher(url);
        assertTrue(tm.find(), "URL 应带 timestamp");
        assertTrue(sm.find(), "URL 应带 sign");

        String timestamp = tm.group(1);
        String actual = URLDecoder.decode(sm.group(1), StandardCharsets.UTF_8);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(props.getSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = Base64.getEncoder().encodeToString(
                mac.doFinal((timestamp + "\n" + props.getSecret()).getBytes(StandardCharsets.UTF_8)));

        assertEquals(expected, actual);
    }

    @Test
    void noSecretKeepsWebhookUntouched() {
        DingTalkProperties props = new DingTalkProperties();
        props.setEnabled(true);
        props.setWebhook("https://oapi.dingtalk.com/robot/send?access_token=abc123");
        props.setSecret("");

        assertEquals(props.getWebhook(),
                new DingTalkNotifier(props, new ObjectMapper()).buildWebhookUrl());
    }

    @Test
    void readyRequiresEnabledAndWebhook() {
        DingTalkProperties props = new DingTalkProperties();
        assertFalse(props.ready(), "默认不启用");

        props.setEnabled(true);
        assertFalse(props.ready(), "只有 enabled 没有 webhook 也不能发");

        props.setWebhook("https://example.com/robot");
        assertTrue(props.ready());
    }
}
