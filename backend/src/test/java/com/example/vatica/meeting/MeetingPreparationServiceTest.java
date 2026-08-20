package com.example.vatica.meeting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.example.vatica.auth.RequestIdentity;
import com.example.vatica.auth.RequestIdentityContext;
import com.example.vatica.knowledge.KnowledgeBaseService;
import com.example.vatica.tool.CalendarEventRecord;
import com.example.vatica.tool.CalendarEventRecordRepository;
import com.example.vatica.tool.IcsParser.CalendarEvent;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 迭代 24A：会议候选与草案必须由当前用户日历事实驱动。 */
class MeetingPreparationServiceTest {

    private final CalendarEventRecordRepository events = mock(CalendarEventRecordRepository.class);
    private final MeetingPreparationRecordRepository preparations = mock(MeetingPreparationRecordRepository.class);
    private final KnowledgeBaseService knowledge = mock(KnowledgeBaseService.class);
    private final MeetingPreparationService service = new MeetingPreparationService(events, preparations, knowledge,
            new ObjectMapper());

    @AfterEach
    void clearIdentity() {
        RequestIdentityContext.clear();
    }

    @Test
    void candidatesKeepSimilarMeetingsSeparateAndFilterByExplicitTopic() {
        identity(7L);
        CalendarEventRecord budget = event(11L, "预算评审", "2026-08-24T09:00", "2026-08-24T10:00");
        CalendarEventRecord product = event(12L, "产品评审", "2026-08-24T10:00", "2026-08-24T11:00");
        CalendarEventRecord later = event(13L, "预算复盘", "2026-08-30T09:00", "2026-08-30T10:00");
        when(events.findByUserIdOrderByStartAtAsc(7L)).thenReturn(List.of(budget, product, later));

        List<MeetingPreparationService.MeetingCandidate> candidates =
                service.candidates("2026-08-24", "2026-08-24", "评审");

        assertThat(candidates).extracting(MeetingPreparationService.MeetingCandidate::eventId)
                .containsExactly(11L, 12L);
        assertThat(candidates).extracting(MeetingPreparationService.MeetingCandidate::title)
                .containsExactly("预算评审", "产品评审");
    }

    @Test
    void createSnapshotsSelectedUsersCalendarEventWithoutSideEffects() {
        identity(7L);
        CalendarEventRecord event = event(11L, "项目周会", "2026-08-24T09:30", "2026-08-24T10:30");
        when(events.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(event));
        when(preparations.save(any(MeetingPreparationRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledge.search(anyString(), anyInt())).thenReturn(new KnowledgeBaseService.SearchResult("项目周会", List.of()));

        MeetingPreparationService.MeetingPreparationView draft =
                service.create(11L, "确认项目风险和决策项", true);

        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.meeting().title()).isEqualTo("项目周会");
        assertThat(draft.meeting().start()).isEqualTo("2026-08-24T09:30");
        assertThat(draft.goal()).isEqualTo("确认项目风险和决策项");
        assertThat(draft.knowledgeRequested()).isTrue();
        assertThat(draft.draft()).isNotNull();
        assertThat(draft.draft().knowledgeStatus()).isEqualTo("READY");
        assertThat(draft.draft().evidence()).extracting(MeetingPreparationService.Evidence::type)
                .containsExactly("CALENDAR_EVENT", "USER_INPUT");
        assertThat(draft.documentPath()).isNull();
        assertThat(draft.todoIds()).isEmpty();
        verify(preparations).save(any(MeetingPreparationRecord.class));
    }

    @Test
    void draftKeepsKnowledgeCitationsAndNeverTurnsThemIntoCalendarFacts() {
        identity(7L);
        CalendarEventRecord event = event(11L, "架构评审", "2026-08-24T09:30", "2026-08-24T10:30");
        when(events.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(event));
        when(preparations.save(any(MeetingPreparationRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeBaseService.Citation citation = new KnowledgeBaseService.Citation("C1", 51L, "架构方案.md",
                "docs/架构方案.md", 81L, "风险", 12, 48, 0.91, "服务边界尚待确认。");
        when(knowledge.search(anyString(), anyInt()))
                .thenReturn(new KnowledgeBaseService.SearchResult("架构评审", List.of(citation)));

        MeetingPreparationService.MeetingPreparationView draft = service.create(11L, null, true);

        assertThat(draft.draft().knowledgeStatus()).isEqualTo("READY");
        assertThat(draft.draft().citations()).singleElement().satisfies(value -> {
            assertThat(value.citationId()).isEqualTo("C1");
            assertThat(value.documentName()).isEqualTo("架构方案.md");
            assertThat(value.quote()).isEqualTo("服务边界尚待确认。");
        });
        assertThat(draft.draft().evidence()).extracting(MeetingPreparationService.Evidence::type)
                .containsExactly("CALENDAR_EVENT");
    }

    @Test
    void draftDegradesWhenKnowledgeSearchIsUnavailableInsteadOfFailingOrInventingContent() {
        identity(7L);
        CalendarEventRecord event = event(11L, "架构评审", "2026-08-24T09:30", "2026-08-24T10:30");
        when(events.findByIdAndUserId(11L, 7L)).thenReturn(Optional.of(event));
        when(preparations.save(any(MeetingPreparationRecord.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(knowledge.search(anyString(), anyInt())).thenThrow(new IllegalStateException("pgvector 不可用"));

        MeetingPreparationService.MeetingPreparationView draft = service.create(11L, "确认风险", true);

        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(draft.draft().knowledgeStatus()).isEqualTo("DEGRADED");
        assertThat(draft.draft().knowledgeMessage()).contains("仅基于日历和用户输入");
        assertThat(draft.draft().citations()).isEmpty();
    }

    @Test
    void createRejectsAnotherUsersOrMissingCalendarEventBeforeSavingDraft() {
        identity(7L);
        when(events.findByIdAndUserId(99L, 7L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(99L, null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不存在或无权");

        verify(preparations, never()).save(any());
    }

    private static CalendarEventRecord event(Long id, String title, String start, String end) {
        CalendarEventRecord result = new CalendarEventRecord(7L, 9L,
                new CalendarEvent(title, LocalDateTime.parse(start), LocalDateTime.parse(end), null));
        ReflectionTestUtils.setField(result, "id", id);
        return result;
    }

    private static void identity(Long userId) {
        RequestIdentityContext.set(new RequestIdentity(userId, 9L, "MEMBER", "learner"));
    }
}
