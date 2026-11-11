package com.devlink.server.repository;

import java.time.LocalDateTime;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;
import com.devlink.server.domain.taskevent.TaskEvent;
import com.devlink.server.domain.taskevent.TaskEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskEventRepository extends JpaRepository<TaskEvent, Long> {
    @Query("""
            select e from TaskEvent e
            where e.taskId = :taskId and e.eventType in :eventTypes and e.sequence > :afterSequence
            order by e.sequence asc
            """)
    List<TaskEvent> findEventsAfterByTypes(@Param("taskId") Long taskId,
            @Param("eventTypes") List<TaskEventType> eventTypes,
            @Param("afterSequence") Long afterSequence, Pageable pageable);

    Optional<TaskEvent> findFirstByTaskIdAndEventTypeInOrderByIdDesc(
            Long taskId, List<TaskEventType> eventTypes);
    boolean existsByTaskIdAndEventType(Long taskId, TaskEventType eventType);

    List<TaskEvent> findByTaskIdOrderByCreatedAtAsc(Long taskId);

    List<TaskEvent> findByTaskIdOrderBySequenceAsc(Long taskId);

    Optional<TaskEvent> findByTaskIdAndAuthorAgentIdAndRequestKey(
            Long taskId, Long authorAgentId, String requestKey);

 // 지정한 이벤트 유형 중 최신 이벤트 조회
    @Query("""
            select e
            from TaskEvent e
            where e.taskId = :taskId
              and e.eventType in :eventTypes
            order by e.sequence desc
            """)
    List<TaskEvent> findLatestEventsByTypes(
            @Param("taskId") Long taskId,
            @Param("eventTypes") List<TaskEventType> eventTypes,
            Pageable pageable
    );

 // 지정한 이벤트 유형 중 기준 순번 이전 이벤트 조회
    @Query("""
            select e
            from TaskEvent e
            where e.taskId = :taskId
              and e.eventType in :eventTypes
              and e.sequence < :beforeSequence
            order by e.sequence desc
            """)
    List<TaskEvent> findEventsBeforeByTypes(
            @Param("taskId") Long taskId,
            @Param("eventTypes") List<TaskEventType> eventTypes,
            @Param("beforeSequence") Long beforeSequence,
            Pageable pageable
    );
    
    //최초 조회. 해당 작업의 최신 이벤트부터 조회
    @Query("""
            select e
            from TaskEvent e
            where e.taskId = :taskId
            order by e.sequence desc
            """)
    List<TaskEvent> findLatestEvents(
            @Param("taskId") Long taskId,
            Pageable pageable
    );
    
    //이전 이력 조회. 기존 순번보다 작은 이벤트 중 최신부터 조회
    @Query("""
            select e
            from TaskEvent e
            where e.taskId = :taskId
              and e.sequence < :beforeSequence
            order by e.sequence desc
            """)
    List<TaskEvent> findEventsBefore(
            @Param("taskId") Long taskId,
            @Param("beforeSequence") Long beforeSequence,
            Pageable pageable
    );
    
    interface LatestEventTime {
        Long getTaskId();
        TaskEventType getEventType();
        LocalDateTime getLatestAt();
    }

    @Query("""
            select e.taskId as taskId,
                   e.eventType as eventType,
                   max(e.createdAt) as latestAt
            from TaskEvent e
            where e.taskId in :taskIds
              and e.eventType in :eventTypes
            group by e.taskId, e.eventType
            """)
    List<LatestEventTime> findLatestEventTimes(
            @Param("taskIds") List<Long> taskIds,
            @Param("eventTypes") List<TaskEventType> eventTypes);
}
