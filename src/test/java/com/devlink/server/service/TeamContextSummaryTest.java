package com.devlink.server.service;

import java.util.Arrays;
import java.util.List;
import com.devlink.server.dto.teamcontext.TeamContextSummary;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Changed;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Common;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Issue;
import com.devlink.server.dto.teamcontext.TeamContextSummary.Position;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class TeamContextSummaryTest {
    @Test
    void normalizationDropsBlankItemsClipsTextAndCapsCounts() {
        TeamContextSummary raw = new TeamContextSummary(
                List.of(new Common("  ", List.of("7:1")), new Common("a".repeat(50), Arrays.asList(" 7:1 ", null, "7:2", "7:1"))),
                List.of(new Changed(null, "after", null), new Changed("before", " ", List.of())),
                List.of(new Issue("topic", List.of(new Position(" backend ", "x"), new Position(null, "y")), null),
                        new Issue(null, List.of(), List.of())));
        TeamContextSummary normalized = raw.normalized(1, 10);
        assertThat(normalized.common()).singleElement().satisfies(item -> {
            assertThat(item.statement()).hasSize(10);
            assertThat(item.evidence()).containsExactly("7:1");
        });
        assertThat(normalized.changed()).singleElement().satisfies(item -> {
            assertThat(item.before()).isEmpty();
            assertThat(item.after()).isEqualTo("after");
        });
        assertThat(normalized.issues()).singleElement().satisfies(item -> {
            assertThat(item.topic()).isEqualTo("topic");
            assertThat(item.positions()).containsExactly(new Position("BACKEND", "x"));
            assertThat(item.cites("7:9")).isFalse();
        });
    }

    @Test
    void roundTripsThroughJsonIgnoringUnknownFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        TeamContextSummary summary = mapper.readValue("""
                {"common":[{"statement":"s","evidence":["7:1"],"extra":1}],"changed":[],"issues":[
                  {"topic":"t","positions":[{"part":"BACKEND","position":"p"}],"evidence":["7:2"],"open_since":"x"}],
                 "unknown":true}
                """, TeamContextSummary.class);
        assertThat(summary.common()).containsExactly(new Common("s", List.of("7:1")));
        assertThat(summary.issues().getFirst().cites("7:2")).isTrue();
        assertThat(mapper.readValue(mapper.writeValueAsString(summary), TeamContextSummary.class)).isEqualTo(summary);
    }
}
