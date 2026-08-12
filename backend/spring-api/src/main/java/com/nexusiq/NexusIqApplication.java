package com.nexusiq;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

// EnableScheduling backs streaming/SseEmitterRegistry's periodic heartbeat
// sweep (docs/API/API_DESIGN.md "SSE": "plus periodic heartbeat").
@EnableScheduling
@SpringBootApplication
public class NexusIqApplication {

	public static void main(String[] args) {
		SpringApplication.run(NexusIqApplication.class, args);
	}

}
