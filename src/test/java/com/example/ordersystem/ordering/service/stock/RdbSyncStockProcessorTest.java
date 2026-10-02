package com.example.ordersystem.ordering.service.stock;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RdbSyncStockProcessorTest {

    @Mock
    private ProductRepository productRepository;

    private RdbSyncStockProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RdbSyncStockProcessor(productRepository);
    }

    @Test
    void loadsProductWithWriteLock() {
        Product product = product(10);
        when(productRepository.findByIdForUpdate(1L)).thenReturn(Optional.of(product));

        assertThat(processor.findProduct(1L)).isSameAs(product);
        verify(productRepository).findByIdForUpdate(1L);
    }

    @Test
    void decreasesAndRestoresRdbStockImmediately() {
        Product product = product(10);

        processor.decrease(product, 3);
        assertThat(product.getStockQuantity()).isEqualTo(7);

        processor.restore(product, 3);
        assertThat(product.getStockQuantity()).isEqualTo(10);
    }

    @Test
    void rejectsOrderWhenRdbStockIsInsufficient() {
        Product product = product(2);

        assertThatThrownBy(() -> processor.decrease(product, 3))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("재고가 부족합니다.");
        assertThat(product.getStockQuantity()).isEqualTo(2);
    }

    private Product product(int stockQuantity) {
        return Product.builder()
                .id(1L)
                .name("benchmark-product")
                .stockQuantity(stockQuantity)
                .build();
    }
}
