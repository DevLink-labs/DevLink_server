package com.devlink.server.dto.teamcontext;

import java.util.List;
import com.devlink.server.domain.agent.AgentPart;

/** 증분 갱신 한 번의 입력이다. 이전 요약과 새 이벤트, 그리고 구조·엔티티 힌트를 함께 넘긴다. */
public record TeamContextInferenceRequest(Long teamId, AgentPart partA, AgentPart partB, long previousVersion,
        TeamContextSummary previous, NewEvent event, Hints hints) {

    /** 새로 반영할 이벤트. {@code reference}는 evidence에 쓰는 "taskId:eventId"다. */
    public record NewEvent(Long taskId, Long eventId, String reference, String eventType, AgentPart authorPart,
            String taskStatus, String taskSummary, String text) { }

    /**
     * 구조 신호와 추출 엔티티. 쟁점의 정의가 아니라 추론이 참고할 근거다.
     * {@code unansweredQuestions}는 이 작업에서 아직 답변이 없는 질문이다.
     */
    public record Hints(List<String> entities, List<QuestionRef> unansweredQuestions, int questionCount,
            int answerCount, boolean taskFailedOrRejected) {
        public Hints {
            entities = entities == null ? List.of() : List.copyOf(entities);
            unansweredQuestions = unansweredQuestions == null ? List.of() : List.copyOf(unansweredQuestions);
        }
    }

    public record QuestionRef(String reference, AgentPart authorPart, String text) { }
}
