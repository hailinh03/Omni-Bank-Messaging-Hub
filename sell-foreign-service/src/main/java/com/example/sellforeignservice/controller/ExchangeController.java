package com.example.sellforeignservice.controller;

import com.example.common.config.api.ApiResponse;
import com.example.sellforeignservice.dto.request.SellForeignTransactionRequest;
import com.example.sellforeignservice.dto.response.SellForeignTransactionResponse;
import com.example.sellforeignservice.service.SellForeignTransactionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/fx")
@Slf4j
@RequiredArgsConstructor
public class ExchangeController {
    private final SellForeignTransactionService sellForeignTransactionService;

    @PostMapping("/exchange")
    public ResponseEntity<ApiResponse<SellForeignTransactionResponse>> exchangeSellForeignTransaction(
            @RequestBody @Valid SellForeignTransactionRequest request) {
        log.info("[ENDPOINT] Received POST /exchange request - idempotencyKey: {}, customerId: {}, {} to {}, amount: {}",
            request.getIdempotencyKey(), request.getCustomerId(),
            request.getBaseCurrency(), request.getTargetCurrency(), request.getAmount());
        try {
            ApiResponse<SellForeignTransactionResponse> response = sellForeignTransactionService.exchange(request);
            log.info("[ENDPOINT] Exchange request accepted for processing - idempotencyKey: {}", 
                request.getIdempotencyKey());
            return new ResponseEntity<>(response, HttpStatus.ACCEPTED);
        } catch (Exception e) {
            log.error("[ENDPOINT] Exchange request processing failed - idempotencyKey: {}", 
                request.getIdempotencyKey(), e);
            throw e;
        }
    }
}
