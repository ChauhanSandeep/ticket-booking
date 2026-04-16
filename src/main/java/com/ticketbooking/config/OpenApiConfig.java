package com.ticketbooking.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ticketBookingOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("Ticket Booking System API")
                        .description("Backend API for booking event tickets with a hold-then-confirm reservation pattern. "
                                + "Supports temporary seat holds, booking confirmation, automatic hold expiry, "
                                + "and concurrent booking safety via pessimistic locking.")
                        .version("1.0.0"));
    }
}
