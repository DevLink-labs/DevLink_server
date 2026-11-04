package com.devlink.server.service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.team.Team;
import com.devlink.server.domain.teammember.MemberRole;
import com.devlink.server.domain.teammember.TeamMember;
import com.devlink.server.domain.user.User;
import com.devlink.server.dto.JoinTeamRequest;
import com.devlink.server.dto.TeamInviteResponse;
import com.devlink.server.dto.TeamJoinResponse;
import com.devlink.server.dto.TeamMemberResponse;
import com.devlink.server.dto.TeamResponse;
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
public class TeamService {
    private static final Logger log = LoggerFactory.getLogger(TeamService.class);
    private static final int MAX_INVITE_ATTEMPTS = 5;
    private final TeamRepository teams;
    private final TeamMemberRepository members;
    private final UserRepository users;
    private final TeamAuthorizationService authorization;
    private final InviteCodeGenerator inviteCodes;
    private final TransactionTemplate transaction;

    public TeamService(TeamRepository teams, TeamMemberRepository members, UserRepository users,
            TeamAuthorizationService authorization, InviteCodeGenerator inviteCodes,
            PlatformTransactionManager transactionManager) {
        this.teams = teams;
        this.members = members;
        this.users = users;
        this.authorization = authorization;
        this.inviteCodes = inviteCodes;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(readOnly = true)
    public List<TeamResponse> getMyTeams(Long userId) {
        requireUser(userId);
        List<TeamMember> memberships = members.findByUserIdOrderByJoinedAtDescIdDesc(userId);
        Map<Long, Team> teamsById = teams.findAllById(
                memberships.stream().map(TeamMember::getTeamId).toList()).stream()
                .collect(Collectors.toMap(Team::getId, Function.identity()));
        return memberships.stream().map(membership -> {
            Team team = teamsById.get(membership.getTeamId());
            if (team == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return TeamResponse.from(team, membership);
        }).toList();
    }

    @Transactional(readOnly = true)
    public TeamResponse getTeam(Long userId, Long teamId) {
        TeamMember membership = authorization.requireTeamMember(userId, teamId);
        Team team = teams.findById(teamId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return TeamResponse.from(team, membership);
    }

    @Transactional(readOnly = true)
    public List<TeamMemberResponse> getMembers(Long userId, Long teamId) {
        authorization.requireTeamMember(userId, teamId);
        List<TeamMember> memberships = members.findByTeamIdOrderByJoinedAtAscIdAsc(teamId);
        Map<Long, User> usersById = users.findAllById(
                memberships.stream().map(TeamMember::getUserId).toList()).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return memberships.stream().map(membership -> {
            User user = usersById.get(membership.getUserId());
            if (user == null) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            return TeamMemberResponse.from(user, membership);
        }).toList();
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TeamInviteResponse regenerateInvite(Long userId, Long teamId) {
        requireValidIds(userId, teamId);
        for (int attempt = 1; attempt <= MAX_INVITE_ATTEMPTS; attempt++) {
            try {
                return transaction.execute(status -> regenerateInviteCode(userId, teamId));
            } catch (DataIntegrityViolationException exception) {
                // 초대 코드 중복만 롤백 후 새 트랜잭션으로 재시도
                if (!isConstraintConflict(exception, "uk_teams_invite_code")) {
                    throw exception;
                }
                if (attempt == MAX_INVITE_ATTEMPTS) {
                    log.error("Invite regeneration failed after repeated code conflicts: userId={}, teamId={}, attempts={}",
                            userId, teamId, attempt);
                    throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
                }
                log.debug("Retrying invite regeneration after code conflict: userId={}, teamId={}, attempt={}/{}",
                        userId, teamId, attempt, MAX_INVITE_ATTEMPTS);
            }
        }
        throw new IllegalStateException("Invite code generation attempts exhausted");
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TeamJoinResponse join(Long userId, JoinTeamRequest request) {
        requireValidUserId(userId);
        if (request == null || request.inviteCode() == null || request.inviteCode().isBlank()
                || request.inviteCode().length() > 64) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        try {
            return transaction.execute(status -> joinTeam(userId, request.inviteCode()));
        } catch (MembershipConflict exception) {
            // 가입 중복 충돌은 롤백 완료 후 새 트랜잭션에서 기존 가입 정보로 복구
            log.debug("Recovering team membership after concurrent join: userId={}, teamId={}",
                    userId, exception.teamId);
            return transaction.execute(status -> recoverMembership(userId, exception));
        }
    }

    private TeamInviteResponse regenerateInviteCode(Long userId, Long teamId) {
        Team team = teams.findByIdForUpdate(teamId)
                .orElseThrow(() -> {
                    log.debug("Invite regeneration rejected: team not found, userId={}, teamId={}", userId, teamId);
                    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                });
        authorization.requireTeamOwner(userId, teamId);
        String code = generateReplacementCode(team.getInviteCode(), teamId);
        team.regenerateInviteCode(code);
        return TeamInviteResponse.from(teams.saveAndFlush(team));
    }

    private String generateReplacementCode(String currentCode, Long teamId) {
        for (int attempt = 0; attempt < MAX_INVITE_ATTEMPTS; attempt++) {
            String code = inviteCodes.generate();
            if (!code.equals(currentCode)) {
                return code;
            }
        }
        log.error("Invite regeneration failed: generated only the current code, teamId={}, attempts={}",
                teamId, MAX_INVITE_ATTEMPTS);
        throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
    }

    private TeamJoinResponse joinTeam(Long userId, String inviteCode) {
        requireUser(userId);
        Team team = teams.findByInviteCodeForUpdate(inviteCode)
                .orElseThrow(() -> {
                    log.debug("Team join rejected: invite code not found, userId={}", userId);
                    return new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                });
        var existing = members.findByTeamIdAndUserId(team.getId(), userId);
        if (existing.isPresent()) {
            log.debug("Team join reused existing membership: userId={}, teamId={}", userId, team.getId());
            return TeamJoinResponse.from(team, existing.get(), true);
        }
        try {
            TeamMember membership = members.saveAndFlush(
                    TeamMember.create(team.getId(), userId, MemberRole.MEMBER));
            return TeamJoinResponse.from(team, membership, false);
        } catch (DataIntegrityViolationException exception) {
            if (!isConstraintConflict(exception, "uk_team_members")) {
                throw exception;
            }
            throw new MembershipConflict(team.getId(), exception);
        }
    }

    private TeamJoinResponse recoverMembership(Long userId, MembershipConflict exception) {
        Team team = teams.findById(exception.teamId)
                .orElseThrow(() -> exception.original);
        TeamMember membership = members.findByTeamIdAndUserId(exception.teamId, userId)
                .orElseThrow(() -> exception.original);
        return TeamJoinResponse.from(team, membership, true);
    }

    private void requireUser(Long userId) {
        requireValidUserId(userId);
        if (!users.existsById(userId)) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private void requireValidUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
    }

    private void requireValidIds(Long userId, Long teamId) {
        requireValidUserId(userId);
        if (teamId == null || teamId <= 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
    }

    private boolean isConstraintConflict(DataIntegrityViolationException exception, String constraintName) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private static final class MembershipConflict extends RuntimeException {
        private final Long teamId;
        private final DataIntegrityViolationException original;

        private MembershipConflict(Long teamId, DataIntegrityViolationException original) {
            super(original);
            this.teamId = teamId;
            this.original = original;
        }
    }
}
