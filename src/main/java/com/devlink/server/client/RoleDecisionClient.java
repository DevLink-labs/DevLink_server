package com.devlink.server.client;

import java.util.List;
import java.util.Set;

/** 질문이 역할 태그의 담당 범위에 드는지 결정 모델에 묻는다. 토큰 일치를 대체하지 않고 일치를 추가만 한다. */
public interface RoleDecisionClient {
    /** false면 호출하지 않는다. 추천 서비스가 태그를 모으는 조회도 생략한다. */
    boolean enabled();

    /**
     * 주어진 태그 중 모델이 일치로 판정한 태그를 반환한다.
     * 호출 실패·시간 초과·형식 오류는 예외 없이 빈 집합이다.
     */
    Set<String> matchedTags(String question, List<String> tags);

    /** 기능이 꺼져 있거나 키가 없을 때 쓰는 구현이다. 외부 호출을 하지 않는다. */
    static RoleDecisionClient disabled() {
        return new RoleDecisionClient() {
            @Override
            public boolean enabled() {
                return false;
            }

            @Override
            public Set<String> matchedTags(String question, List<String> tags) {
                return Set.of();
            }
        };
    }
}
