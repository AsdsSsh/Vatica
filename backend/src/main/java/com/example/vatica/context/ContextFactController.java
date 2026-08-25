package com.example.vatica.context;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 29B：关键事实查询、捕获和撤销接口；所有数据由 ContextFactService 做租户校验。 */
@RestController
@RequestMapping("/api/context/facts")
public class ContextFactController {

    private final ContextFactService service;

    public ContextFactController(ContextFactService service) {
        this.service = service;
    }

    /** 默认只返回当前可用事实；审计页面可传 current=false 查看已替代/撤销版本。 */
    @GetMapping
    public List<ContextFactView> list(@RequestParam ContextFactScopeType scopeType,
            @RequestParam String scopeId, @RequestParam(defaultValue = "true") boolean current) {
        List<ContextFactRecord> records = current
                ? service.resolveCurrent(scopeType, scopeId)
                : service.listActive(scopeType, scopeId);
        return records.stream().map(ContextFactView::from).toList();
    }

    @GetMapping("/{id}")
    public ContextFactView get(@PathVariable String id) {
        return ContextFactView.from(service.get(id));
    }

    /** 显式捕获短事实；原文、思维链和敏感字段由服务层拒绝。 */
    @PostMapping
    public ContextFactView capture(@RequestBody ContextFactService.CaptureRequest request) {
        return ContextFactView.from(service.capture(request));
    }

    @PostMapping("/{id}/revoke")
    public ContextFactView revoke(@PathVariable String id, @RequestBody(required = false) RevokeRequest request) {
        return ContextFactView.from(service.revoke(id, request == null ? null : request.reason()));
    }

    /** 来源记录变化后，批量阻止旧事实进入上下文；历史版本仍可审计。 */
    @PostMapping("/source/refresh")
    public RefreshResult markNeedsRefresh(@RequestBody SourceRefreshRequest request) {
        int count = service.markNeedsRefreshBySource(request == null ? null : request.sourceType(),
                request == null ? null : request.sourceId(), request == null ? null : request.reason());
        return new RefreshResult(count);
    }

    public record RevokeRequest(String reason) { }
    public record SourceRefreshRequest(ContextFactSourceType sourceType, String sourceId, String reason) { }
    public record RefreshResult(int affected) { }
}
