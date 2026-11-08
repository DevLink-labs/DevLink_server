package com.devlink.server.client;

import java.util.List;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.Hints;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.NewEvent;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.QuestionRef;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Common;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Issue;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Position;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class StructuralTeamContextInferenceClientTest {
    private final StructuralTeamContextInferenceClient client = new StructuralTeamContextInferenceClient();

    @Test
    void opensIssuesForUnansweredQuestionsAndKeepsCommonAndChanged() {
        TeamContextSummary previous = new TeamContextSummary(
                List.of(new Common("세션 쿠키로 인증한다", List.of("7:1"))), List.of(), List.of());
        TeamContextSummary next = client.infer(request(previous, "QUESTION", "IN_PROGRESS", "7:3",
                List.of(new QuestionRef("7:3", AgentPart.FRONTEND, "delivery가 왜 null이야?"))));
        assertThat(next.common()).isEqualTo(previous.common());
        assertThat(next.changed()).isEmpty();
        assertThat(next.issues()).singleElement().satisfies(issue -> {
            assertThat(issue.topic()).isEqualTo(StructuralTeamContextInferenceClient.QUESTION_PREFIX + "delivery가 왜 null이야?");
            assertThat(issue.positions()).containsExactly(new Position("FRONTEND", "답변 대기"));
            assertThat(issue.evidence()).containsExactly("7:3");
        });
        assertThat(client.model()).isEqualTo("structural");
    }

    @Test
    void closesQuestionIssueOnceAnsweredButKeepsIssuesOfOtherTasks() {
        Issue open = new Issue(StructuralTeamContextInferenceClient.QUESTION_PREFIX + "delivery?",
                List.of(new Position("FRONTEND", "답변 대기")), List.of("7:3"));
        Issue otherTask = new Issue(StructuralTeamContextInferenceClient.QUESTION_PREFIX + "다른 작업",
                List.of(), List.of("9:2"));
        Issue semantic = new Issue("404 vs 빈 200", List.of(), List.of("7:2"));
        TeamContextSummary previous = new TeamContextSummary(List.of(), List.of(), List.of(open, otherTask, semantic));
        TeamContextSummary next = client.infer(request(previous, "ANSWER", "IN_PROGRESS", "7:4", List.of()));
        assertThat(next.issues()).containsExactly(otherTask, semantic);
    }

    @Test
    void addsFailureIssueOnceAndDoesNotDuplicateOpenQuestions() {
        Issue open = new Issue(StructuralTeamContextInferenceClient.QUESTION_PREFIX + "delivery?",
                List.of(), List.of("7:3"));
        TeamContextSummary previous = new TeamContextSummary(List.of(), List.of(), List.of(open));
        TeamContextInferenceRequest request = request(previous, "STATUS_CHANGED", "FAILED", "7:5",
                List.of(new QuestionRef("7:3", AgentPart.FRONTEND, "delivery?")));
        TeamContextSummary next = client.infer(request);
        assertThat(next.issues()).hasSize(2);
        assertThat(next.issues().get(1).topic()).startsWith(StructuralTeamContextInferenceClient.FAILED_PREFIX);
        assertThat(next.issues().get(1).evidence()).containsExactly("7:5");
        assertThat(client.infer(new TeamContextInferenceRequest(1L, AgentPart.BACKEND, AgentPart.FRONTEND, 2,
                next, request.event(), request.hints())).issues()).isEqualTo(next.issues());
    }

    private static TeamContextInferenceRequest request(TeamContextSummary previous, String type, String status,
            String reference, List<QuestionRef> unanswered) {
        return new TeamContextInferenceRequest(1L, AgentPart.BACKEND, AgentPart.FRONTEND, 1, previous,
                new NewEvent(7L, Long.parseLong(reference.split(":")[1]), reference, type, AgentPart.BACKEND, status,
                        "로그인 응답 수정", "IN_PROGRESS → FAILED: 의존 API 미제공"),
                new Hints(List.of("delivery"), unanswered, unanswered.size(), 0, "FAILED".equals(status)));
    }
}
