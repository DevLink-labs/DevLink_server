package com.devlink.server.prompt;

import com.devlink.server.dto.teamcontext.TeamContextInferenceRequest;
import com.devlink.server.exception.TeamContextInferenceException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** 시스템 프롬프트와 고정 출력 스키마. 프롬프트를 바꾸면 설정의 prompt-version도 올려 버전별 비교가 가능하게 한다. */
public final class TeamContextPrompt {
    public static final String SYSTEM = """
            너는 한 개발 팀의 중앙 서버에서 두 파트(예: FRONTEND와 BACKEND) 사이의 작업 요청·질문·답변을 지켜보며 \
            세 가지 요약을 유지하는 관찰자다. 사용자 메시지는 JSON이며 previous(이전 요약), event(새 이벤트), \
            hints(구조 신호와 추출 엔티티)를 담는다. 이전 요약에 새 이벤트를 반영한 다음 요약을 JSON으로만 출력한다.

            정의:
            - common: 두 파트가 반복해서 전제로 삼는 사실이나 합의. 한 번 언급된 것은 넣지 않는다.
            - changed: 이전 진술을 뒤집었거나 두 파트가 서로 다르게 말하는 지점. before는 이전 진술, after는 새 진술이다.
            - issues: changed 중 아직 합의되지 않은 것과 지금 부딪히는 지점. positions에는 파트별 입장을 적는다. \
            합의된 쟁점은 issues에서 빼고 필요하면 common이나 changed로 옮긴다.

            규칙:
            - 증분 갱신이다. 이전 요약을 그대로 옮기되 새 이벤트가 바꾸는 항목만 추가·수정·삭제한다.
            - 모든 항목의 evidence에는 근거가 된 "taskId:eventId" 좌표를 넣는다. 새 이벤트의 좌표는 event.reference다. \
            좌표를 지어내지 않는다.
            - hints는 참고 자료다. 미답변 질문·실패·거절은 쟁점일 가능성이 높지만, 답변이 끝났어도 양쪽 생각이 다르면 쟁점이다.
            - 문장은 한국어로 짧게 쓰고 코드 식별자·경로·상태 코드는 원문 그대로 둔다.
            - 각 배열은 20개를 넘기지 않는다. 오래되고 덜 중요한 항목부터 뺀다.
            - 출력은 스키마에 맞는 JSON 객체 하나뿐이다. 설명을 덧붙이지 않는다.
            """;

    private TeamContextPrompt() { }

    public static String user(ObjectMapper mapper, TeamContextInferenceRequest request) {
        try {
            return mapper.writeValueAsString(request);
        } catch (JsonProcessingException exception) {
            throw new TeamContextInferenceException("Could not serialize inference request", exception);
        }
    }

    /** OpenAI 호환 response_format. vLLM과 OpenRouter 모두 json_schema를 받으며 strict는 지원 제공자에서만 강제된다. */
    public static ObjectNode responseFormat(ObjectMapper mapper) {
        ObjectNode format = mapper.createObjectNode();
        format.put("type", "json_schema");
        ObjectNode schema = format.putObject("json_schema");
        schema.put("name", "team_part_context");
        schema.put("strict", true);
        ObjectNode root = schema.putObject("schema");
        object(root);
        ObjectNode properties = root.putObject("properties");
        ArrayNode required = root.putArray("required");
        required.add("common").add("changed").add("issues");

        ObjectNode common = items(properties.putObject("common"));
        ObjectNode commonProperties = common.putObject("properties");
        commonProperties.putObject("statement").put("type", "string");
        stringArray(commonProperties.putObject("evidence"));
        common.putArray("required").add("statement").add("evidence");

        ObjectNode changed = items(properties.putObject("changed"));
        ObjectNode changedProperties = changed.putObject("properties");
        changedProperties.putObject("before").put("type", "string");
        changedProperties.putObject("after").put("type", "string");
        stringArray(changedProperties.putObject("evidence"));
        changed.putArray("required").add("before").add("after").add("evidence");

        ObjectNode issue = items(properties.putObject("issues"));
        ObjectNode issueProperties = issue.putObject("properties");
        issueProperties.putObject("topic").put("type", "string");
        ObjectNode position = items(issueProperties.putObject("positions"));
        ObjectNode positionProperties = position.putObject("properties");
        positionProperties.putObject("part").put("type", "string");
        positionProperties.putObject("position").put("type", "string");
        position.putArray("required").add("part").add("position");
        stringArray(issueProperties.putObject("evidence"));
        issue.putArray("required").add("topic").add("positions").add("evidence");
        return format;
    }

    private static ObjectNode object(ObjectNode node) {
        node.put("type", "object");
        node.put("additionalProperties", false);
        return node;
    }

    private static ObjectNode items(ObjectNode array) {
        array.put("type", "array");
        return object(array.putObject("items"));
    }

    private static void stringArray(ObjectNode array) {
        array.put("type", "array");
        array.putObject("items").put("type", "string");
    }
}
