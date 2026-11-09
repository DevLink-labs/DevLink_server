package com.devlink.server.service;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.dto.CreateTeamRequest;
import com.devlink.server.dto.CreateTeamResponse;
import com.devlink.server.repository.TeamMemberRepository;
import com.devlink.server.repository.TeamRepository;
import com.devlink.server.repository.UserRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TeamCreationService {
    private static final Logger log = LoggerFactory.getLogger(TeamCreationService.class);
    private static final int MAX_CREATION_ATTEMPTS = 5;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final UserRepository users;
    private final InviteCodeGenerator inviteCodes;
    private final TransactionTemplate transaction;

    public TeamCreationService(TeamRepository teams, TeamMemberRepository members,
            UserRepository users, InviteCodeGenerator inviteCodes,
            PlatformTransactionManager transactionManager) {
        this.teams = teams;
        this.members = members;
        this.users = users;
        this.inviteCodes = inviteCodes;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreateTeamResponse create(Long userId, CreateTeamRequest request) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        for (int attempt = 1; attempt <= MAX_CREATION_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(status -> createTeam(userId, request));
            } catch (DataIntegrityViolationException exception) {
                // 초대 코드 충돌만 롤백 후 새 트랜잭션으로 재시도
                if (!isInviteCodeConflict(exception)) {
                    throw exception;
                }
                if (attempt == MAX_CREATION_ATTEMPTS) {
                    log.error("Team creation failed after repeated invite code conflicts: userId={}, attempts={}",
                            userId, attempt);
                    throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
                }
                log.debug("Retrying team creation after invite code conflict: userId={}, attempt={}/{}",
                        userId, attempt, MAX_CREATION_ATTEMPTS);
            }
        }
        throw new IllegalStateException("Team creation attempts exhausted");
    }

    private CreateTeamResponse createTeam(Long userId, CreateTeamRequest request) {
        if (!users.existsById(userId)) {
            log.debug("Team creation rejected: account not found, userId={}", userId);
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        Team team = teams.saveAndFlush(Team.create(request.name(), inviteCodes.generate()));
        TeamMember owner = members.saveAndFlush(TeamMember.create(team.getId(), userId, MemberRole.OWNER));
        return CreateTeamResponse.from(team, owner);
    }

    private boolean isInviteCodeConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_teams_invite_code".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }
}
