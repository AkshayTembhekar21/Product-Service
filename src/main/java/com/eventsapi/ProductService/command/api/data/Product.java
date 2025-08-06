package com.eventsapi.ProductService.command.api.data;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import lombok.Data;

@Data
@Entity
public class Product {

    @Id
    private String productId;
    private String name;
    private double price;
    private int quantity;
}
