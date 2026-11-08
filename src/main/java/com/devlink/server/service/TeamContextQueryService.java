package com.devlink.server.service;

import java.util.List;
import java.util.Locale;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.AgentPrincipal;
import com.devlink.server.domain.agent.AgentPart;
import com.devlink.server.domain.teamcontext.TeamPartContext;
import com.devlink.server.dto.teamcontext.TeamContextResponse;
import com.devlink.server.dto.teamcontext.TeamPartContextView;
import com.devlink.server.repository.TeamPartContextRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 저장된 최신 요약을 읽기만 한다. 읽을 때 추론하지 않으므로 응답 지연은 조회 한 번이다. */
@Service
@RequiredArgsConstructor
public class TeamContextQueryService {
    private final TeamPartContextRepository contexts;
    private final TeamAuthorizationService authorization;

    @Transactional(readOnly = true)
    public TeamContextResponse get(Long teamId, AgentPrincipal principal, String part, String counterpart) {
        if (principal == null || principal.agentId() <= 0 || principal.ownerUserId() <= 0 || principal.teamId() <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (principal.teamId() != teamId) {
            throw new BusinessException(ErrorCode.ACCESS_DENIED);
        }
        authorization.requireTeamMember(principal.ownerUserId(), teamId);
        AgentPart own = parsePart(part);
        if (counterpart != null && !counterpart.isBlank()) {
            AgentPart other = parsePart(counterpart);
            AgentPart[] pair = TeamPartContext.pair(own, other);
            TeamPartContextView view = contexts
                    .findFirstByTeamIdAndPartAAndPartBOrderByVersionDesc(teamId, pair[0], pair[1])
                    .map(context -> TeamPartContextView.from(context, other))
                    .orElseGet(() -> TeamPartContextView.empty(other));
            return new TeamContextResponse(teamId, own, List.of(view));
        }
        List<TeamPartContextView> views = contexts.findLatestByTeamIdAndPart(teamId, own).stream()
                .map(context -> TeamPartContextView.from(context,
                        context.getPartA() == own ? context.getPartB() : context.getPartA()))
                .toList();
        return new TeamContextResponse(teamId, own, views);
    }

    private static AgentPart parsePart(String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return AgentPart.valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }
}
