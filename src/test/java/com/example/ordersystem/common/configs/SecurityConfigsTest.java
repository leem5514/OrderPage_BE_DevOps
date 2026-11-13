package com.example.ordersystem.common.configs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.member.domain.Role;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthContributor;
import org.springframework.boot.actuate.health.HealthContributorRegistry;
import org.springframework.boot.actuate.health.HealthIndicator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.health.redis.enabled=false",
        "management.health.rabbit.enabled=false",
        "management.metrics.export.prometheus.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private HealthContributorRegistry healthContributors;

    @Test
    void databaseOutageAffectsReadinessButNotLiveness() throws Exception {
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        HealthContributor original = healthContributors.unregisterContributor("db");
        healthContributors.registerContributor("db", (HealthIndicator) () -> Health.down().build());
        try {
            mockMvc.perform(get("/actuator/health/liveness"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
            mockMvc.perform(get("/actuator/health/readiness"))
                    .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.status").value("DOWN"));
        } finally {
            healthContributors.unregisterContributor("db");
            healthContributors.registerContributor("db", original);
        }
        mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @Transactional
    void publicSignupCannotGrantAdminRole() throws Exception {
        mockMvc.perform(post("/member/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"audit\",\"email\":\"role-check@example.test\",\"password\":\"audit-password-123\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated());
        assertThat(memberRepository.findByEmail("role-check@example.test").orElseThrow().getRole())
                .isEqualTo(Role.USER);
    }

    @Test
    void startupDoesNotCreateKnownAdminCredentials() {
        assertThat(memberRepository.findByEmail("admin@test.com")).isEmpty();
    }

    @Test
    void healthEndpointAllowsAnonymousHealthChecks() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void prometheusEndpointAllowsInternalScraping() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/actuator/metrics"))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberEndpointRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/member/myInfo"))
                .andExpect(status().isForbidden());
    }
}
