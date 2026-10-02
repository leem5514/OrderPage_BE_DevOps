package com.example.ordersystem.ordering.service.stock;

import com.example.ordersystem.common.service.StockInventoryService;
import com.example.ordersystem.ordering.dto.StockDecreaseEvent;
import com.example.ordersystem.ordering.service.StockDecreaseEventHandler;
import com.example.ordersystem.product.domain.Product;
import com.example.ordersystem.product.repository.ProductRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.persistence.EntityNotFoundException;

/**
 * After 비교군: Redis Lua로 실시간 재고를 원자적으로 차감하고 RabbitMQ로 RDB에 반영한다.
 */
@Service
@ConditionalOnProperty(name = "order.stock-processing-mode", havingValue = "REDIS_ASYNC", matchIfMissing = true)
public class RedisAsyncStockProcessor implements StockProcessor {

    private final ProductRepository productRepository;
    private final StockInventoryService stockInventoryService;
    private final StockDecreaseEventHandler stockDecreaseEventHandler;

    public RedisAsyncStockProcessor(ProductRepository productRepository,
                                    StockInventoryService stockInventoryService,
                                    StockDecreaseEventHandler stockDecreaseEventHandler) {
        this.productRepository = productRepository;
        this.stockInventoryService = stockInventoryService;
        this.stockDecreaseEventHandler = stockDecreaseEventHandler;
    }

    @Override
    public Product findProduct(Long productId) {
        // 재고 동시성은 Redis Lua가 제어하므로 주문 요청마다 RDB 쓰기 잠금을 잡지 않는다.
        return productRepository.findById(productId)
                .orElseThrow(() -> new EntityNotFoundException("상품이 존재하지 않습니다."));
    }

    @Override
    public void decrease(Product product, int quantity) {
        long remaining = stockInventoryService.decreaseStock(
                product.getId(), quantity, product.getStockQuantity());
        if (remaining < 0) {
            throw new IllegalArgumentException("재고가 부족합니다.");
        }
    }

    @Override
    public void completeDecrease(Long productId, int quantity) {
        stockDecreaseEventHandler.publish(new StockDecreaseEvent(productId, quantity));
    }

    @Override
    public void rollbackDecrease(Long productId, int quantity) {
        stockInventoryService.increaseStock(productId, quantity);
    }

    @Override
    public void restore(Product product, int quantity) {
        stockInventoryService.increaseStock(product.getId(), quantity);
        stockDecreaseEventHandler.publish(new StockDecreaseEvent(product.getId(), -quantity));
    }

    @Override
    public void initialize(Product product) {
        stockInventoryService.increaseStock(product.getId(), product.getStockQuantity());
    }

    @Override
    public void remove(Long productId) {
        stockInventoryService.removeStock(productId);
    }
}
