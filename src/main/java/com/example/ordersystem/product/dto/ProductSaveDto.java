package com.example.ordersystem.product.dto;

import com.example.ordersystem.member.domain.Member;
import com.example.ordersystem.member.domain.Role;
import com.example.ordersystem.product.domain.Product;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.web.multipart.MultipartFile;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.PositiveOrZero;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProductSaveDto {
    @NotBlank(message = "name is essential")
    private String name;

    @NotBlank(message = "category is essential")
    private String category;

    @NotNull(message = "price is essential")
    @Positive(message = "price must be positive")
    private Integer price;

    @NotNull(message = "stockQuantity is essential")
    @PositiveOrZero(message = "stockQuantity must be zero or positive")
    private Integer stockQuantity;

    @NotNull(message = "productImage is essential")
    private MultipartFile productImage;

    public Product toEntity() {
        Product product = Product.builder()
                .name(this.name)
                .category(this.category)
                .price(this.price)
                .stockQuantity(this.stockQuantity)
//                .imagePath(imagePath)
                .build();
        return product;
    }


}
