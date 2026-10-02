package com.example.ordersystem.ordering.service.stock;

import com.example.ordersystem.product.domain.Product;
import com.example.ordersystem.product.repository.ProductRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;

/**
 * Before 비교군: 요청 트랜잭션 안에서 RDB 행 잠금, 재고 검증, 차감을 모두 끝낸다.
 */
@Service
@ConditionalOnProperty(name = "order.stock-processing-mode", havingValue = "RDB_SYNC")
public class RdbSyncStockProcessor implements StockProcessor {

    private final ProductRepository productRepository;

    public RdbSyncStockProcessor(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    @Override
    public Product findProduct(Long productId) {
        // 같은 상품 주문을 직렬화해 음수 재고와 초과 판매를 막는다.
        return productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new EntityNotFoundException("상품이 존재하지 않습니다."));
    }

    @Override
    public void decrease(Product product, int quantity) {
        if (product.getStockQuantity() < quantity) {
            throw new IllegalArgumentException("재고가 부족합니다.");
        }
        product.updateStockQuantity(quantity);
    }

    @Override
    public void restore(Product product, int quantity) {
        product.updateStockQuantity(-quantity);
    }
}
