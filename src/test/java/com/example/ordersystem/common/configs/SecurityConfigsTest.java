package com.example.ordersystem.common.configs;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.member.domain.Role;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthContributor;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.health.contributor.HealthIndicator;
import com.example.ordersystem.common.auth.JwtTokenProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "management.health.redis.enabled=false",
        "management.health.rabbit.enabled=false",
        "management.prometheus.metrics.export.enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityConfigsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private JwtTokenProvider tokens;

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

    @Test
    void orderEndpointStillRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/order/create").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"productId\":1,\"productCount\":1}]"))
                .andExpect(status().isForbidden());
    }

    @Test
    void validJwtPreservesMethodAuthorizationAndRefreshCannotAuthenticate() throws Exception {
        mockMvc.perform(get("/member/list").header("Authorization", "Bearer " +
                        tokens.createToken("user@example.test", "USER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/member/list").header("Authorization", "Bearer " +
                        tokens.createToken("admin@example.test", "ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status_code").value(200));
        mockMvc.perform(get("/member/list").header("Authorization", "Bearer " +
                        tokens.createRefreshToken("admin@example.test", "ADMIN")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void oversizedPasswordsReturn400WithoutEchoingInput() throws Exception {
        String password = "가".repeat(25);
        var result = mockMvc.perform(post("/member/create").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"length@example.test\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(password);
        assertThat(memberRepository.findByEmail("length@example.test")).isEmpty();
        mockMvc.perform(post("/doLogin").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"length@example.test\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isBadRequest());
    }
}
