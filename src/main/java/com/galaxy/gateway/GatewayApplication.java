package com.galaxy.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class GatewayApplication {

	public static void main(String[] args) {
		ConfigurableApplicationContext context = SpringApplication.run(GatewayApplication.class, args);
		
		// Add shutdown hook for graceful termination
		Runtime.getRuntime().addShutdownHook(new Thread(() -> {
			System.out.println("Shutting down gracefully...");
			context.close();
		}));
	}
}
