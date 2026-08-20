package com.example.vatica.meeting;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 迭代 24A：结构化会议选择和无副作用草案 API。 */
@RestController
@RequestMapping("/api/meeting-preparations")
public class MeetingPreparationController {

    public record CreateRequest(Long calendarEventId, String goal, Boolean includeKnowledge) { }
    public record DraftUpdateRequest(String goal, Boolean includeKnowledge) { }
    public record RejectRequest(String reason) { }

    private final MeetingPreparationService service;

    public MeetingPreparationController(MeetingPreparationService service) {
        this.service = service;
    }

    @GetMapping("/candidates")
    public List<MeetingPreparationService.MeetingCandidate> candidates(@RequestParam String from,
            @RequestParam String to, @RequestParam(required = false) String topic) {
        return service.candidates(from, to, topic);
    }

    @PostMapping
    public MeetingPreparationService.MeetingPreparationView create(@RequestBody CreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("操作失败：会议准备请求不能为空。");
        }
        return service.create(request.calendarEventId(), request.goal(), request.includeKnowledge());
    }

    /** 24B：草案可在批准前按新的用户目标或资料选项重新生成。 */
    @PatchMapping("/{id}/draft")
    public MeetingPreparationService.MeetingPreparationView refreshDraft(@PathVariable String id,
            @RequestBody DraftUpdateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("操作失败：会议准备草案请求不能为空。");
        }
        return service.refreshDraft(id, request.goal(), request.includeKnowledge());
    }

    /** 24C：批准后才写入准备文档和待办；同一草案重复批准幂等返回。 */
    @PostMapping("/{id}/approve")
    public MeetingPreparationService.MeetingPreparationView approve(@PathVariable String id) {
        return service.approve(id);
    }

    @PostMapping("/{id}/reject")
    public MeetingPreparationService.MeetingPreparationView reject(@PathVariable String id,
            @RequestBody(required = false) RejectRequest request) {
        return service.reject(id, request == null ? null : request.reason());
    }

    @GetMapping
    public List<MeetingPreparationService.MeetingPreparationView> recent() {
        return service.recent();
    }

    @GetMapping("/{id}")
    public MeetingPreparationService.MeetingPreparationView get(@PathVariable String id) {
        return service.get(id);
    }
}
