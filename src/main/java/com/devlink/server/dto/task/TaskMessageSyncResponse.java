package com.devlink.server.dto.task;

import java.util.List;

/** cursor는 로컬 저장 완료 위치이며 Agent의 읽음·업무 처리 완료를 뜻하지 않는다. */
public record TaskMessageSyncResponse(List<TaskMessageItem> items, String nextCursor, boolean hasNext) {
    public TaskMessageSyncResponse {
        items = List.copyOf(items);
    }
}
