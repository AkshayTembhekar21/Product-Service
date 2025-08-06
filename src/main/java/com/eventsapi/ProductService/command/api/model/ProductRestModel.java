package com.eventsapi.ProductService.command.api.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ProductRestModel {

    private String name;
    private double price;
    private int quantity;

}
