package com.example.ordersystem.ordering.service;

import com.example.ordersystem.member.domain.Member;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.ordering.controller.SseController;
import com.example.ordersystem.ordering.domain.Ordering;
import com.example.ordersystem.ordering.dto.OrderSaveReqDto;
import com.example.ordersystem.ordering.repository.OrderingRepository;
import com.example.ordersystem.ordering.service.stock.StockProcessor;
import com.example.ordersystem.product.domain.Product;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderingServiceTest {

    @Mock
    private OrderingRepository orderingRepository;
    @Mock
    private MemberRepository memberRepository;
    @Mock
    private StockProcessor stockProcessor;
    @Mock
    private SseController sseController;

    private OrderingService orderingService;

    @BeforeEach
    void setUp() {
        orderingService = new OrderingService(
                orderingRepository, memberRepository, stockProcessor, sseController);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("buyer@test.com", null, Collections.emptyList()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void completesStockProcessingOnlyAfterOrderIsSaved() {
        Member member = Member.builder().email("buyer@test.com").build();
        Product product = product(1L);
        when(memberRepository.findByEmail("buyer@test.com")).thenReturn(Optional.of(member));
        when(stockProcessor.findProduct(1L)).thenReturn(product);
        when(orderingRepository.save(any(Ordering.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderingService.orderCreate(Collections.singletonList(order(1L, 2)));

        verify(stockProcessor).decrease(product, 2);
        verify(orderingRepository).save(any(Ordering.class));
        verify(stockProcessor).completeDecrease(1L, 2);
    }

    @Test
    void compensatesAlreadyDecreasedStockWhenLaterProductFails() {
        Member member = Member.builder().email("buyer@test.com").build();
        Product first = product(1L);
        Product second = product(2L);
        when(memberRepository.findByEmail("buyer@test.com")).thenReturn(Optional.of(member));
        when(stockProcessor.findProduct(1L)).thenReturn(first);
        when(stockProcessor.findProduct(2L)).thenReturn(second);
        doAnswer(invocation -> {
            if (invocation.getArgument(0) == second) {
                throw new IllegalArgumentException("재고가 부족합니다.");
            }
            return null;
        }).when(stockProcessor).decrease(any(Product.class), anyInt());

        assertThatThrownBy(() -> orderingService.orderCreate(Arrays.asList(order(2L, 1), order(1L, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고가 부족합니다.");

        verify(stockProcessor).rollbackDecrease(1L, 2);
        verify(orderingRepository, never()).save(any(Ordering.class));
        verify(stockProcessor, never()).completeDecrease(anyLong(), anyInt());
    }

    private Product product(Long id) {
        return Product.builder()
                .id(id)
                .name("benchmark-product-" + id)
                .stockQuantity(10)
                .build();
    }

    private OrderSaveReqDto order(Long productId, int quantity) {
        return OrderSaveReqDto.builder()
                .productId(productId)
                .productCount(quantity)
                .build();
    }
}
