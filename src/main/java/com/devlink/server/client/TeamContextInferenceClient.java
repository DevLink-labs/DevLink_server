package com.devlink.server.client;

import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.exception.TeamContextInferenceException;

/**
 * 이전 요약과 새 이벤트로 다음 요약을 만든다. 구현은 호스팅 LLM(OpenAI 호환)이거나 구조 신호 전용이다.
 * 실패는 {@link TeamContextInferenceException}으로 알리고 갱신기가 Outbox 재시도로 처리한다.
 */
public interface TeamContextInferenceClient {
    /** 저장할 모델 식별자. 구조 전용 구현은 "structural"이다. */
    String model();

    TeamContextSummary infer(TeamContextInferenceRequest request);
}
