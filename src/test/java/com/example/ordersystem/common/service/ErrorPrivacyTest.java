package com.example.ordersystem.common.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class ErrorPrivacyTest {
    public void request(Object body) {}

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
        assertThat(result.getStatusCodeValue()).isEqualTo(500);
        assertThat(result.getBody().getStatus_code()).isEqualTo(500);
        assertThat(result.getBody().getError_message()).doesNotContain(marker);
        assertThat(output.getAll()).doesNotContain(marker);
    }
}
