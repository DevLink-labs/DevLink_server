package com.devlink.server.repository;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.task.Task;
import org.springframework.data.jpa.repository.JpaRepository;
import com.devlink.server.domain.task.TaskStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;


public interface TaskRepository extends JpaRepository<Task, Long> {
    boolean existsByRequesterAgentIdOrReceiverAgentId(Long requesterAgentId, Long receiverAgentId);

    Optional<Task> findByRequesterAgentIdAndRequestKey(Long requesterAgentId, String requestKey);

    List<Task> findByReceiverAgentIdAndStatusAndDeliveredAtIsNullOrderByIdAsc(
            Long receiverAgentId, TaskStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Task t where t.id = :taskId")
    Optional<Task> findByIdForUpdate(@Param("taskId") Long taskId);

    List<Task> findByTeamIdOrderByCreatedAtDesc(Long teamId);

    @Query("""
            select task
            from Task task
            where task.teamId = :teamId
              and (:status is null or task.status = :status)
            order by task.createdAt desc, task.id desc
            """)
    Page<Task> findTeamTasks(
    		@Param("teamId")Long teamId,
    		@Param("status")TaskStatus status,
    		Pageable pageable);


    @Query("""
            select task
            from Task task
            where task.teamId = :teamId
              and (:status is null or task.status = :status)
              and (
                  (:direction = 'sent'
                      and task.requesterAgentId in (:agentIds))
                  or
                  (:direction = 'received'
                      and task.receiverAgentId in (:agentIds))
                  or
                  (:direction = 'all'
                      and (
                          task.requesterAgentId in (:agentIds)
                          or task.receiverAgentId in (:agentIds)
                      ))
              )
            order by task.createdAt desc, task.id desc
            """)
    Page<Task> findAgentTasks(
            @Param("teamId") Long teamId,
            @Param("agentIds") List<Long> agentIds,
            @Param("direction") String direction,
            @Param("status") TaskStatus status,
            Pageable pageable
    );

}
