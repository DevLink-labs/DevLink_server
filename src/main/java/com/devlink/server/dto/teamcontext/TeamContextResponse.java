package com.devlink.server.dto.teamcontext;

import java.util.List;
import com.devlink.server.domain.agent.AgentPart;

/** 요청한 파트 기준으로 상대 파트별 최신 요약을 돌려준다. counterpart를 지정하면 한 항목이다. */
public record TeamContextResponse(Long teamId, AgentPart part, List<TeamPartContextView> contexts) {
    public TeamContextResponse {
        contexts = contexts == null ? List.of() : List.copyOf(contexts);
    }
}
