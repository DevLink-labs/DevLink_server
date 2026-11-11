package com.devlink.server.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest.QuestionRef;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Issue;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Position;

/**
 * LLM 없이 구조 신호만으로 쟁점을 유지하는 폴백이다. 미답변 질문과 실패·거절 작업만 쟁점으로 올리고,
 * 답변이 달리면 그 질문 쟁점을 닫는다. 공통 맥락·달라진 맥락은 추론이 필요하므로 이전 값을 그대로 둔다.
 */
public class StructuralTeamContextInferenceClient implements TeamContextInferenceClient {
    public static final String MODEL = "structural";
    public static final String QUESTION_PREFIX = "[미답변 질문] ";
    public static final String FAILED_PREFIX = "[실패] ";
    public static final String REJECTED_PREFIX = "[거절] ";

    @Override
    public String model() {
        return MODEL;
    }

    @Override
    public TeamContextSummary infer(TeamContextInferenceRequest request) {
        String taskPrefix = request.event().taskId() + ":";
        Set<String> open = request.hints().unansweredQuestions().stream()
                .map(QuestionRef::reference)
                .collect(Collectors.toSet());
        List<Issue> issues = new ArrayList<>();
        for (Issue issue : request.previous().issues()) {
            boolean thisTask = issue.evidence().stream().anyMatch(reference -> reference.startsWith(taskPrefix));
            boolean questionIssue = issue.topic().startsWith(QUESTION_PREFIX);
            if (thisTask && questionIssue && issue.evidence().stream().noneMatch(open::contains)) {
                continue; // 답변이 달린 질문은 닫는다.
            }
            issues.add(issue);
        }
        for (QuestionRef question : request.hints().unansweredQuestions()) {
            if (issues.stream().noneMatch(issue -> issue.cites(question.reference()))) {
                issues.add(new Issue(QUESTION_PREFIX + question.text(),
                        List.of(new Position(name(question.authorPart()), "답변 대기")),
                        List.of(question.reference())));
            }
        }
        // taskStatus는 현재 상태라서 늦게 처리된 이전 이벤트에도 FAILED로 보인다.
        // 실패·거절 쟁점은 그 전이를 기록한 STATUS_CHANGED 이벤트 자체에서만 연다.
        String status = request.event().taskStatus();
        String text = request.event().text();
        boolean transition = "STATUS_CHANGED".equals(request.event().eventType())
                && text != null && text.contains("→ " + status);
        if (transition && ("FAILED".equals(status) || "REJECTED".equals(status))) {
            String reference = request.event().reference();
            if (issues.stream().noneMatch(issue -> issue.cites(reference))) {
                String prefix = "FAILED".equals(status) ? FAILED_PREFIX : REJECTED_PREFIX;
                issues.add(new Issue(prefix + text,
                        List.of(new Position(name(request.event().authorPart()), status)),
                        List.of(reference)));
            }
        }
        return new TeamContextSummary(request.previous().common(), request.previous().changed(), issues);
    }

    private static String name(AgentPart part) {
        return part == null ? AgentPart.OTHER.name() : part.name();
    }
}
