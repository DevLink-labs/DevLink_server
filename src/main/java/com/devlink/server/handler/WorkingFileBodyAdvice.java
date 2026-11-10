package com.devlink.server.handler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import com.devlink.server.common.BusinessException;
import com.devlink.server.common.ErrorCode;
import com.devlink.server.dto.gitcollection.WorkingFileRequest;

@RestControllerAdvice
public class WorkingFileBodyAdvice extends RequestBodyAdviceAdapter {
	
	private static final int MAX_BODY_BYTES = 2 * 1024 * 1024;
	
	@Override
	public boolean supports(
			MethodParameter parameter,
			Type targetType,
			Class<? extends HttpMessageConverter<?>> converterType) {
		return parameter.getParameterType() == WorkingFileRequest.class;
	}
	
	@Override
	public HttpInputMessage beforeBodyRead(
			HttpInputMessage inputMessage,
			MethodParameter parameter,
			Type targetType,
			Class<? extends HttpMessageConverter<?>> converterType)
	throws IOException{
		byte[] body = inputMessage.getBody()
				.readNBytes(MAX_BODY_BYTES+1);
		if(body.length > MAX_BODY_BYTES) {
			throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		InputStream bodyStream = new ByteArrayInputStream(body);
		
		return new HttpInputMessage() {
			@Override
			public InputStream getBody() {
				return bodyStream;
			}
			
			@Override
			public HttpHeaders getHeaders() {
				return inputMessage.getHeaders();
			}
		};
	}

}
