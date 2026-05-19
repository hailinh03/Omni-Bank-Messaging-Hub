package com.example.corebanking.controller;

import com.example.common.config.api.ApiResponse;
import com.example.corebanking.dto.HoldRequest;
import com.example.corebanking.dto.HoldResponse;
import com.example.corebanking.dto.ReleaseAndEntryRequest;
import com.example.corebanking.dto.ReleaseAndEntryResponse;
import com.example.corebanking.dto.ReleaseHoldRequest;
import com.example.corebanking.dto.ReleaseHoldResponse;
import com.example.corebanking.service.CoreBankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/core")
@RequiredArgsConstructor
@Slf4j
public class CoreBankingController {

    private final CoreBankingService coreBankingService;

    @PostMapping("/check-and-hold")
    public ResponseEntity<ApiResponse<HoldResponse>> CheckAndHold(@RequestBody HoldRequest request) {
        log.info("[ENDPOINT] Received POST /check-and-hold request - txId: {}, amount: {}", 
            request.getTxId(), request.getAmount());
        try {
            HoldResponse responseData = coreBankingService.processCheckAndHold(request);
            log.info("[ENDPOINT] Hold operation successful - holdId: {}, txId: {}", 
                responseData.getHoldId(), request.getTxId());
            return ResponseEntity.ok(ApiResponse.success("SUCCESS", responseData));
        } catch (Exception e) {
            log.error("[ENDPOINT] Hold operation failed - txId: {}", request.getTxId(), e);
            throw e;
        }
    }

    @PostMapping("/release-and-entry")
    public ResponseEntity<ApiResponse<ReleaseAndEntryResponse>> releaseAndEntry(@RequestBody ReleaseAndEntryRequest request) {
        log.info("[ENDPOINT] Received POST /release-and-entry request - txId: {}, holdId: {}", 
            request.getTxId(), request.getHoldId());
        try {
            ReleaseAndEntryResponse responseData = coreBankingService.processReleaseAndEntry(request);
            log.info("[ENDPOINT] Release and entry successful - entryId: {}, holdId: {}", 
                responseData.getEntryId(), request.getHoldId());
            return ResponseEntity.ok(ApiResponse.success("SUCCESS", responseData));
        } catch (Exception e) {
            log.error("[ENDPOINT] Release and entry failed - txId: {}", request.getTxId(), e);
            throw e;
        }
    }

    @PostMapping("/release-hold")
    public ResponseEntity<ApiResponse<ReleaseHoldResponse>> releaseHold(@RequestBody ReleaseHoldRequest request) {
        log.info("[ENDPOINT] Received POST /release-hold request - txId: {}, holdId: {}", 
            request.getTxId(), request.getHoldId());
        try {
            ReleaseHoldResponse responseData = coreBankingService.processReleaseHold(request);
            log.info("[ENDPOINT] Release hold successful - holdId: {}", request.getHoldId());
            return ResponseEntity.ok(ApiResponse.success("SUCCESS", responseData));
        } catch (Exception e) {
            log.error("[ENDPOINT] Release hold failed - txId: {}", request.getTxId(), e);
            throw e;
        }
    }
}
