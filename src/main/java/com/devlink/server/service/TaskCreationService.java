package com.devlink.server.service;


import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.domain.task.Task;
import com.devlink.server.dto.TaskRoutingResult;
import com.devlink.server.dto.TaskRoutingResult.Status;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import com.devlink.server.dto.CreateTaskRequest;
import com.devlink.server.dto.CreateTaskResponse;
import com.devlink.server.dto.TaskDeliveryResult;
import com.devlink.server.repository.AgentRepository;
import com.devlink.server.repository.TaskEventRepository;
import com.devlink.server.repository.TaskRepository;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.WebSocketSession;

@Service
public class TaskCreationService {
    private static final Logger log = LoggerFactory.getLogger(TaskCreationService.class);
    private static final int MAX_SUMMARY_CODE_POINTS = 1000;
    private static final List<TaskEventType> DELIVERY_EVENT_TYPES = List.of(
            TaskEventType.ACK_PENDING, TaskEventType.ACK_RECEIVED,
            TaskEventType.DELIVERY_UNKNOWN, TaskEventType.DELIVERY_NOT_SENT);
    private final TaskRepository taskRepository;
    private final TaskEventRepository taskEventRepository;
    private final AgentRepository agentRepository;
    private final TaskRoutingService taskRoutingService;
    private final TransactionTemplate transaction;

    public TaskCreationService(TaskRepository taskRepository, TaskEventRepository taskEventRepository,
            AgentRepository agentRepository, TaskRoutingService taskRoutingService,
            PlatformTransactionManager transactionManager) {
        this.taskRepository = taskRepository;
        this.taskEventRepository = taskEventRepository;
        this.agentRepository = agentRepository;
        this.taskRoutingService = taskRoutingService;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public CreateTaskResponse create(Long teamId, Long agentId, CreateTaskRequest request) {
        // 형식 오류는 DB 작업 전에 거른다. 정규화한 값을 저장·재시도 비교에 모두 쓴다.
        String taskSummary = normalizeSummary(request.taskSummary());
        Creation creation;
        try {
            // 트랜잭션 시작 → Task(PENDING) + TaskEvent(CREATED) 저장 → 커밋
            creation = transaction.execute(tx -> createTask(teamId, agentId, request, taskSummary));
        } catch (DataIntegrityViolationException exception) {
            // 요청 키 유니크 제약 위반만 복구한다. 다른 제약 오류는 중복 요청으로 보지 않는다.
            if (!isRequestKeyConflict(exception)) {
                throw exception;
            }
            // 패배한 생성 트랜잭션은 완전히 롤백됐다. 새 트랜잭션에서 승자의 작업을 조회·비교한다.
            creation = transaction.execute(tx -> recoverReplay(teamId, agentId, request, taskSummary, exception));
        }

        Task task = creation.task();
        if (creation.replayed()) {
            TaskDeliveryResult delivery = findLatestDelivery(task.getId());
            return CreateTaskResponse.of(task, true, delivery);
        }

        return deliverTask(task, null);
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void retryUndelivered(long receiverAgentId, WebSocketSession session) {
        for (Task task : taskRoutingService.findUndeliveredTasks(receiverAgentId)) {
            deliverTask(task, session);
        }
    }

    /** 작업 저장 커밋 이후 전송과 이력 저장의 실패를 구분한다. ACK는 기다리지 않는다. */
    private CreateTaskResponse deliverTask(Task task, WebSocketSession expectedSession) {
        TaskRoutingResult routing;
        long previousDeliveryId;

        // 1. 브로커 전송
        try {
            previousDeliveryId = latestDeliveryEvent(task.getId()).map(TaskEvent::getId).orElse(0L);
            routing = expectedSession == null
                    ? taskRoutingService.sendTask(task.getId())
                    : taskRoutingService.sendTask(task.getId(), expectedSession);
        } catch (RuntimeException exception) {
            log.error("Task routing failed: taskId={}", task.getId(), exception);
            return CreateTaskResponse.of(task, false,
                    new TaskDeliveryResult(TaskEventType.DELIVERY_UNKNOWN, "ROUTING_ERROR"));
        }

        // 2. 전송 결과 routing을 받아서 Task, TaskEvent upsert
        try {
            task = saveDeliveryEvent(task, routing, previousDeliveryId);
        } catch (RuntimeException exception) {
            log.error("Failed to record delivery history: taskId={}", task.getId(), exception);
            return CreateTaskResponse.of(task, false,
                    new TaskDeliveryResult(TaskEventType.DELIVERY_UNKNOWN, "HISTORY_RECORD_FAILED"));
        }
        TaskDeliveryResult delivery = findLatestDelivery(task.getId());
        // 다른 경로가 전송 후 이력을 저장하기 전이어도 ACK 대기 상태를 응답한다.
        if (delivery == null && routing.status() == Status.ACK_IN_PROGRESS) {
            delivery = new TaskDeliveryResult(TaskEventType.ACK_PENDING, null);
        }
        return CreateTaskResponse.of(task, false, delivery);
    }

    /** 전송 결과를 기록한다. deliveredAt은 유효한 ACK를 저장할 때만 변경한다. */
    private Task saveDeliveryEvent(Task task, TaskRoutingResult routing, long previousDeliveryId) {
        return transaction.execute(tx -> {
            Task savedTask = taskRepository.findByIdForUpdate(task.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            // 교체된 연결의 결과로 현재 연결의 전달 이력 뒤에 미전송 이력을 추가하지 않는다.
            if (routing.status() == Status.CONNECTION_REPLACED) {
                return savedTask;
            }
            if (savedTask.getDeliveredAt() != null || taskEventRepository
                    .existsByTaskIdAndEventType(task.getId(), TaskEventType.ACK_RECEIVED)) {
                return savedTask;
            }
            TaskEventType eventType = switch (routing.status()) {
                case SENT
                    -> TaskEventType.ACK_PENDING;

                case RECEIVER_OFFLINE, CONNECTION_UNAVAILABLE, CONNECTION_REPLACED, TASK_NOT_PENDING
                    -> TaskEventType.DELIVERY_NOT_SENT;

                case TRANSPORT_ERROR
                    -> TaskEventType.DELIVERY_UNKNOWN;

                case ALREADY_DELIVERED, ACK_IN_PROGRESS -> null;
            };

            if (eventType == null) {
                return savedTask;
            }
            // ACK가 이미 처리된 시도나 다른 경로에서 더 최근 결과를 남긴 경우를 건너뛴다.
            boolean resultSuperseded = routing.status() == Status.SENT
                    ? !taskRoutingService.isAwaitingAck(routing)
                    : latestDeliveryEvent(task.getId()).filter(event -> event.getId() > previousDeliveryId).isPresent();
            if (resultSuperseded) {
                return savedTask;
            }
            taskEventRepository.save(TaskEvent.of(task.getId(), eventType,
                    routing.status() == Status.SENT ? null : routing.status().name()));
            return savedTask;
        });
    }

    private Optional<TaskEvent> latestDeliveryEvent(Long taskId) {
        return taskEventRepository.findFirstByTaskIdAndEventTypeInOrderByIdDesc(taskId, DELIVERY_EVENT_TYPES);
    }

    private TaskDeliveryResult findLatestDelivery(Long taskId) {
        try {
            return transaction.execute(tx -> latestDeliveryEvent(taskId)
                    .map(event -> new TaskDeliveryResult(event.getEventType(),
                            switch (event.getEventType()) {
                                case ACK_PENDING, ACK_RECEIVED -> null;
                                default -> event.getContent();
                            }))
                    .orElse(null));
        } catch (RuntimeException exception) {
            log.error("Failed to read delivery history: taskId={}", taskId, exception);
            return new TaskDeliveryResult(TaskEventType.DELIVERY_UNKNOWN, "HISTORY_READ_FAILED");
        }
    }

    private Creation createTask(Long teamId, Long agentId, CreateTaskRequest request, String taskSummary) {
        var requester = agentRepository.findById(agentId)
                .filter(agent -> agent.getTeamId().equals(teamId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Long userId = requester.getOwnerUserId();
        // 1. 요청 Agent + 요청 키로 생성된 작업 찾기 (유니크 제약과 같은 범위)
        var existing = taskRepository.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey());
        // 2. 기존 작업이 있으면 요청 내용 비교 (요청 Agent는 조회 조건이므로 비교 불필요)
        if (existing.isPresent()) {
            // 2-1. 기존 작업이 있는데 내용이 다른 경우 요청키를 잘못 재사용한것
            requireSamePayload(existing.get(), teamId, request, taskSummary);
            return new Creation(existing.get(), true);
        }

        // 3. 신규 요청인 경우 수신 Agent 확인
        requireReceiver(teamId, request.receiverAgentId());
        // 3-1. Task(새 작업) 저장
        Task task = taskRepository.save(Task.create(request.requestKey(), teamId,
                agentId, userId, request.receiverAgentId(), request.prompt(), taskSummary));
        // 3-2. TaskEvent(생성 이력) 저장
        taskEventRepository.save(TaskEvent.of(task.getId(), TaskEventType.CREATED, null));
        return new Creation(task, false);
    }

    /**
     * INSERT 경합에서 진 요청을 처리한다. 아무것도 쓰지 않고 승자의 작업만 읽는다.
     * PostgreSQL은 승자가 커밋해야 제약 위반을 돌려주므로 재조회는 승자를 찾는다.
     * 그 사이 행이 사라진 경우는 분류할 수 없으므로 원래 예외를 그대로 던진다.
     */
    private Creation recoverReplay(Long teamId, Long agentId, CreateTaskRequest request, String taskSummary,
            DataIntegrityViolationException original) {
        Task task = taskRepository.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey())
                .orElseThrow(() -> original);
        requireSamePayload(task, teamId, request, taskSummary);
        return new Creation(task, true);
    }

    /**
     * 정상 경로의 재조회와 경합 복구가 같은 비교 규칙을 쓴다. 요청 Agent는 조회 조건이므로 비교하지 않는다.
     * 요약은 정규화한 값끼리 비교하므로 앞뒤 공백만 다른 재시도는 같은 요청이다.
     */
    private void requireSamePayload(Task task, Long teamId, CreateTaskRequest request, String taskSummary) {
        if (!task.getTeamId().equals(teamId)
                || !task.getReceiverAgentId().equals(request.receiverAgentId())
                || !task.getPrompt().equals(request.prompt())
                || !Objects.equals(task.getTaskSummary(), taskSummary)) {
            throw new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT);
        }
    }

    /** 누락은 null로 두고, 값이 있으면 앞뒤 공백을 제거한 뒤 1~1,000 코드포인트만 허용한다. */
    private static String normalizeSummary(String raw) {
        if (raw == null) {
            return null;
        }
        String summary = raw.strip();
        int codePoints = summary.codePointCount(0, summary.length());
        if (codePoints < 1 || codePoints > MAX_SUMMARY_CODE_POINTS) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return summary;
    }

    // DataIntegrityViolationException 중 요청 키 경합인지 확인
    private boolean isRequestKeyConflict(DataIntegrityViolationException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation
                    && "uk_tasks_request_key".equals(violation.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    private void requireReceiver(Long teamId, Long agentId) {
        // 1. 수신 Agent가 존재하는가?
        var agent = agentRepository.findById(agentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        // 2. URL로 받은 팀과 같은가?
        if (!agent.getTeamId().equals(teamId)) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
    }

    private record Creation(Task task, boolean replayed) { }
}
