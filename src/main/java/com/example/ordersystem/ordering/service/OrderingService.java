package com.example.ordersystem.ordering.service;

import com.example.ordersystem.member.domain.Member;
import com.example.ordersystem.member.repository.MemberRepository;
import com.example.ordersystem.ordering.controller.SseController;
import com.example.ordersystem.ordering.domain.OrderDetail;
import com.example.ordersystem.ordering.domain.OrderStatus;
import com.example.ordersystem.ordering.domain.Ordering;
import com.example.ordersystem.ordering.dto.OrderListResDto;
import com.example.ordersystem.ordering.dto.OrderSaveReqDto;
import com.example.ordersystem.ordering.dto.StockDecreaseEvent;
import com.example.ordersystem.ordering.repository.OrderingRepository;
import com.example.ordersystem.ordering.service.stock.StockProcessor;
import com.example.ordersystem.product.domain.Product;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.persistence.EntityNotFoundException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@Transactional
public class OrderingService {

    private final OrderingRepository orderingRepository;
    private final MemberRepository memberRepository;
    private final StockProcessor stockProcessor;
    private final SseController sseController;


    @Autowired
    public OrderingService(OrderingRepository orderingRepository, MemberRepository memberRepository, StockProcessor stockProcessor, SseController sseController) {
        this.orderingRepository = orderingRepository;
        this.memberRepository = memberRepository;
        this.stockProcessor = stockProcessor;
        this.sseController = sseController;
    }

    /* 주문하기 */
    // ORDER_STOCK_PROCESSING_MODE 값에 따라 RDB_SYNC 또는 REDIS_ASYNC 구현 하나가 주입된다.
    // 주문 API와 데이터는 그대로 두고 재고 처리 방식만 바꿔야 부하 테스트 비교 조건이 같아진다.
    public Ordering orderCreate(List<OrderSaveReqDto> dtos) {

        String memberEmail = SecurityContextHolder.getContext().getAuthentication().getName();
        Member member = memberRepository.findByEmail(memberEmail).orElseThrow(()-> new EntityNotFoundException("회원이 존재하지 않습니다."));
        Ordering ordering = Ordering.builder()
                .member(member)
                .build();

        // 주문 저장이 실제로 성공한 뒤에만 RabbitMQ 이벤트를 발행한다 (뒤 상품에서 재고 부족으로
        // 주문 전체가 실패할 경우 이미 발행된 이벤트 때문에 RDB 재고만 먼저 깎이는 걸 막기 위함).
        List<StockDecreaseEvent> pendingStockDecreases = new ArrayList<>();
        // Redis 모드는 명시적으로 보상하고 RDB 모드는 트랜잭션 롤백에 맡긴다. 두 구현 모두 같은 호출
        // 순서를 사용해야 비교 실험에서 주문 로직 자체가 변수가 되지 않는다.
        List<StockDecreaseEvent> appliedStockDecreases = new ArrayList<>();

        // RDB 모드에서 여러 상품의 행 잠금 순서가 엇갈려 생길 수 있는 데드락을 막기 위해 정렬한다.
        // Redis 모드도 같은 순서를 사용해 두 비교군의 주문 처리 순서를 동일하게 유지한다.
        List<OrderSaveReqDto> sortedDtos = new ArrayList<>(dtos);
        sortedDtos.sort(Comparator.comparing(OrderSaveReqDto::getProductId));

        try {
            for (OrderSaveReqDto orderDto : sortedDtos) {
                Product product = stockProcessor.findProduct(orderDto.getProductId());
                int quantity = orderDto.getProductCount();

                stockProcessor.decrease(product, quantity);
                StockDecreaseEvent event = new StockDecreaseEvent(product.getId(), quantity);
                appliedStockDecreases.add(event);
                pendingStockDecreases.add(event);

                OrderDetail orderDetail = OrderDetail.builder()
                        .product(product)
                        .quantity(quantity)
                        .ordering(ordering)
                        .build();
                ordering.getOrderDetails().add(orderDetail);
            }

            Ordering savedOrder = orderingRepository.save(ordering);

            pendingStockDecreases.forEach(event ->
                    stockProcessor.completeDecrease(event.getProductId(), event.getProductCount()));
            sseController.publishMessage(savedOrder.fromEntityList(), "admin@test.com", "ordered");
            return savedOrder;
        } catch (RuntimeException e) {
            // Redis 구현은 이미 차감한 재고를 복구하고, RDB 구현은 no-op 후 트랜잭션이 변경을 롤백한다.
            appliedStockDecreases.forEach(event ->
                    stockProcessor.rollbackDecrease(event.getProductId(), event.getProductCount()));
            throw e;
        }
    }


    /* 전체 리스트 */
    public List<OrderListResDto> orderList(){
        List<Ordering> orderings = orderingRepository.findAll();
        List<OrderListResDto> orderListResDtos = new ArrayList<>();
        for(Ordering ordering : orderings){
            orderListResDtos.add(ordering.fromEntityList());
        }
        return orderListResDtos;
    }

    /* 내 주문 보기 */
    public List<OrderListResDto> myOrders(){
        Member member =memberRepository.findByEmail(SecurityContextHolder.getContext().getAuthentication().getName()).orElseThrow(() -> new EntityNotFoundException("Member not found"));
        List<Ordering> orderings = orderingRepository.findByMember(member);
        List<OrderListResDto> orderListResDtos = new ArrayList<>();
        for(Ordering ordering : orderings){
            orderListResDtos.add(ordering.fromEntityList());
        }
        return orderListResDtos;
    }

    /* 주문 취소 (admin 기준) : 취소된 수량만큼 재고를 되돌린다 */
    public Ordering orderCancel(Long id) {
        Ordering ordering = orderingRepository.findById(id).orElseThrow(() -> new EntityNotFoundException("Ordering not found"));
        if (ordering.getOrderstatus() == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("이미 취소된 주문입니다.");
        }
        ordering.updateStatus(OrderStatus.CANCELLED);

        List<OrderDetail> sortedDetails = new ArrayList<>(ordering.getOrderDetails());
        sortedDetails.sort(Comparator.comparing(detail -> detail.getProduct().getId()));
        for (OrderDetail detail : sortedDetails) {
            Long productId = detail.getProduct().getId();
            int quantity = detail.getQuantity();
            Product product = stockProcessor.findProduct(productId);
            stockProcessor.restore(product, quantity);
        }

        // 취소당한 본인(구매자)에게 알림. DTO는 아직 세션이 열려있는 지금(트랜잭션 안)에서 미리 만들어서
        // 넘긴다 — 실제 전송은 비동기 스레드에서 이뤄지므로, 그쪽에서 지연로딩 엔티티를 건드리면 안 된다.
        OrderListResDto dto = ordering.fromEntityList();
        sseController.publishMessage(dto, dto.getMemberEmail(), "order-cancelled");
        return ordering;
    }
}
