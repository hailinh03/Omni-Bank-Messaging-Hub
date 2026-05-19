package com.example.treasuryservice.controller;

import com.example.common.config.api.ApiResponse;
import com.example.sellforeignprocessorservice.dto.ExternalApiResponse;
import com.example.sellforeignprocessorservice.dto.TreasuryRateRequest;
import com.example.treasuryservice.dto.TreasuryRateResponse;
import com.example.treasuryservice.service.Impl.CurrencyExchangeServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/treasury")
@RequiredArgsConstructor
@Slf4j
public class TreasuryController {
    private final CurrencyExchangeServiceImpl currencyExchangeService;

    @PostMapping("/rate")
    public ResponseEntity<ApiResponse<TreasuryRateResponse>> getRate(@RequestBody TreasuryRateRequest request) {
        log.info("[ENDPOINT] Received POST /rate request - txId: {}, {} to {}", 
            request.getTxId(), request.getBase(), request.getCurrencies());
        try {
            TreasuryRateResponse treasuryRateResponse = currencyExchangeService.processExchange(request);
            log.info("[ENDPOINT] Exchange rate request successful - txId: {}, rate: {}", 
                request.getTxId(), treasuryRateResponse.getRateExchange());
            return ResponseEntity.ok(ApiResponse.success("SUCCESS", treasuryRateResponse));
        } catch (Exception e) {
            log.error("[ENDPOINT] Exchange rate request failed - txId: {}", request.getTxId(), e);
            throw e;
        }
    }
}

