package com.nexusiq;

import org.springframework.boot.SpringApplication;

public class TestNexusIqApplication {

	public static void main(String[] args) {
		SpringApplication.from(NexusIqApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
