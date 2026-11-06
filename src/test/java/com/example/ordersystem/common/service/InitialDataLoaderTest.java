package com.example.ordersystem.common.service;

import com.example.ordersystem.member.domain.Role;
import com.example.ordersystem.member.dto.MemberSaveDto;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.member.service.MemberService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InitialDataLoaderTest {
    @Test
    void explicitBootstrapCreatesAdminOnlyOnce() {
        MemberService service = mock(MemberService.class);
        MemberRepository repository = mock(MemberRepository.class);
        new ApplicationContextRunner()
                .withUserConfiguration(InitialDataLoader.class)
                .withBean(MemberService.class, () -> service)
                .withBean(MemberRepository.class, () -> repository)
                .withPropertyValues("app.bootstrap-admin.enabled=true",
                        "app.admin-email=bootstrap@example.test",
                        "app.bootstrap-admin.password=temporary-test-password")
                .run(context -> {
                    InitialDataLoader loader = context.getBean(InitialDataLoader.class);
                    loader.run();
                    ArgumentCaptor<MemberSaveDto> input = ArgumentCaptor.forClass(MemberSaveDto.class);
                    verify(service).memberCreate(input.capture());
                    assertThat(input.getValue().getRole()).isEqualTo(Role.ADMIN);
                    assertThat(input.getValue().getEmail()).isEqualTo("bootstrap@example.test");
                    when(repository.findByEmail("bootstrap@example.test"))
                            .thenReturn(java.util.Optional.of(new com.example.ordersystem.member.domain.Member()));
                    loader.run();
                    verifyNoMoreInteractions(service);
                });
    }
}
