package com.example.treasuryservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@Slf4j
@SpringBootApplication
public class TreasuryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TreasuryServiceApplication.class, args);
        log.info("Application [TREASURY-SERVICE] started successfully");
    }

}
