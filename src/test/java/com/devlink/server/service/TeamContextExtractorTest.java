package com.devlink.server.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TeamContextExtractorTest {
    private final TeamContextExtractor extractor = new TeamContextExtractor();

    @Test
    void extractsPathsIdentifiersStatusesAndCodesInOrderWithoutDuplicates() {
        List<String> entities = extractor.extract(
                "작업 상세는 /api/v1/tasks/{id} 를 쓰고 src/main/java/com/devlink/server/dto/AgentResponse.java 를 고쳐야 해.",
                "`delivery` 필드가 null이면 HTTP 404 대신 빈 200 응답을 주는지? REQUEST_KEY_CONFLICT 로 거부됨. /api/v1/tasks/{id} 다시 확인.");
        assertThat(entities).containsExactly(
                "/api/v1/tasks/{id}",
                "src/main/java/com/devlink/server/dto/AgentResponse.java",
                "delivery",
                "HTTP 404",
                "HTTP 200",
                "REQUEST_KEY_CONFLICT");
    }

    @Test
    void ignoresPlainNumbersBlankInputAndNull() {
        assertThat(extractor.extract(null, "   ", "파일 100개를 바꿨고 2026년에 300줄을 지웠다")).isEmpty();
    }

    @Test
    void capsTheNumberOfEntities() {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 100; index++) {
            text.append("CODE_").append(index).append(' ');
        }
        assertThat(extractor.extract(text.toString())).hasSize(TeamContextExtractor.MAX_ENTITIES);
    }
}
