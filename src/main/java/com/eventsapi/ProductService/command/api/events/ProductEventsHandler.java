package com.eventsapi.ProductService.command.api.events;

import org.axonframework.eventhandling.EventHandler;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;

import com.eventsapi.ProductService.command.api.data.Product;
import com.eventsapi.ProductService.command.api.data.ProductRepo;

@Component
public class ProductEventsHandler {

    private final ProductRepo productRepo;

    public ProductEventsHandler(ProductRepo productRepo) {
        this.productRepo = productRepo;
    }

    @EventHandler
    public void on(ProductCreatedEvent event) {
        Product product = new Product();
        BeanUtils.copyProperties(event, product);
        productRepo.save(product);
    }

}
