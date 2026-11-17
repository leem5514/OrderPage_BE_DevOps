package com.example.ordersystem.common.auth;

import java.nio.charset.StandardCharsets;

public final class PasswordPolicy {
    private PasswordPolicy() { }

    public static void validate(String password) {
        if (password == null || password.length() < 8) {
            throw new IllegalArgumentException("비밀번호는 8자 이상이어야 합니다.");
        }
        // BCrypt는 문자 수가 아닌 UTF-8 72바이트까지 처리한다. 자르거나 정규화하지 않는다.
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new IllegalArgumentException("비밀번호는 UTF-8 기준 72바이트 이하여야 합니다.");
        }
    }
}
