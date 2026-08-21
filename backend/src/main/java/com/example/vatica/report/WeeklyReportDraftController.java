package com.example.vatica.report;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 26B：周报草案与预览接口；不执行工作区写入。 */
@RestController
@RequestMapping("/api/weekly-reports")
public class WeeklyReportDraftController {

    private final WeeklyReportDraftService service;

    public WeeklyReportDraftController(WeeklyReportDraftService service) {
        this.service = service;
    }

    @PostMapping("/drafts")
    public WeeklyReportDraftService.WeeklyReportDraftView create(
            @RequestBody WeeklyReportDraftService.CreateRequest request) {
        return service.create(request);
    }

    @GetMapping("/drafts")
    public List<WeeklyReportDraftService.WeeklyReportDraftView> recent() {
        return service.recent();
    }

    @GetMapping("/drafts/{id}")
    public WeeklyReportDraftService.WeeklyReportDraftView get(@PathVariable String id) {
        return service.get(id);
    }

    @PatchMapping("/drafts/{id}")
    public WeeklyReportDraftService.WeeklyReportDraftView update(@PathVariable String id,
            @RequestBody WeeklyReportDraftService.UpdateRequest request) {
        return service.update(id, request);
    }
}
