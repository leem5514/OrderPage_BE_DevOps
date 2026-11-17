package com.example.ordersystem.common.auth;

import com.example.ordersystem.common.configs.WebConfig;
import com.example.ordersystem.member.domain.Member;
import com.example.ordersystem.member.dto.MemberLoginDto;
import com.example.ordersystem.member.dto.MemberResetPasswordDto;
import com.example.ordersystem.member.dto.MemberSaveDto;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.member.service.MemberService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PasswordValidationTest {
    private final MemberRepository repository = mock(MemberRepository.class);
    private final PasswordEncoder encoder = new WebConfig().makePassword();
    private final MemberService service = new MemberService(repository, encoder);

    static Stream<String> rejectedPasswords() {
        return Stream.of(null, "", "short", "a".repeat(73), "가".repeat(25), "a".repeat(72) + "different");
    }

    @ParameterizedTest
    @MethodSource("rejectedPasswords")
    void rejectsAllWriteAndLoginPathsBeforeRepositoryAccess(String password) {
        assertThatIllegalArgumentException().isThrownBy(() -> service.memberCreate(
                MemberSaveDto.builder().email("user@example.test").password(password).build()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.login(
                MemberLoginDto.builder().email("user@example.test").password(password).build()));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resetPassword(
                new MemberResetPasswordDto("user@example.test", password, "new-password")));
        assertThatIllegalArgumentException().isThrownBy(() -> service.resetPassword(
                new MemberResetPasswordDto("user@example.test", "old-password", password)));
        verifyNoInteractions(repository);
    }

    static Stream<String> boundaryPasswords() { return Stream.of("a".repeat(72), "가".repeat(24)); }

    @ParameterizedTest
    @MethodSource("boundaryPasswords")
    void preservesBcryptAndAcceptsExact72ByteBoundary(String password) {
        when(repository.save(any(Member.class))).thenAnswer(call -> call.getArgument(0));
        Member member = service.memberCreate(MemberSaveDto.builder()
                .email("user@example.test").password(password).build());
        assertThat(member.getPassword()).startsWith("{bcrypt}");
        when(repository.findByEmail(member.getEmail())).thenReturn(Optional.of(member));
        assertThat(service.login(new MemberLoginDto(member.getEmail(), password))).isSameAs(member);
        assertThatIllegalArgumentException().isThrownBy(() -> service.login(
                new MemberLoginDto(member.getEmail(), password + "x")));
        service.resetPassword(new MemberResetPasswordDto(member.getEmail(), password, "new-password"));
        assertThat(encoder.matches("new-password", member.getPassword())).isTrue();
        assertThat(encoder.matches(password, member.getPassword())).isFalse();
    }

    @Test
    void applicationEncoderDoesNotAcceptAnExtendedPassword() {
        String password = "a".repeat(72);
        String hash = encoder.encode(password);
        assertThat(encoder.matches(password + "different-suffix", hash)).isFalse();
    }
}
