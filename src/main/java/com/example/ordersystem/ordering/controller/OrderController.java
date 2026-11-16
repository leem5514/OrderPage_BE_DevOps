package com.example.ordersystem.ordering.controller;

import com.example.ordersystem.common.dto.CommonResDto;
import com.example.ordersystem.ordering.domain.Ordering;
import com.example.ordersystem.ordering.dto.OrderListResDto;
import com.example.ordersystem.ordering.dto.OrderSaveReqDto;
import com.example.ordersystem.ordering.service.OrderingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class OrderController {
    private final OrderingService orderingService;


    public OrderController(OrderingService orderingService) {
        this.orderingService = orderingService;
    }

    @PostMapping("/order/create") // 생성
    public ResponseEntity<?> orderingCreate(@RequestBody List<OrderSaveReqDto> dto) {
        // 기존 배열 요청을 유지한다. 전체 항목 검증은 Service에서 부작용 전에 수행한다.
        Ordering ordering = orderingService.orderCreate(dto);
        CommonResDto commonResDto = new CommonResDto(HttpStatus.CREATED, "created order", ordering.getId());
        return new ResponseEntity<>(commonResDto, HttpStatus.CREATED);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/order/list")
    public ResponseEntity<?> orderingList() {
        List<OrderListResDto> orderList = orderingService.orderList();
        CommonResDto commonResDto = new CommonResDto(HttpStatus.OK, "success created", orderList);
        return new ResponseEntity<>(commonResDto, HttpStatus.OK);
    }

    // 내 주문만 볼 수 있는 myOrders
    @GetMapping("/order/myorders")
    public ResponseEntity<?> myOrderInfo() {
        List<OrderListResDto> orderList = orderingService.myOrders();
        CommonResDto commonResDto = new CommonResDto(HttpStatus.OK, "내 주문 내역에 접근합니다", orderList);
        return new ResponseEntity<>(commonResDto, HttpStatus.OK);
    }

    //admin 사용자가 주문취소 : /order/{id}/cancel -> orderstatus 만 변경
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/order/{id}/cancel")
    public ResponseEntity<?> orderCancel(@PathVariable Long id) {
        Ordering ordering = orderingService.orderCancel(id);
        CommonResDto commonResDto = new CommonResDto(HttpStatus.OK, "정상 취소 완료", ordering.getId());
        return new ResponseEntity<>(commonResDto, HttpStatus.OK);
    }


}
