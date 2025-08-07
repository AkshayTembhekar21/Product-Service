# Complete SAGA Pattern Flow with Code Examples

## Architecture Overview
- **Order Service**: Port 8081, Server 1
- **Inventory Service**: Port 8082, Server 2  
- **Payment Service**: Port 8083, Server 3
- **Shipping Service**: Port 8084, Server 4
- **Saga Service**: Port 8085, Server 5 (or can run on any of the above servers)

## Complete Flow with Code - Step by Step

### Step 1: User calls Order Service REST API

```java
// User sends HTTP request
POST http://localhost:8081/orders
{
  "customerId": "CUST-123",
  "productId": "PROD-001",
  "quantity": 2,
  "amount": 1998.00
}
```

### Step 2: Order Service Controller receives request

```java
// OrderController.java
@RestController
@RequestMapping("/orders")
public class OrderController {
    @Autowired
    private CommandGateway commandGateway;

    @PostMapping
    public String createOrder(@RequestBody CreateOrderRequest request) {
        // Create command from HTTP request
        CreateOrderCommand command = CreateOrderCommand.builder()
            .orderId(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .productId(request.getProductId())
            .quantity(request.getQuantity())
            .amount(request.getAmount())
            .build();

        // Send command to CommandGateway
        return commandGateway.sendAndWait(command); // ← THIS IS WHERE CONTROL GOES TO AGGREGATE
    }
}
```

### Step 3: CommandGateway routes to OrderAggregate

```java
// OrderAggregate.java
@Aggregate
public class OrderAggregate {
    @AggregateIdentifier
    private String orderId;
    private String customerId;
    private String productId;
    private int quantity;
    private double amount;
    private OrderStatus status;

    public OrderAggregate() {
    }

    @CommandHandler
    public OrderAggregate(CreateOrderCommand command) {
        // Business validation
        if (command.getAmount() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        // Create event
        OrderCreatedEvent event = new OrderCreatedEvent();
        BeanUtils.copyProperties(command, event);
        event.setStatus(OrderStatus.CREATED);
        
        // Apply event - THIS IS WHERE THE MAGIC HAPPENS
        AggregateLifecycle.apply(event); // ← THIS CALLS BOTH EVENT HANDLERS
    }

    // THIS GETS CALLED FIRST
    @EventSourcingHandler
    public void on(OrderCreatedEvent event) {
        this.orderId = event.getOrderId();
        this.customerId = event.getCustomerId();
        this.productId = event.getProductId();
        this.quantity = event.getQuantity();
        this.amount = event.getAmount();
        this.status = event.getStatus();
    }
}
```

### Step 4: AggregateLifecycle.apply() calls Event Handler

```java
// OrderEventsHandler.java
@Component
public class OrderEventsHandler {
    private final OrderRepo orderRepo;

    public OrderEventsHandler(OrderRepo orderRepo) {
        this.orderRepo = orderRepo;
    }

    // THIS GETS CALLED SECOND
    @EventHandler
    public void on(OrderCreatedEvent event) {
        // Save to database
        Order order = new Order();
        BeanUtils.copyProperties(event, order);
        orderRepo.save(order);
        
        // Order is now saved in Order Service database
        // BUT OTHER SERVICES DON'T KNOW ABOUT IT YET
    }
}
```

### Step 5: How Saga Service gets notified

**THIS IS THE MISSING PIECE YOU ASKED ABOUT**

The Saga Service needs to be **running on the same server** as Order Service OR needs a **shared event store**. Here's how:

#### Option A: Saga Service runs on same server as Order Service

```java
// OrderServiceApplication.java (includes Saga)
@SpringBootApplication
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}

// OrderSaga.java (in the same Order Service)
@Saga
public class OrderSaga {
    
    private String orderId;
    private String productId;
    private int quantity;
    private double amount;
    private String customerId;
    
    // THIS GETS CALLED THIRD - same event, different handler
    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderCreatedEvent event) {
        // Store saga state
        this.orderId = event.getOrderId();
        this.productId = event.getProductId();
        this.quantity = event.getQuantity();
        this.amount = event.getAmount();
        this.customerId = event.getCustomerId();
        
        // NOW SEND HTTP REQUEST TO INVENTORY SERVICE
        ReserveInventoryCommand reserveCmd = new ReserveInventoryCommand();
        reserveCmd.setProductId(this.productId);
        reserveCmd.setQuantity(this.quantity);
        reserveCmd.setOrderId(this.orderId);
        reserveCmd.setCustomerId(this.customerId);
        
        // THIS IS WHERE CONTROL MOVES TO NEXT SERVICE
        commandGateway.send(reserveCmd); // ← HTTP POST to Inventory Service
    }
}
```

### Step 6: How HTTP request goes to Inventory Service

```java
// Saga sends HTTP request to Inventory Service
// This happens automatically when you call commandGateway.send()

// The actual HTTP request looks like this:
POST http://localhost:8082/inventory/reserve
{
  "productId": "PROD-001",
  "quantity": 2,
  "orderId": "ORDER-123",
  "customerId": "CUST-123"
}
```

### Step 7: Inventory Service receives HTTP request

```java
// InventoryController.java
@RestController
@RequestMapping("/inventory")
public class InventoryController {
    @Autowired
    private CommandGateway commandGateway;

    // THIS ENDPOINT RECEIVES THE HTTP REQUEST FROM SAGA
    @PostMapping("/reserve")
    public String reserveInventory(@RequestBody ReserveInventoryCommand command) {
        // Send command to CommandGateway
        return commandGateway.sendAndWait(command); // ← CONTROL GOES TO INVENTORY AGGREGATE
    }
}
```

### Step 8: Inventory Service processes command

```java
// InventoryAggregate.java
@Aggregate
public class InventoryAggregate {
    @AggregateIdentifier
    private String productId;
    private int availableQuantity;
    private int reservedQuantity;

    public InventoryAggregate() {
    }

    @CommandHandler
    public void handle(ReserveInventoryCommand command) {
        // Business validation
        if (this.availableQuantity < command.getQuantity()) {
            throw new IllegalStateException("Insufficient inventory");
        }

        // Create event
        InventoryReservedEvent event = new InventoryReservedEvent();
        event.setProductId(this.productId);
        event.setOrderId(command.getOrderId());
        event.setQuantity(command.getQuantity());
        event.setCustomerId(command.getCustomerId());
        
        // Apply event
        AggregateLifecycle.apply(event); // ← THIS CALLS BOTH EVENT HANDLERS
    }

    // THIS GETS CALLED FIRST
    @EventSourcingHandler
    public void on(InventoryReservedEvent event) {
        this.availableQuantity -= event.getQuantity();
        this.reservedQuantity += event.getQuantity();
    }
}
```

### Step 9: Inventory Service Event Handler

```java
// InventoryEventsHandler.java
@Component
public class InventoryEventsHandler {
    private final InventoryRepo inventoryRepo;

    public InventoryEventsHandler(InventoryRepo inventoryRepo) {
        this.inventoryRepo = inventoryRepo;
    }

    // THIS GETS CALLED SECOND
    @EventHandler
    public void on(InventoryReservedEvent event) {
        Inventory inventory = inventoryRepo.findById(event.getProductId()).orElseThrow();
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - event.getQuantity());
        inventory.setReservedQuantity(inventory.getReservedQuantity() + event.getQuantity());
        inventoryRepo.save(inventory);
        
        // Inventory is now updated in Inventory Service database
    }
}
```

### Step 10: Saga Service receives Inventory event

```java
// OrderSaga.java (back in Order Service)
@Saga
public class OrderSaga {
    
    // THIS GETS CALLED THIRD - receives event from Inventory Service
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(InventoryReservedEvent event) {
        // Send HTTP command to Payment Service
        ProcessPaymentCommand paymentCmd = new ProcessPaymentCommand();
        paymentCmd.setOrderId(this.orderId);
        paymentCmd.setAmount(this.amount);
        paymentCmd.setCustomerId(this.customerId);
        paymentCmd.setShippingAddress(event.getShippingAddress());
        
        // CONTROL MOVES TO PAYMENT SERVICE
        commandGateway.send(paymentCmd); // ← HTTP POST to Payment Service
    }
}
```

## Complete Flow Summary

1. **User** → HTTP POST to Order Service
2. **Order Controller** → Creates command, sends to CommandGateway
3. **CommandGateway** → Routes to OrderAggregate @CommandHandler
4. **OrderAggregate** → Creates event, calls AggregateLifecycle.apply()
5. **AggregateLifecycle.apply()** calls:
   - **@EventSourcingHandler** (rebuilds aggregate state)
   - **@EventHandler** (saves to database)
   - **@SagaEventHandler** (triggers saga)
6. **Saga** → Sends HTTP POST to Inventory Service
7. **Inventory Controller** → Receives HTTP request, creates command
8. **InventoryAggregate** → Processes command, creates event
9. **Inventory Event Handler** → Saves to database
10. **Saga** → Receives Inventory event, sends HTTP POST to Payment Service
11. **Process continues** through Payment and Shipping services

## Key Points:

- **HTTP requests** move control between services
- **Events** stay within each service
- **Saga** coordinates via HTTP commands
- **Each service** has its own database and event store
- **Saga** can run on same server as any service OR separate server

## Why We Need Saga Service

The **Saga Service** is needed because **events from one service cannot automatically reach other services**. Here's the problem:

### The Problem Without Saga
```java
// Order Service creates order
@CommandHandler
public OrderAggregate(CreateOrderCommand command) {
    OrderCreatedEvent event = new OrderCreatedEvent();
    AggregateLifecycle.apply(event); // This event stays ONLY in Order Service
}

// Inventory Service has NO WAY to know about this event
// Payment Service has NO WAY to know about this event
// Shipping Service has NO WAY to know about this event
```

### The Solution With Saga
```java
// Saga Service listens to events from ALL services
@Saga
public class OrderSaga {
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderCreatedEvent event) {
        // When Order Service publishes OrderCreatedEvent
        // Saga receives it and tells other services what to do
        commandGateway.send(reserveCmd); // Tell Inventory Service
    }
}
```

## The Two Different Event Handlers

### 1. Order Service Event Handler (Local Processing)
```java
// This runs INSIDE Order Service
@Component
public class OrderEventsHandler {
    @EventHandler
    public void on(OrderCreatedEvent event) {
        // Save order to Order Service database
        orderRepo.save(order);
    }
}
```

### 2. Saga Service Event Handler (Cross-Service Coordination)
```java
// This runs INSIDE Saga Service
@Saga
public class OrderSaga {
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderCreatedEvent event) {
        // Tell other services what to do
        commandGateway.send(reserveCmd); // HTTP call to Inventory Service
    }
}
```

## Complete Simple Flow

### Step 1: User sends request to Order Service
```
POST http://server1:8081/orders
```

### Step 2: Order Service processes locally
```java
// OrderAggregate creates event
OrderCreatedEvent event = new OrderCreatedEvent();
AggregateLifecycle.apply(event);

// TWO things happen:
// 1. OrderEventsHandler saves to database (local)
// 2. OrderSaga receives event (cross-service)
```

### Step 3: Saga Service coordinates other services
```java
// Saga receives OrderCreatedEvent
// Saga sends HTTP command to Inventory Service
// Inventory Service processes and sends back event
// Saga receives InventoryReservedEvent
// Saga sends HTTP command to Payment Service
// And so on...
```

## Why Not Just Use Events Between Services?

**Events cannot travel between services automatically** because:
- Each service has its own event store
- Each service runs on different servers
- There's no shared memory or database

**Saga Service acts as the "bridge"** that:
- Listens to events from all services
- Sends HTTP commands to other services
- Coordinates the entire workflow

## Simplified Architecture

```
User → Order Service (creates order)
     ↓
Order Service publishes OrderCreatedEvent
     ↓
Saga Service receives OrderCreatedEvent
     ↓
Saga Service sends HTTP command to Inventory Service
     ↓
Inventory Service processes and publishes InventoryReservedEvent
     ↓
Saga Service receives InventoryReservedEvent
     ↓
Saga Service sends HTTP command to Payment Service
     ↓
And so on...
```

## The Key Point

**Without Saga Service**: Events stay within each service, no coordination possible.

**With Saga Service**: Events trigger HTTP commands to other services, enabling coordination.

The Saga Service is the **orchestrator** that makes distributed transactions work across multiple services.

## Complete Service Implementations

### Order Service (Port 8081, Server 1)

#### OrderController.java
```java
@RestController
@RequestMapping("/orders")
public class OrderController {
    @Autowired
    private CommandGateway commandGateway;

    @PostMapping
    public String createOrder(@RequestBody CreateOrderRequest request) {
        CreateOrderCommand command = CreateOrderCommand.builder()
            .orderId(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .productId(request.getProductId())
            .quantity(request.getQuantity())
            .amount(request.getAmount())
            .build();

        return commandGateway.sendAndWait(command);
    }

    // This endpoint receives commands from Saga Service
    @PostMapping("/confirm")
    public String confirmOrder(@RequestBody ConfirmOrderCommand command) {
        return commandGateway.sendAndWait(command);
    }

    @PostMapping("/cancel")
    public String cancelOrder(@RequestBody CancelOrderCommand command) {
        return commandGateway.sendAndWait(command);
    }
}
```

#### OrderAggregate.java
```java
@Aggregate
public class OrderAggregate {
    @AggregateIdentifier
    private String orderId;
    private String customerId;
    private String productId;
    private int quantity;
    private double amount;
    private OrderStatus status;

    public OrderAggregate() {
    }

    @CommandHandler
    public OrderAggregate(CreateOrderCommand command) {
        // Business validation
        if (command.getAmount() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        
        // Create event
        OrderCreatedEvent event = new OrderCreatedEvent();
        BeanUtils.copyProperties(command, event);
        event.setStatus(OrderStatus.CREATED);
        
        // Apply event - this stores it in event store AND publishes to event bus
        AggregateLifecycle.apply(event);
    }

    @CommandHandler
    public void handle(ConfirmOrderCommand command) {
        OrderConfirmedEvent event = new OrderConfirmedEvent();
        event.setOrderId(this.orderId);
        event.setStatus(OrderStatus.CONFIRMED);
        AggregateLifecycle.apply(event);
    }

    @CommandHandler
    public void handle(CancelOrderCommand command) {
        OrderCancelledEvent event = new OrderCancelledEvent();
        event.setOrderId(this.orderId);
        event.setReason(command.getReason());
        event.setStatus(OrderStatus.CANCELLED);
        AggregateLifecycle.apply(event);
    }

    @EventSourcingHandler
    public void on(OrderCreatedEvent event) {
        this.orderId = event.getOrderId();
        this.customerId = event.getCustomerId();
        this.productId = event.getProductId();
        this.quantity = event.getQuantity();
        this.amount = event.getAmount();
        this.status = event.getStatus();
    }

    @EventSourcingHandler
    public void on(OrderConfirmedEvent event) {
        this.status = event.getStatus();
    }

    @EventSourcingHandler
    public void on(OrderCancelledEvent event) {
        this.status = event.getStatus();
    }
}
```

#### OrderEventsHandler.java
```java
@Component
public class OrderEventsHandler {
    private final OrderRepo orderRepo;

    public OrderEventsHandler(OrderRepo orderRepo) {
        this.orderRepo = orderRepo;
    }

    @EventHandler
    public void on(OrderCreatedEvent event) {
        // Save to local database
        Order order = new Order();
        BeanUtils.copyProperties(event, order);
        orderRepo.save(order);
    }

    @EventHandler
    public void on(OrderConfirmedEvent event) {
        Order order = orderRepo.findById(event.getOrderId()).orElseThrow();
        order.setStatus(OrderStatus.CONFIRMED);
        orderRepo.save(order);
    }

    @EventHandler
    public void on(OrderCancelledEvent event) {
        Order order = orderRepo.findById(event.getOrderId()).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(event.getReason());
        orderRepo.save(order);
    }
}
```

### Inventory Service (Port 8082, Server 2)

#### InventoryController.java
```java
@RestController
@RequestMapping("/inventory")
public class InventoryController {
    @Autowired
    private CommandGateway commandGateway;

    // This endpoint receives commands from Saga Service
    @PostMapping("/reserve")
    public String reserveInventory(@RequestBody ReserveInventoryCommand command) {
        return commandGateway.sendAndWait(command);
    }

    @PostMapping("/release")
    public String releaseInventory(@RequestBody ReleaseInventoryCommand command) {
        return commandGateway.sendAndWait(command);
    }

    // Create initial inventory
    @PostMapping("/create")
    public String createInventory(@RequestBody CreateInventoryCommand command) {
        return commandGateway.sendAndWait(command);
    }
}
```

#### InventoryAggregate.java
```java
@Aggregate
public class InventoryAggregate {
    @AggregateIdentifier
    private String productId;
    private int availableQuantity;
    private int reservedQuantity;

    public InventoryAggregate() {
    }

    @CommandHandler
    public InventoryAggregate(CreateInventoryCommand command) {
        InventoryCreatedEvent event = new InventoryCreatedEvent();
        BeanUtils.copyProperties(command, event);
        AggregateLifecycle.apply(event);
    }

    @CommandHandler
    public void handle(ReserveInventoryCommand command) {
        // Business validation
        if (this.availableQuantity < command.getQuantity()) {
            throw new IllegalStateException("Insufficient inventory");
        }

        // Create event
        InventoryReservedEvent event = new InventoryReservedEvent();
        event.setProductId(this.productId);
        event.setOrderId(command.getOrderId());
        event.setQuantity(command.getQuantity());
        event.setCustomerId(command.getCustomerId());
        AggregateLifecycle.apply(event);
    }

    @CommandHandler
    public void handle(ReleaseInventoryCommand command) {
        InventoryReleasedEvent event = new InventoryReleasedEvent();
        event.setProductId(this.productId);
        event.setOrderId(command.getOrderId());
        event.setQuantity(command.getQuantity());
        AggregateLifecycle.apply(event);
    }

    @EventSourcingHandler
    public void on(InventoryCreatedEvent event) {
        this.productId = event.getProductId();
        this.availableQuantity = event.getInitialQuantity();
        this.reservedQuantity = 0;
    }

    @EventSourcingHandler
    public void on(InventoryReservedEvent event) {
        this.availableQuantity -= event.getQuantity();
        this.reservedQuantity += event.getQuantity();
    }

    @EventSourcingHandler
    public void on(InventoryReleasedEvent event) {
        this.availableQuantity += event.getQuantity();
        this.reservedQuantity -= event.getQuantity();
    }
}
```

#### InventoryEventsHandler.java
```java
@Component
public class InventoryEventsHandler {
    private final InventoryRepo inventoryRepo;

    public InventoryEventsHandler(InventoryRepo inventoryRepo) {
        this.inventoryRepo = inventoryRepo;
    }

    @EventHandler
    public void on(InventoryCreatedEvent event) {
        Inventory inventory = new Inventory();
        BeanUtils.copyProperties(event, inventory);
        inventoryRepo.save(inventory);
    }

    @EventHandler
    public void on(InventoryReservedEvent event) {
        Inventory inventory = inventoryRepo.findById(event.getProductId()).orElseThrow();
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() - event.getQuantity());
        inventory.setReservedQuantity(inventory.getReservedQuantity() + event.getQuantity());
        inventoryRepo.save(inventory);
    }

    @EventHandler
    public void on(InventoryReleasedEvent event) {
        Inventory inventory = inventoryRepo.findById(event.getProductId()).orElseThrow();
        inventory.setAvailableQuantity(inventory.getAvailableQuantity() + event.getQuantity());
        inventory.setReservedQuantity(inventory.getReservedQuantity() - event.getQuantity());
        inventoryRepo.save(inventory);
    }
}
```

### Payment Service (Port 8083, Server 3)

#### PaymentController.java
```java
@RestController
@RequestMapping("/payments")
public class PaymentController {
    @Autowired
    private CommandGateway commandGateway;

    // This endpoint receives commands from Saga Service
    @PostMapping("/process")
    public String processPayment(@RequestBody ProcessPaymentCommand command) {
        return commandGateway.sendAndWait(command);
    }
}
```

#### PaymentAggregate.java
```java
@Aggregate
public class PaymentAggregate {
    @AggregateIdentifier
    private String paymentId;
    private String orderId;
    private String customerId;
    private double amount;
    private PaymentStatus status;

    public PaymentAggregate() {
    }

    @CommandHandler
    public PaymentAggregate(ProcessPaymentCommand command) {
        // Simulate payment processing (in real system, call payment gateway)
        if (Math.random() < 0.1) { // 10% chance of failure
            throw new PaymentFailedException("Payment processing failed");
        }

        // Create event
        PaymentProcessedEvent event = new PaymentProcessedEvent();
        event.setPaymentId(UUID.randomUUID().toString());
        event.setOrderId(command.getOrderId());
        event.setCustomerId(command.getCustomerId());
        event.setAmount(command.getAmount());
        event.setStatus(PaymentStatus.PROCESSED);
        event.setShippingAddress(command.getShippingAddress());
        AggregateLifecycle.apply(event);
    }

    @EventSourcingHandler
    public void on(PaymentProcessedEvent event) {
        this.paymentId = event.getPaymentId();
        this.orderId = event.getOrderId();
        this.customerId = event.getCustomerId();
        this.amount = event.getAmount();
        this.status = event.getStatus();
    }
}
```

#### PaymentEventsHandler.java
```java
@Component
public class PaymentEventsHandler {
    private final PaymentRepo paymentRepo;

    public PaymentEventsHandler(PaymentRepo paymentRepo) {
        this.paymentRepo = paymentRepo;
    }

    @EventHandler
    public void on(PaymentProcessedEvent event) {
        Payment payment = new Payment();
        BeanUtils.copyProperties(event, payment);
        paymentRepo.save(payment);
    }
}
```

### Shipping Service (Port 8084, Server 4)

#### ShippingController.java
```java
@RestController
@RequestMapping("/shipping")
public class ShippingController {
    @Autowired
    private CommandGateway commandGateway;

    // This endpoint receives commands from Saga Service
    @PostMapping("/arrange")
    public String arrangeShipping(@RequestBody ArrangeShippingCommand command) {
        return commandGateway.sendAndWait(command);
    }
}
```

#### ShippingAggregate.java
```java
@Aggregate
public class ShippingAggregate {
    @AggregateIdentifier
    private String shippingId;
    private String orderId;
    private String productId;
    private int quantity;
    private String shippingAddress;
    private ShippingStatus status;

    public ShippingAggregate() {
    }

    @CommandHandler
    public ShippingAggregate(ArrangeShippingCommand command) {
        // Create event
        ShippingArrangedEvent event = new ShippingArrangedEvent();
        event.setShippingId(UUID.randomUUID().toString());
        event.setOrderId(command.getOrderId());
        event.setProductId(command.getProductId());
        event.setQuantity(command.getQuantity());
        event.setShippingAddress(command.getShippingAddress());
        event.setStatus(ShippingStatus.ARRANGED);
        AggregateLifecycle.apply(event);
    }

    @EventSourcingHandler
    public void on(ShippingArrangedEvent event) {
        this.shippingId = event.getShippingId();
        this.orderId = event.getOrderId();
        this.productId = event.getProductId();
        this.quantity = event.getQuantity();
        this.shippingAddress = event.getShippingAddress();
        this.status = event.getStatus();
    }
}
```

#### ShippingEventsHandler.java
```java
@Component
public class ShippingEventsHandler {
    private final ShippingRepo shippingRepo;

    public ShippingEventsHandler(ShippingRepo shippingRepo) {
        this.shippingRepo = shippingRepo;
    }

    @EventHandler
    public void on(ShippingArrangedEvent event) {
        Shipping shipping = new Shipping();
        BeanUtils.copyProperties(event, shipping);
        shippingRepo.save(shipping);
    }
}
```

### Saga Service (Port 8085, Server 5)

#### SagaServiceApplication.java
```java
@SpringBootApplication
public class SagaServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(SagaServiceApplication.class, args);
    }
}
```

#### OrderSaga.java
```java
@Saga
public class OrderSaga {
    
    private String orderId;
    private String productId;
    private int quantity;
    private double amount;
    private String customerId;
    
    // Step 1: Start the saga when order is created
    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderCreatedEvent event) {
        // Store saga state
        this.orderId = event.getOrderId();
        this.productId = event.getProductId();
        this.quantity = event.getQuantity();
        this.amount = event.getAmount();
        this.customerId = event.getCustomerId();
        
        // Send HTTP command to Inventory Service (Server 2, Port 8082)
        ReserveInventoryCommand reserveCmd = new ReserveInventoryCommand();
        reserveCmd.setProductId(this.productId);
        reserveCmd.setQuantity(this.quantity);
        reserveCmd.setOrderId(this.orderId);
        reserveCmd.setCustomerId(this.customerId);
        
        commandGateway.send(reserveCmd); // ← HTTP POST to http://server2:8082/inventory/reserve
    }
    
    // Step 2: When inventory is reserved, process payment
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(InventoryReservedEvent event) {
        // Send HTTP command to Payment Service (Server 3, Port 8083)
        ProcessPaymentCommand paymentCmd = new ProcessPaymentCommand();
        paymentCmd.setOrderId(this.orderId);
        paymentCmd.setAmount(this.amount);
        paymentCmd.setCustomerId(this.customerId);
        paymentCmd.setShippingAddress(event.getShippingAddress());
        
        commandGateway.send(paymentCmd); // ← HTTP POST to http://server3:8083/payments/process
    }
    
    // Step 3: When payment succeeds, arrange shipping
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(PaymentProcessedEvent event) {
        // Send HTTP command to Shipping Service (Server 4, Port 8084)
        ArrangeShippingCommand shippingCmd = new ArrangeShippingCommand();
        shippingCmd.setOrderId(this.orderId);
        shippingCmd.setProductId(this.productId);
        shippingCmd.setQuantity(this.quantity);
        shippingCmd.setShippingAddress(event.getShippingAddress());
        
        commandGateway.send(shippingCmd); // ← HTTP POST to http://server4:8084/shipping/arrange
    }
    
    // Step 4: When shipping is arranged, confirm order
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(ShippingArrangedEvent event) {
        // Send HTTP command back to Order Service (Server 1, Port 8081)
        ConfirmOrderCommand confirmCmd = new ConfirmOrderCommand();
        confirmCmd.setOrderId(this.orderId);
        
        commandGateway.send(confirmCmd); // ← HTTP POST to http://server1:8081/orders/confirm
        
        // Saga completed successfully
        SagaLifecycle.end();
    }
    
    // COMPENSATION: If payment fails, release inventory
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(PaymentFailedEvent event) {
        // Compensate by releasing inventory (undo step 4)
        ReleaseInventoryCommand releaseCmd = new ReleaseInventoryCommand();
        releaseCmd.setProductId(this.productId);
        releaseCmd.setQuantity(this.quantity);
        releaseCmd.setOrderId(this.orderId);
        
        commandGateway.send(releaseCmd);
        
        // Cancel the order (undo step 2)
        CancelOrderCommand cancelCmd = new CancelOrderCommand();
        cancelCmd.setOrderId(this.orderId);
        cancelCmd.setReason("Payment failed");
        
        commandGateway.send(cancelCmd);
        
        // End saga with failure
        SagaLifecycle.end();
    }
    
    // COMPENSATION: If inventory reservation fails, cancel order
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(InventoryReservationFailedEvent event) {
        // Cancel the order since inventory is not available
        CancelOrderCommand cancelCmd = new CancelOrderCommand();
        cancelCmd.setOrderId(this.orderId);
        cancelCmd.setReason("Insufficient inventory");
        
        commandGateway.send(cancelCmd); // ← HTTP POST to http://server1:8081/orders/cancel
        
        // End saga with failure
        SagaLifecycle.end();
    }
}
```

## Application Properties for Each Service

### Order Service (application.properties)
```properties
spring.application.name=OrderService
server.port=8081

# H2 Database for Order Service
spring.datasource.url=jdbc:h2:mem:orderdb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA Settings
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# Axon Event Store
axon.eventhandling.processors.default.mode=tracking
axon.eventstore.events.storage-engine=jdbc
axon.eventstore.events.jdbc.url=jdbc:h2:mem:order-events
axon.eventstore.events.jdbc.username=sa
axon.eventstore.events.jdbc.password=
```

### Inventory Service (application.properties)
```properties
spring.application.name=InventoryService
server.port=8082

# H2 Database for Inventory Service
spring.datasource.url=jdbc:h2:mem:inventorydb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA Settings
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# Axon Event Store
axon.eventhandling.processors.default.mode=tracking
axon.eventstore.events.storage-engine=jdbc
axon.eventstore.events.jdbc.url=jdbc:h2:mem:inventory-events
axon.eventstore.events.jdbc.username=sa
axon.eventstore.events.jdbc.password=
```

### Payment Service (application.properties)
```properties
spring.application.name=PaymentService
server.port=8083

# H2 Database for Payment Service
spring.datasource.url=jdbc:h2:mem:paymentdb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA Settings
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# Axon Event Store
axon.eventhandling.processors.default.mode=tracking
axon.eventstore.events.storage-engine=jdbc
axon.eventstore.events.jdbc.url=jdbc:h2:mem:payment-events
axon.eventstore.events.jdbc.username=sa
axon.eventstore.events.jdbc.password=
```

### Shipping Service (application.properties)
```properties
spring.application.name=ShippingService
server.port=8084

# H2 Database for Shipping Service
spring.datasource.url=jdbc:h2:mem:shippingdb
spring.datasource.driverClassName=org.h2.Driver
spring.datasource.username=sa
spring.datasource.password=

# JPA Settings
spring.jpa.database-platform=org.hibernate.dialect.H2Dialect
spring.jpa.hibernate.ddl-auto=update
spring.jpa.show-sql=true

# Axon Event Store
axon.eventhandling.processors.default.mode=tracking
axon.eventstore.events.storage-engine=jdbc
axon.eventstore.events.jdbc.url=jdbc:h2:mem:shipping-events
axon.eventstore.events.jdbc.username=sa
axon.eventstore.events.jdbc.password=
```

### Saga Service (application.properties)
```properties
spring.application.name=SagaService
server.port=8085

# Axon Event Store for Saga
axon.eventhandling.processors.default.mode=tracking
axon.eventstore.events.storage-engine=jdbc
axon.eventstore.events.jdbc.url=jdbc:h2:mem:saga-events
axon.eventstore.events.jdbc.username=sa
axon.eventstore.events.jdbc.password=

# Saga Configuration
axon.saga.repository=jpa
```

## What Happens If Something Fails?

If any step fails (like payment processing), the Saga automatically triggers compensation events to undo the completed steps:

```java
// OrderSaga.java - Compensation handling
@Saga
public class OrderSaga {
    
    // COMPENSATION: If payment fails, release inventory
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(PaymentFailedEvent event) {
        // Compensate by releasing inventory (undo step 4)
        ReleaseInventoryCommand releaseCmd = new ReleaseInventoryCommand();
        releaseCmd.setProductId(this.productId);
        releaseCmd.setQuantity(this.quantity);
        releaseCmd.setOrderId(this.orderId);
        
        commandGateway.send(releaseCmd);
        
        // Cancel the order (undo step 2)
        CancelOrderCommand cancelCmd = new CancelOrderCommand();
        cancelCmd.setOrderId(this.orderId);
        cancelCmd.setReason("Payment failed");
        
        commandGateway.send(cancelCmd);
        
        // End saga with failure
        SagaLifecycle.end();
    }
}
```

This is how the OrderSaga orchestrates the entire distributed transaction across multiple services, ensuring that either all steps succeed or the system returns to a consistent state through compensation. 