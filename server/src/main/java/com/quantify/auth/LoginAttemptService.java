package com.quantify.auth;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录失败限流。
 *
 * <p>需求文档 §2.5：<b>同一账号连续失败 5 次，锁定 10 分钟</b>。
 *
 * <p>三个设计决定，都不是随意的：
 *
 * <ol>
 *   <li><b>对不存在的用户名也计数。</b>如果只锁真实存在的账号，攻击者就能靠
 *       "试三次后被锁 / 一直不被锁" 反推哪些用户名是真的，等于送了一份账号清单。
 *       这里无论账号存不存在都同等对待。</li>
 *
 *   <li><b>放内存不放数据库。</b>这是单实例部署，锁 10 分钟而已，进程重启丢计数
 *       也不是问题（攻击者没法重启你的服务）。放数据库要多一次写、还要考虑清理，
 *       收益不抵成本。项目约定里也不允许引 Redis。</li>
 *
 *   <li><b>有容量上限。</b>不加限制的话，攻击者拿一百万个随机用户名来试，
 *       这张 Map 就能把内存撑爆——本来是防攻击的功能反而变成了攻击面。
 *       超过上限时清掉已过期的条目；仍然满就拒绝再记新的（等同于不限流），
 *       理由是：宁可漏掉极端情况下的限流，也不能让服务 OOM。</li>
 * </ol>
 */
@Slf4j
@Service
public class LoginAttemptService {

    /** 连续失败多少次后锁定 */
    @Value("${quantify.login.max-fail:5}")
    private int maxFail;

    /** 锁定时长（分钟） */
    @Value("${quantify.login.lock-minutes:10}")
    private int lockMinutes;

    /** 容量上限，防内存被撑爆 */
    private static final int MAX_ENTRIES = 10_000;
    private record Attempt(int failCount, Instant lockedUntil) {
    }

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    /**
     * 现在还锁着吗？返回剩余锁定秒数，没锁返回 0。
     * <p>要在<b>校验密码之前</b>调用，否则被锁期间仍然会去比对密码，白白消耗 BCrypt 的算力。
     */
    public long lockedRemainSeconds(String username) {
        Attempt a = attempts.get(key(username));
        if (a == null || a.lockedUntil() == null) {
            return 0;
        }
        long remain = Duration.between(Instant.now(), a.lockedUntil()).getSeconds();
        if (remain <= 0) {
            // 锁已到期，顺手清掉，等于"失败次数清零重新开始"
            attempts.remove(key(username));
            return 0;
        }
        return remain;
    }

    /** 记一次失败。达到阈值就上锁 */
    public void recordFailure(String username) {
        String k = key(username);
        if (attempts.size() >= MAX_ENTRIES && !attempts.containsKey(k)) {
            evictExpired();
            if (attempts.size() >= MAX_ENTRIES) {
                log.warn("登录失败计数表已满（{} 条），本次不再记录，避免内存被撑爆", MAX_ENTRIES);
                return;
            }
        }

        attempts.compute(k, (key, old) -> {
            int count = (old == null ? 0 : old.failCount()) + 1;
            if (count >= maxFail) {
                log.warn("账号 {} 连续失败 {} 次，锁定 {} 分钟", username, count, lockMinutes);
                return new Attempt(count, Instant.now().plus(Duration.ofMinutes(lockMinutes)));
            }
            return new Attempt(count, null);
        });
    }

    /** 登录成功，清掉计数 */
    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    /** 供管理端/测试查看当前状态 */
    public int currentFailCount(String username) {
        Attempt a = attempts.get(key(username));
        return a == null ? 0 : a.failCount();
    }

    /** 手动解锁（管理员重置密码后顺手解锁，避免用户明明改了密码却还登不上） */
    public void unlock(String username) {
        attempts.remove(key(username));
    }

    /**
     * 定期清理。只保留"当前还锁着"的条目——
     * 没锁的（含锁定已过期的）都清掉，等于给这些用户名重新开始的机会。
     */
    @Scheduled(fixedDelay = 10 * 60 * 1000L)
    public void evictExpired() {
        Instant now = Instant.now();
        int before = attempts.size();
        attempts.entrySet().removeIf(e -> {
            Instant until = e.getValue().lockedUntil();
            return until == null || until.isBefore(now);
        });
        int removed = before - attempts.size();
        if (removed > 0) {
            log.debug("清理登录失败计数 {} 条，剩余 {}", removed, attempts.size());
        }
    }

    /** 用户名大小写不敏感，避免用 Admin / ADMIN 绕开计数 */
    private String key(String username) {
        return username == null ? "" : username.trim().toLowerCase();
    }
}
