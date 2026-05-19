package com.example.sellforeignservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@Slf4j
@SpringBootApplication
public class SellForeignServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SellForeignServiceApplication.class, args);
        log.info("Application [SELL-FOREIGN-SERVICE] started successfully");

    }

}
