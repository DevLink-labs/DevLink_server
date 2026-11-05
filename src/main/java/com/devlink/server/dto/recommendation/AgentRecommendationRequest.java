package com.devlink.server.dto.recommendation;

import com.devlink.server.common.StrictStringDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

/**
 * 담당 Agent 추천 요청. 두 필드 모두 JSON 문자열만 허용하며, question의 공백·길이는 코드포인트 기준으로 서비스에서 검증한다.
 * repoKey는 요청자 브로커의 즉시 확인 저장소 힌트이며 중앙 검색 범위를 제한하지 않는다.
 */
public record AgentRecommendationRequest(
        @JsonDeserialize(using = StrictStringDeserializer.class) String question,
        @JsonDeserialize(using = StrictStringDeserializer.class) String repoKey) {
}
