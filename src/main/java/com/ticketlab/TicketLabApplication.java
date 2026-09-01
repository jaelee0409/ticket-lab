package com.ticketlab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TicketLabApplication {

	public static void main(String[] args) {
		SpringApplication.run(TicketLabApplication.class, args);
	}

}
