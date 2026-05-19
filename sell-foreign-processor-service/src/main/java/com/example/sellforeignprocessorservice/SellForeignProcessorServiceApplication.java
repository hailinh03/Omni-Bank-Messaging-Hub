package com.example.sellforeignprocessorservice;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableFeignClients
@Slf4j
public class SellForeignProcessorServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(SellForeignProcessorServiceApplication.class, args);
        log.info("Application [SELL-FOREIGN-PROCESSOR-SERVICE] started successfully");

    }

}
