package com.example.vatica.action;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;

/** 迭代 25B：把批准、执行、恢复和取消收口到稳定幂等键。 */
@Service
public class ActionExecutionService {

    public enum Claim { EXECUTE, ALREADY_SUCCEEDED }

    private final ActionExecutionRecordRepository repository;

    public ActionExecutionService(ActionExecutionRecordRepository repository) {
        this.repository = repository;
    }

    /** 为一份用户已看到的动作计划建立批准记录；重复批准只复用原记录。 */
    @Transactional
    public void approve(ActionPlanView plan) {
        RequestIdentity identity = RequestIdentityContext.require();
        for (ActionPlanView.ActionItemView action : plan.actions()) {
            repository.findForUpdate(identity.userId(), action.idempotencyKey()).orElseGet(() ->
                    repository.save(new ActionExecutionRecord(UUID.randomUUID().toString(), identity, plan, action)));
        }
    }

    /** 认领一项动作。已成功动作直接跳过，防止恢复或重复请求再产生副作用。 */
    @Transactional
    public Claim claim(ActionPlanView plan, String actionId) {
        ActionExecutionRecord record = locked(plan, actionId);
        if (record.getStatus() == ActionExecutionStatus.SUCCEEDED) {
            return Claim.ALREADY_SUCCEEDED;
        }
        record.begin();
        repository.save(record);
        return Claim.EXECUTE;
    }

    @Transactional
    public void succeed(ActionPlanView plan, String actionId, String result) {
        ActionExecutionRecord record = locked(plan, actionId);
        record.succeed(result);
        repository.save(record);
    }

    @Transactional
    public void fail(ActionPlanView plan, String actionId, String errorCode, String errorMessage) {
        ActionExecutionRecord record = locked(plan, actionId);
        record.fail(errorCode, errorMessage);
        repository.save(record);
    }

    /** 重试只重新排队失败或请求中断时留下的动作，不重放已成功动作。 */
    @Transactional
    public void requeueRecoverable(ActionPlanView plan) {
        RequestIdentity identity = RequestIdentityContext.require();
        for (ActionPlanView.ActionItemView action : plan.actions()) {
            repository.findForUpdate(identity.userId(), action.idempotencyKey()).ifPresent(record -> {
                record.requeueForRecovery();
                repository.save(record);
            });
        }
    }

    /** 取消不会碰正在执行或已结束的动作；调用方据返回值决定业务对象是否可以进入取消态。 */
    @Transactional
    public int cancelNotStarted(ActionPlanView plan) {
        RequestIdentity identity = RequestIdentityContext.require();
        int cancelled = 0;
        for (ActionPlanView.ActionItemView action : plan.actions()) {
            ActionExecutionRecord record = repository.findForUpdate(identity.userId(), action.idempotencyKey()).orElse(null);
            if (record != null && record.cancelIfNotStarted()) {
                repository.save(record);
                cancelled++;
            }
        }
        return cancelled;
    }

    /** 合并持久化执行事实，计划本身仍由具体业务场景生成。 */
    @Transactional(readOnly = true)
    public ActionPlanView decorate(ActionPlanView plan) {
        RequestIdentity identity = RequestIdentityContext.require();
        Map<String, ActionExecutionRecord> records = new HashMap<>();
        for (ActionExecutionRecord record : repository.findByUserIdAndSubjectTypeAndSubjectId(
                identity.userId(), plan.subjectType(), plan.subjectId())) {
            records.put(record.getActionId(), record);
        }
        List<ActionPlanView.ActionItemView> actions = plan.actions().stream()
                .map(action -> decorate(action, records.get(action.id())))
                .toList();
        return new ActionPlanView(plan.id(), plan.subjectType(), plan.subjectId(), plan.revision(), plan.status(), actions);
    }

    private ActionPlanView.ActionItemView decorate(ActionPlanView.ActionItemView action, ActionExecutionRecord record) {
        if (record == null) {
            return action;
        }
        String approvalStatus = record.getStatus() == ActionExecutionStatus.CANCELLED ? "CANCELLED" : "APPROVED";
        String executionStatus = switch (record.getStatus()) {
            case APPROVED -> "NOT_STARTED";
            case RUNNING -> "RUNNING";
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
        };
        String result = record.getStatus() == ActionExecutionStatus.FAILED
                ? message(record.getErrorCode(), record.getErrorMessage()) : record.getResult();
        return new ActionPlanView.ActionItemView(action.id(), action.type(), action.purpose(), action.target(),
                action.expectedChange(), action.inputSummary(), action.requiredPermission(), action.risk(),
                action.idempotencyKey(), approvalStatus, executionStatus, result);
    }

    private ActionExecutionRecord locked(ActionPlanView plan, String actionId) {
        RequestIdentity identity = RequestIdentityContext.require();
        ActionPlanView.ActionItemView action = plan.actions().stream().filter(item -> item.id().equals(actionId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("操作失败：动作计划中不存在 " + actionId + "。"));
        return repository.findForUpdate(identity.userId(), action.idempotencyKey())
                .orElseThrow(() -> new IllegalStateException("操作失败：动作尚未批准或执行记录缺失。"));
    }

    private static String message(String errorCode, String errorMessage) {
        if (errorCode == null || errorCode.isBlank()) return errorMessage;
        return errorMessage == null || errorMessage.isBlank() ? errorCode : errorCode + "：" + errorMessage;
    }
}
