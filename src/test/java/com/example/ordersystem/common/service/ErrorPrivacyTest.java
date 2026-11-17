package com.example.ordersystem.common.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.http.MockHttpInputMessage;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ErrorPrivacyTest {
    public void request(Object body) {}

    @Test
    void unreadableBodyDoesNotExposeParserInput(CapturedOutput output) {
        String marker = "SyntheticSensitiveParserInput123";
        HttpMessageNotReadableException error = new HttpMessageNotReadableException(
                marker, new MockHttpInputMessage(new byte[0]));
        var response = new CommonExceptionHandler().unreadableBodyHandler(error);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().getStatus_code()).isEqualTo(400);
        assertThat(response.getBody().getError_message()).isEqualTo("요청 본문을 확인해주세요.");
        assertThat(output.getAll()).doesNotContain(marker);
    }

    @Test
    void rejectedPasswordAndExceptionTextAreNotLoggedOrReturned(CapturedOutput output) throws Exception {
        String marker = "SyntheticSensitiveInput123";
        BeanPropertyBindingResult errors = new BeanPropertyBindingResult(new Object(), "request");
        errors.addError(new FieldError("request", "password", marker, false, null, null, marker));
        MethodParameter parameter = new MethodParameter(getClass().getMethod("request", Object.class), 0);
        CommonExceptionHandler handler = new CommonExceptionHandler();
        assertThat(handler.validHandler(new MethodArgumentNotValidException(parameter, errors))
                .getBody().getError_message()).doesNotContain(marker);
        var result = handler.exceptionHandler(new RuntimeException(marker));
        assertThat(result.getStatusCode().value()).isEqualTo(500);
        assertThat(result.getBody().getStatus_code()).isEqualTo(500);
        assertThat(result.getBody().getError_message()).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }
}
