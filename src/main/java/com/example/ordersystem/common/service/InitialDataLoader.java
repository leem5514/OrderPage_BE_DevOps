package com.example.ordersystem.common.service;

import com.example.ordersystem.member.domain.Role;
import com.example.ordersystem.member.dto.MemberSaveDto;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.member.service.MemberService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

// commendLineRunner을 상속함으로서 해당 컴포넌트가 스프링빈으로 등록되는 시점에서 run 메서드 실행
@Component
@ConditionalOnProperty(name = "app.bootstrap-admin.enabled", havingValue = "true")
public class InitialDataLoader implements CommandLineRunner {

    // 기본 비활성. 빈 DB 초기화 시 한 인스턴스에서만 Secret으로 값을 주입한다.
    @Value("${app.admin-email}")
    private String adminEmail;
    @Value("${app.bootstrap-admin.password}")
    private String adminPassword;

    @Autowired
    private MemberService memberService;
    @Autowired
    private MemberRepository memberRepository;

    @Override
    public void run(String... args) throws Exception {
        Assert.hasText(adminEmail, "관리자 초기화 이메일이 필요합니다");
        Assert.hasText(adminPassword, "관리자 초기화 비밀번호가 필요합니다");
        if(memberRepository.findByEmail(adminEmail).isEmpty()) {
            memberService.memberCreate(MemberSaveDto.builder()
                    .name("admin")
                    .email(adminEmail)
                    .password(adminPassword)
                    .role(Role.ADMIN)
                    .build());
        }

    }
}
