package com.example.ordersystem;

import com.example.ordersystem.ordering.service.stock.RdbSyncStockProcessor;
import com.example.ordersystem.ordering.service.stock.StockProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class OrdersystemApplicationTests {

	@Autowired
	private StockProcessor stockProcessor;

	@Test
	void contextLoads() {
		assertThat(stockProcessor).isInstanceOf(RdbSyncStockProcessor.class);
	} 
 
}
