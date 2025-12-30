package com.galaxy.gateway.routes;

import java.net.URI;

import org.springframework.cloud.gateway.server.mvc.filter.CircuitBreakerFilterFunctions;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.function.RequestPredicates;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class Routes {

    @Bean
    public RouterFunction<ServerResponse> productServiceRoute() {
        return route("product_service").route(RequestPredicates.path("/api/product"), http("http://localhost:9080"))
                .filter(CircuitBreakerFilterFunctions.circuitBreaker("productServiceCircuitBreaker",
                        URI.create("forward:/fallbackRoute")))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> orderServiceRoute() {
        return route("order_service")
                .route(RequestPredicates.path("/api/order"), http("http://localhost:9081"))
                .filter(CircuitBreakerFilterFunctions.circuitBreaker("orderServiceCircuitBreaker",
                        URI.create("forward:/fallbackRoute")))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> inventoryServiceRoute() {
        return route("inventory_service")
                .route(RequestPredicates.path("/api/inventory"), http("http://localhost:9082"))
                .filter(CircuitBreakerFilterFunctions.circuitBreaker("inventoryServiceCircuitBreaker",
                        URI.create("forward:/fallbackRoute")))
                .build();
    }

    @Bean
    public RouterFunction<ServerResponse> fallbackRoute() {
        return route("fallbackRoute")
                .GET("/fallbackRoute",
                        request -> ServerResponse.status(HttpStatus.SERVICE_UNAVAILABLE)
                                .body("Service Unavailable, please try again later"))
                .build();
    }
}