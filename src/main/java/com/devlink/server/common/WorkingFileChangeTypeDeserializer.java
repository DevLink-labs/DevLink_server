package com.devlink.server.common;

import java.io.IOException;
import com.devlink.server.domain.gitrepo.WorkingFileChangeType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

public class WorkingFileChangeTypeDeserializer extends JsonDeserializer<WorkingFileChangeType> {
	@Override
	public WorkingFileChangeType deserialize(
			JsonParser parser,
			DeserializationContext context) throws IOException{
		if(parser.currentToken() != JsonToken.VALUE_STRING) {
			return(WorkingFileChangeType)context.handleUnexpectedToken(
					WorkingFileChangeType.class,parser);
		}
		String value = parser.getText();
		
		try {
			return WorkingFileChangeType.valueOf(value);
		}catch(IllegalArgumentException exception) {
			throw context.weirdStringException(value, 
					WorkingFileChangeType.class,  "ADDED, MODIFIED, DELETED, TYPE_CHANGED, "
                            + "UNTRACKED, UNMERGED 중 하나여야 합니다.");
		}
	}
}
