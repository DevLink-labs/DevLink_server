package com.devlink.server.service;


import java.util.List;
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

@Service
public class TaskCreationService {
    private static final Logger log = LoggerFactory.getLogger(TaskCreationService.class);
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
        Creation creation;
        try {
            // 트랜잭션 시작 → Task(PENDING) + TaskEvent(CREATED) 저장 → 커밋
            creation = transaction.execute(tx -> createTask(teamId, agentId, request));
        } catch (DataIntegrityViolationException exception) {
            // 요청 키 유니크 제약 위반만 복구한다. 다른 제약 오류는 중복 요청으로 보지 않는다.
            if (!isRequestKeyConflict(exception)) {
                throw exception;
            }
            // 패배한 생성 트랜잭션은 완전히 롤백됐다. 새 트랜잭션에서 승자의 작업을 조회·비교한다.
            creation = transaction.execute(tx -> recoverReplay(teamId, agentId, request, exception));
        }

        Task task = creation.task();
        if (creation.replayed()) {
            TaskDeliveryResult delivery = findLatestDelivery(task.getId());
            return CreateTaskResponse.of(task, true, delivery);
        }

        return deliverTask(task);
    }

    /** 생성 커밋 이후 전송과 이력 저장의 실패를 구분한다. ACK는 기다리지 않는다. */
    private CreateTaskResponse deliverTask(Task task) {
        TaskRoutingResult routing;

        // 1. 브로커 전송
        try {
            routing = taskRoutingService.sendTask(task.getId());
        } catch (RuntimeException exception) {
            log.error("Task routing failed after creation: taskId={}", task.getId(), exception);
            return CreateTaskResponse.of(task, false,
                    new TaskDeliveryResult(TaskEventType.DELIVERY_UNKNOWN, "ROUTING_ERROR"));
        }

        // 2. 전송 결과 routing을 받아서 Task, TaskEvent upsert
        try {
            task = saveDeliveryEvent(task, routing);
        } catch (RuntimeException exception) {
            log.error("Failed to record delivery history: taskId={}", task.getId(), exception);
            return CreateTaskResponse.of(task, false,
                    new TaskDeliveryResult(TaskEventType.DELIVERY_UNKNOWN, "HISTORY_RECORD_FAILED"));
        }
        return CreateTaskResponse.of(task, false, findLatestDelivery(task.getId()));
    }

    /** 전송 결과를 기록한다. deliveredAt은 유효한 ACK를 저장할 때만 변경한다. */
    private Task saveDeliveryEvent(Task task, TaskRoutingResult routing) {
        return transaction.execute(tx -> {
            Task savedTask = taskRepository.findByIdForUpdate(task.getId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
            if (taskEventRepository.existsByTaskIdAndEventType(
                    task.getId(), TaskEventType.ACK_RECEIVED)) {
                return savedTask;
            }
            TaskEventType eventType = switch (routing.status()) {
                case SENT
                    -> TaskEventType.ACK_PENDING;

                case RECEIVER_OFFLINE, CONNECTION_UNAVAILABLE, CONNECTION_REPLACED, TASK_NOT_PENDING
                    -> TaskEventType.DELIVERY_NOT_SENT;

                case TRANSPORT_ERROR
                    -> TaskEventType.DELIVERY_UNKNOWN;
            };

            // ACK뿐 아니라 타임아웃·연결 종료가 먼저 기록된 경우도 보호한다.
            if (eventType == TaskEventType.ACK_PENDING && taskEventRepository
                    .findFirstByTaskIdAndEventTypeInOrderByIdDesc(task.getId(), DELIVERY_EVENT_TYPES)
                    .isPresent()) {
                return savedTask;
            }
            taskEventRepository.save(TaskEvent.of(task.getId(), eventType,
                    routing.status() == Status.SENT ? null : routing.status().name()));
            return savedTask;
        });
    }

    private TaskDeliveryResult findLatestDelivery(Long taskId) {
        try {
            return transaction.execute(tx -> taskEventRepository
                    .findFirstByTaskIdAndEventTypeInOrderByIdDesc(taskId, DELIVERY_EVENT_TYPES)
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

    private Creation createTask(Long teamId, Long agentId, CreateTaskRequest request) {
        var requester = agentRepository.findById(agentId)
                .filter(agent -> agent.getTeamId().equals(teamId))
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        Long userId = requester.getOwnerUserId();
        // 1. 요청 Agent + 요청 키로 생성된 작업 찾기 (유니크 제약과 같은 범위)
        var existing = taskRepository.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey());
        // 2. 기존 작업이 있으면 요청 내용 비교 (요청 Agent는 조회 조건이므로 비교 불필요)
        if (existing.isPresent()) {
            // 2-1. 기존 작업이 있는데 내용이 다른 경우 요청키를 잘못 재사용한것
            requireSamePayload(existing.get(), teamId, request);
            return new Creation(existing.get(), true);
        }

        // 3. 신규 요청인 경우 수신 Agent 확인
        requireReceiver(teamId, request.receiverAgentId());
        // 3-1. Task(새 작업) 저장
        Task task = taskRepository.save(Task.create(request.requestKey(), teamId,
                agentId, userId, request.receiverAgentId(), request.prompt()));
        // 3-2. TaskEvent(생성 이력) 저장
        taskEventRepository.save(TaskEvent.of(task.getId(), TaskEventType.CREATED, null));
        return new Creation(task, false);
    }

    /**
     * INSERT 경합에서 진 요청을 처리한다. 아무것도 쓰지 않고 승자의 작업만 읽는다.
     * PostgreSQL은 승자가 커밋해야 제약 위반을 돌려주므로 재조회는 승자를 찾는다.
     * 그 사이 행이 사라진 경우는 분류할 수 없으므로 원래 예외를 그대로 던진다.
     */
    private Creation recoverReplay(Long teamId, Long agentId, CreateTaskRequest request,
            DataIntegrityViolationException original) {
        Task task = taskRepository.findByRequesterAgentIdAndRequestKey(agentId, request.requestKey())
                .orElseThrow(() -> original);
        requireSamePayload(task, teamId, request);
        return new Creation(task, true);
    }

    /** 정상 경로의 재조회와 경합 복구가 같은 비교 규칙을 쓴다. 요청 Agent는 조회 조건이므로 비교하지 않는다. */
    private void requireSamePayload(Task task, Long teamId, CreateTaskRequest request) {
        if (!task.getTeamId().equals(teamId)
                || !task.getReceiverAgentId().equals(request.receiverAgentId())
                || !task.getPrompt().equals(request.prompt())) {
            throw new BusinessException(ErrorCode.REQUEST_KEY_CONFLICT);
        }
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
        // 재접수는 위에서 기존 작업을 반환한다. 새 작업에 대해서만 저장 전에 검사한다.
        taskRoutingService.requireReceiverAvailable(agent);
    }

    private record Creation(Task task, boolean replayed) { }
}
