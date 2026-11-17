package com.example.ordersystem.ordering.controller;

import com.example.ordersystem.common.service.CommonExceptionHandler;
import com.example.ordersystem.member.domain.Member;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.ordering.domain.Ordering;
import com.example.ordersystem.ordering.repository.OrderingRepository;
import com.example.ordersystem.ordering.service.OrderingService;
import com.example.ordersystem.ordering.service.stock.StockProcessor;
import com.example.ordersystem.product.domain.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class OrderInputValidationTest {
    @Mock private OrderingRepository orderingRepository;
    @Mock private MemberRepository memberRepository;
    @Mock private StockProcessor stockProcessor;
    @Mock private SseController notifications;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 실제 Service를 연결해 HTTP 400뿐 아니라 재고/저장/알림 호출 전 차단도 확인한다.
        OrderingService service = new OrderingService(
                orderingRepository, memberRepository, stockProcessor, notifications);
        ReflectionTestUtils.setField(service, "adminEmail", "operator@example.test");
        mvc = MockMvcBuilders.standaloneSetup(new OrderController(service))
                .setControllerAdvice(new CommonExceptionHandler()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("buyer@example.test", null, Collections.emptyList()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "[]",
            "[null]",
            "[{\"productCount\":1}]",
            "[{\"productId\":null,\"productCount\":1}]",
            "[{\"productId\":0,\"productCount\":1}]",
            "[{\"productId\":-1,\"productCount\":1}]",
            "[{\"productId\":1}]",
            "[{\"productId\":1,\"productCount\":null}]",
            "[{\"productId\":1,\"productCount\":0}]",
            "[{\"productId\":1,\"productCount\":-1}]",
            "[{\"productId\":1,\"productCount\":1},{\"productId\":2,\"productCount\":-1}]"
    })
    void invalidItemsReturn400WithoutBusinessSideEffects(String body) throws Exception {
        mvc.perform(post("/order/create").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status_code").value(400));
        verifyNoInteractions(memberRepository, orderingRepository, stockProcessor, notifications);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", "null", "[",
            "{\"productId\":1,\"productCount\":1}",
            "[{\"productId\":1,\"productCount\":2147483648}]",
            "[{\"productId\":1,\"productCount\":\"SyntheticSensitiveInput123\"}]"
    })
    void unreadableBodiesReturnSafe400WithoutBusinessSideEffects(String body) throws Exception {
        mvc.perform(post("/order/create").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status_code").value(400))
                .andExpect(jsonPath("$.error_message").value("요청 본문을 확인해주세요."))
                .andExpect(content().string(not(containsString("SyntheticSensitiveInput123"))));
        verifyNoInteractions(memberRepository, orderingRepository, stockProcessor, notifications);
    }

    @Test
    void existingArrayContractStillCreatesAValidMultiProductOrder() throws Exception {
        Member buyer = Member.builder().email("buyer@example.test").build();
        Product first = Product.builder().id(1L).name("first").stockQuantity(10).build();
        Product second = Product.builder().id(2L).name("second").stockQuantity(10).build();
        when(memberRepository.findByEmail("buyer@example.test")).thenReturn(Optional.of(buyer));
        when(stockProcessor.findProduct(1L)).thenReturn(first);
        when(stockProcessor.findProduct(2L)).thenReturn(second);
        when(orderingRepository.save(any(Ordering.class))).thenAnswer(invocation -> {
            Ordering saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        mvc.perform(post("/order/create").contentType(MediaType.APPLICATION_JSON)
                .content("[{\"productId\":2,\"productCount\":3},{\"productId\":1,\"productCount\":2}]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status_code").value(201))
                .andExpect(jsonPath("$.result").value(10));
        ArgumentCaptor<Ordering> saved = ArgumentCaptor.forClass(Ordering.class);
        verify(orderingRepository).save(saved.capture());
        assertThat(saved.getValue().getMember()).isSameAs(buyer);
        assertThat(saved.getValue().getOrderDetails()).extracting(detail -> detail.getQuantity())
                .containsExactly(2, 3);
        verify(stockProcessor).decrease(first, 2);
        verify(stockProcessor).decrease(second, 3);
        verify(stockProcessor).completeDecrease(1L, 2);
        verify(stockProcessor).completeDecrease(2L, 3);
        verify(notifications).publishMessage(any(), eq("operator@example.test"), eq("ordered"));
    }
}
