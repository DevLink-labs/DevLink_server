package com.devlink.server.common;

import java.io.IOException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

/**
 * JSON 문자열만 받는 역직렬화기. 숫자·불리언 등을 문자열로 자동 변환하지 않고 입력 오류로 처리한다.
 * null은 Jackson이 이 역직렬화기를 거치지 않고 그대로 전달한다.
 */
public final class StrictStringDeserializer extends JsonDeserializer<String> {
    @Override
    public String deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        if (parser.currentToken() != JsonToken.VALUE_STRING) {
            return (String) context.handleUnexpectedToken(String.class, parser);
        }
        return parser.getText();
    }
}
