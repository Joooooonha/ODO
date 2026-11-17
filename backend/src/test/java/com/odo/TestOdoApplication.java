package com.odo;

import org.springframework.boot.SpringApplication;

public class TestOdoApplication {

	public static void main(String[] args) {
		SpringApplication.from(OdoApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
