package com.example.ordersystem.common.auth;

import org.springframework.security.crypto.password.PasswordEncoder;

/** BCrypt 길이 제한을 모든 PasswordEncoder 호출에 일관되게 적용한다. */
public final class BoundedPasswordEncoder implements PasswordEncoder {
    private final PasswordEncoder delegate;

    public BoundedPasswordEncoder(PasswordEncoder delegate) { this.delegate = delegate; }

    @Override
    public String encode(CharSequence rawPassword) {
        PasswordPolicy.validate(rawPassword == null ? null : rawPassword.toString());
        return delegate.encode(rawPassword);
    }

    @Override
    public boolean matches(CharSequence rawPassword, String encodedPassword) {
        try {
            PasswordPolicy.validate(rawPassword == null ? null : rawPassword.toString());
        } catch (IllegalArgumentException rejected) {
            // BCrypt 구현의 초과 입력 처리에 의존하지 않는다. 입력을 잘라 비교하지 않는다.
            return false;
        }
        return delegate.matches(rawPassword, encodedPassword);
    }

    @Override
    public boolean upgradeEncoding(String encodedPassword) { return delegate.upgradeEncoding(encodedPassword); }
}
