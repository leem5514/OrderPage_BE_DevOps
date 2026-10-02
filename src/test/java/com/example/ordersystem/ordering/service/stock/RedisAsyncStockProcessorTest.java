package com.example.ordersystem.ordering.service.stock;

import com.example.ordersystem.common.service.StockInventoryService;
import com.example.ordersystem.ordering.dto.StockDecreaseEvent;
import com.example.ordersystem.ordering.service.StockDecreaseEventHandler;
import com.example.ordersystem.product.domain.Product;
import com.example.ordersystem.product.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisAsyncStockProcessorTest {

    @Mock
    private ProductRepository productRepository;
    @Mock
    private StockInventoryService stockInventoryService;
    @Mock
    private StockDecreaseEventHandler stockDecreaseEventHandler;

    private RedisAsyncStockProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RedisAsyncStockProcessor(
                productRepository, stockInventoryService, stockDecreaseEventHandler);
    }

    @Test
    void loadsProductWithoutRdbWriteLock() {
        Product product = product(10);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        assertThat(processor.findProduct(1L)).isSameAs(product);
        verify(productRepository).findById(1L);
        verify(productRepository, never()).findByIdForUpdate(1L);
    }

    @Test
    void decreasesRedisStockAndPublishesRdbEventAfterOrderSave() {
        Product product = product(10);
        when(stockInventoryService.decreaseStock(1L, 3, 10)).thenReturn(7L);

        processor.decrease(product, 3);
        processor.completeDecrease(1L, 3);

        assertThat(product.getStockQuantity()).isEqualTo(10);
        verify(stockInventoryService).decreaseStock(1L, 3, 10);
        verify(stockDecreaseEventHandler).publish(new StockDecreaseEvent(1L, 3));
    }

    @Test
    void rejectsOrderWhenRedisStockIsInsufficient() {
        Product product = product(2);
        when(stockInventoryService.decreaseStock(1L, 3, 2)).thenReturn(-1L);

        assertThatThrownBy(() -> processor.decrease(product, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고가 부족합니다.");
    }

    @Test
    void compensatesFailedOrderAndRestoresCancelledOrder() {
        Product product = product(10);

        processor.rollbackDecrease(1L, 3);
        processor.restore(product, 2);

        verify(stockInventoryService).increaseStock(1L, 3);
        verify(stockInventoryService).increaseStock(1L, 2);
        verify(stockDecreaseEventHandler).publish(new StockDecreaseEvent(1L, -2));
    }

    private Product product(int stockQuantity) {
        return Product.builder()
                .id(1L)
                .name("benchmark-product")
                .stockQuantity(stockQuantity)
                .build();
    }
}
