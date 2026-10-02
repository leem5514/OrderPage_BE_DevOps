package com.example.ordersystem.ordering.service.stock;

import com.example.ordersystem.product.domain.Product;

/**
 * 동일한 주문 API에서 재고 처리 방식만 교체하기 위한 경계다.
 * 부하 테스트는 이 구현만 바꿔 RDB 동기 방식과 Redis 비동기 방식을 공정하게 비교한다.
 */
public interface StockProcessor {

    Product findProduct(Long productId);

    void decrease(Product product, int quantity);

    default void completeDecrease(Long productId, int quantity) {
    }

    default void rollbackDecrease(Long productId, int quantity) {
    }

    void restore(Product product, int quantity);

    default void initialize(Product product) {
    }

    default void remove(Long productId) {
    }
}
