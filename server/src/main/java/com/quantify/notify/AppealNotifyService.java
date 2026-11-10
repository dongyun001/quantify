package com.quantify.notify;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.quantify.auth.ScopeContext;
import com.quantify.common.BizException;
import com.quantify.common.ErrorCode;
import com.quantify.log.AuditLogService;
import com.quantify.score.ScoreAppeal;
import com.quantify.score.ScoreAppealMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 申诉的钉钉通知。
 *
 * <p>时序上的硬要求：
 * <ol>
 *   <li>只在申诉事务<b>提交后</b>才发（{@link TransactionPhase#AFTER_COMMIT}）</li>
 *   <li>异步执行，HTTP 超时不阻塞请求线程</li>
 *   <li>发送失败不回滚申诉，只更新 {@code notify_status=2} / {@code notify_error}</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AppealNotifyService {

    private final ScoreAppealMapper appealMapper;
    private final DingTalkNotifier notifier;
    private final AuditLogService auditLogService;
    private final TransactionTemplate transactionTemplate;

    /** 申诉创建事件，事务提交后触发 */
    public record CreatedEvent(Long appealId, Long operatorId) {
    }

    /** 申诉受理 / 驳回事件，事务提交后触发 */
    public record ReviewedEvent(Long appealId, Long operatorId) {
    }

    // ==================================================================
    //  事务提交后的异步发送
    // ==================================================================

    @Async("appealNotifyExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(CreatedEvent event) {
        sendCreated(event.appealId(), event.operatorId());
    }

    @Async("appealNotifyExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewed(ReviewedEvent event) {
        sendReviewed(event.appealId(), event.operatorId());
    }

    public void sendCreated(Long appealId, Long operatorId) {
        ScoreAppeal a = appealMapper.selectDetail(appealId);
        if (a == null) {
            log.warn("申诉不存在，跳过创建通知 appealId={}", appealId);
            return;
        }
        DingTalkNotifier.NotifyResult r = notifier.sendAppealCreated(a);
        markResult(a, r, operatorId);
    }

    public void sendReviewed(Long appealId, Long operatorId) {
        ScoreAppeal a = appealMapper.selectDetail(appealId);
        if (a == null) {
            log.warn("申诉不存在，跳过结果通知 appealId={}", appealId);
            return;
        }
        DingTalkNotifier.NotifyResult r = notifier.sendAppealReviewed(a);
        markResult(a, r, operatorId);
    }

    // ==================================================================
    //  手动重试
    // ==================================================================

    public Map<String, Object> retry(Long id, Long operatorId) {
        ScoreAppeal a = appealMapper.selectDetail(id);
        if (a == null) {
            throw BizException.notFound("申诉不存在");
        }
        if (!ScopeContext.canSee(a.getClassId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权限操作该申诉");
        }
        if (!notifier.isReady()) {
            throw BizException.of("未配置钉钉 webhook，无法重发");
        }

        DingTalkNotifier.NotifyResult r = a.getStatus() != null && a.getStatus() == 0
                ? notifier.sendAppealCreated(a)
                : notifier.sendAppealReviewed(a);

        if (r.skipped()) {
            throw BizException.of("钉钉结果通知已关闭，无法重发");
        }
        markResult(a, r, operatorId);

        Map<String, Object> result = new HashMap<>();
        result.put("notifyStatus", r.success() ? 1 : 2);
        result.put("notifyStatusText", r.success() ? "已发送" : "发送失败");
        result.put("notifyError", r.error());
        return result;
    }

    // ==================================================================
    //  发送结果落库（独立事务）
    // ==================================================================

    private void markResult(ScoreAppeal a, DingTalkNotifier.NotifyResult r, Long operatorId) {
        if (r.skipped()) {
            // 未配置 / 已关闭：保持 notify_status=0，不当作失败
            return;
        }
        int status = r.success() ? 1 : 2;
        try {
            transactionTemplate.executeWithoutResult(tx -> {
                appealMapper.update(null, new UpdateWrapper<ScoreAppeal>()
                        .eq("id", a.getId())
                        .set("notify_status", status)
                        .set("notify_error", r.success() ? null : r.error()));

                Map<String, Object> after = new HashMap<>();
                after.put("notifyStatus", status);
                after.put("notifyError", r.error());
                auditLogService.record(operatorId, null, a.getClassId(),
                        "SCORE_APPEAL", a.getId(), "DINGTALK_NOTIFY", null, after);
            });
        } catch (Exception e) {
            // 通知状态更新失败不能影响主流程（主流程早已提交）
            log.error("更新钉钉通知状态失败 appealId={}", a.getId(), e);
        }
    }
}
