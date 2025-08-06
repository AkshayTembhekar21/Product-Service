package com.eventsapi.ProductService.command.api.controller;

import java.util.UUID;

import org.axonframework.commandhandling.gateway.CommandGateway;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.eventsapi.ProductService.command.api.commands.CreateProductCommand;
import com.eventsapi.ProductService.command.api.model.ProductRestModel;

@RestController
@RequestMapping("/products")
public class ProductCommandController {

    @Autowired
    private CommandGateway commandGateway;

    @PostMapping
    public String addProduct(@RequestBody ProductRestModel product) {
        CreateProductCommand command = CreateProductCommand.builder()
            .productId(UUID.randomUUID().toString())
            .name(product.getName())
            .price(product.getPrice())
            .quantity(product.getQuantity())
            .build();

        String result = commandGateway.sendAndWait(command);
        return result;
    }

}
