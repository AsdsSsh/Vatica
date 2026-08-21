package com.example.vatica.report;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 26A：只读周报事实入口；草案与副作用留到 26B/26C。 */
@RestController
@RequestMapping("/api/weekly-reports")
public class WeeklyReportController {

    private final WeeklyReportService service;

    public WeeklyReportController(WeeklyReportService service) {
        this.service = service;
    }

    @PostMapping("/facts")
    public WeeklyReportService.WeeklyReportView facts(
            @RequestBody WeeklyReportService.WeeklyReportRequest request) {
        return service.collect(request);
    }
}
