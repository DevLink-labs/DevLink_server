package com.devlink.server.common;

import java.io.IOException;
import com.devlink.server.domain.gitrepo.CommitChangeType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

public final class CommitChangeTypeDeserializer extends JsonDeserializer<CommitChangeType>{
	
	@Override
	public CommitChangeType deserialize(JsonParser parser,
			DeserializationContext context) throws IOException {
		
		if(parser.currentToken() != JsonToken.VALUE_STRING) {
			return(CommitChangeType)context.handleUnexpectedToken(CommitChangeType.class, parser);
		}
		
		String value = parser.getText();
		
		try {
			return CommitChangeType.valueOf(value);
		}catch(IllegalArgumentException exception) {
			throw context.weirdStringException(
					value, 
					CommitChangeType.class, 
					"ADDED, MODIFIED, DELETED, RENAMED 중 하나여야 합니다.");
			
		}
		
	}
}
