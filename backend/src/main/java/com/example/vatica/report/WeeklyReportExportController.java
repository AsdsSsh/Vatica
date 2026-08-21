package com.example.vatica.report;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 26C：周报导出计划、批准、重试和取消；不提供自动发送邮件接口。 */
@RestController
@RequestMapping("/api/weekly-reports")
public class WeeklyReportExportController {

    private final WeeklyReportExportService service;

    public WeeklyReportExportController(WeeklyReportExportService service) {
        this.service = service;
    }

    @PostMapping("/drafts/{draftId}/exports")
    public WeeklyReportExportService.WeeklyReportExportView prepare(@PathVariable String draftId,
            @RequestBody WeeklyReportExportService.ExportRequest request) {
        return service.prepare(draftId, request);
    }

    @GetMapping("/exports")
    public List<WeeklyReportExportService.WeeklyReportExportView> recent() {
        return service.recent();
    }

    @GetMapping("/exports/{id}")
    public WeeklyReportExportService.WeeklyReportExportView get(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/exports/{id}/approve")
    public WeeklyReportExportService.WeeklyReportExportView approve(@PathVariable String id) {
        return service.approve(id);
    }

    @PostMapping("/exports/{id}/retry")
    public WeeklyReportExportService.WeeklyReportExportView retry(@PathVariable String id) {
        return service.retry(id);
    }

    @PostMapping("/exports/{id}/cancel")
    public WeeklyReportExportService.WeeklyReportExportView cancel(@PathVariable String id) {
        return service.cancel(id);
    }
}
