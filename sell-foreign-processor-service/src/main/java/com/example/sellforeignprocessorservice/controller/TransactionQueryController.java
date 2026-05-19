package com.example.sellforeignprocessorservice.controller;

import com.example.common.config.api.ApiResponse;
import com.example.common.exception.BusinessException;
import com.example.sellforeignprocessorservice.dto.TransactionQueryResponse;
import com.example.sellforeignprocessorservice.service.TransactionQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fx")
@RequiredArgsConstructor
@Slf4j
public class TransactionQueryController {

    private final TransactionQueryService transactionQueryService;

    @GetMapping("/transactions/{txId}")
    public ResponseEntity<ApiResponse<TransactionQueryResponse>> getTransactionResult(@PathVariable("txId") String txId) {
        log.info("[ENDPOINT] Received GET /transactions/{} query request", txId);
        
        UUID parsedTxId;
        try {
            parsedTxId = UUID.fromString(txId);
            log.debug("Transaction ID parsed successfully - txId: {}", parsedTxId);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid transaction ID format - txId: {}", txId);
            throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid transaction ID format: " + txId);
        }

        try {
            TransactionQueryResponse result = transactionQueryService.getTransactionResult(parsedTxId);
            log.info("[ENDPOINT] Transaction query successful - txId: {}, status: {}", 
                parsedTxId, result.getTransactionStatus());
            return ResponseEntity.ok(ApiResponse.success("SUCCESS", result));
        } catch (Exception e) {
            log.error("[ENDPOINT] Transaction query failed - txId: {}", parsedTxId, e);
            throw e;
        }
    }
}
