package com.example.treasuryservice.service.Impl;

import com.example.common.config.api.ApiCode;
import com.example.common.enums.Currency;
import com.example.sellforeignprocessorservice.dto.TreasuryRateRequest;
import com.example.treasuryservice.client.FxRatesClient;
import com.example.treasuryservice.dto.TreasuryRateResponse;
import com.example.treasuryservice.exception.BusinessException;
import com.example.treasuryservice.service.ICurrencyExchangeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class CurrencyExchangeServiceImpl implements ICurrencyExchangeService {
    private final FxRatesClient fxRatesClient;

    @Override
    public TreasuryRateResponse processExchange(TreasuryRateRequest request) {
        log.info("Processing currency exchange request - txId: {}, {} to {}", 
            request.getTxId(), request.getBase(), request.getCurrencies());
        
        validateMessage(request);
        log.debug("Treasury rate request validation passed");

        BigDecimal rate;
        try {
            log.debug("Fetching exchange rate from FX Rates API - base: {}, target: {}",
                request.getBase(), request.getCurrencies());
            rate = fxRatesClient.getRate(
                    request.getBase(),
                    request.getCurrencies(),
                    1
            );
            log.debug("Exchange rate retrieved - rate: {} for {}/{}", rate, request.getBase(), request.getCurrencies());
        } catch (IllegalStateException ex) {
            if (ex.getMessage() != null && ex.getMessage().contains("Rate not found")) {
                log.warn("Invalid currency pair requested - base: {}, target: {}", request.getBase(), request.getCurrencies());
                throw new BusinessException(
                        HttpStatus.BAD_REQUEST,
                        ApiCode.FX_ERR_001,
                        "Invalid currency pair"
                );
            }
            log.error("Treasury service unavailable for txId: {}", request.getTxId(), ex);
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiCode.TREASURY_ERR_001,
                    "Treasury unavailable"
            );
        } catch (RuntimeException ex) {
            log.error("Unexpected error while fetching exchange rate for txId: {}", request.getTxId(), ex);
            throw new BusinessException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    ApiCode.TREASURY_ERR_001,
                    "Treasury unavailable"
            );
        }

        TreasuryRateResponse treasuryRateResponse = TreasuryRateResponse
                .builder()
              //  .txId(request.getTxId())
                .target(request.getCurrencies())
                .base(request.getBase())
                .rateExchange(rate)
                .timestamp(LocalDateTime.now())
                .build();

        log.info(" {} -> {} | rate={} | timestamp={}",
             //   treasuryRateResponse.getTxId(),
                treasuryRateResponse.getBase(),
                treasuryRateResponse.getTarget(),
                rate,
                treasuryRateResponse.getTimestamp());

        return treasuryRateResponse;
    }

    private void validateMessage(TreasuryRateRequest request) {
        log.debug("Validating treasury rate request");
        
        if (request == null || request.getTxId() == null) {
            log.warn("Treasury rate request validation failed: missing txId");
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    ApiCode.MISSING_FIELD,
                    "Missing required field"
            );
        }
        if (request.getCurrencies() == null || request.getBase() == null) {
            log.warn("Treasury rate request validation failed: missing currencies or base");
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    ApiCode.MISSING_FIELD,
                    "Missing required field"
            );
        }
        if (!Currency.isSupported(request.getBase())
                || !Currency.isSupported(request.getCurrencies())
                || request.getCurrencies().equalsIgnoreCase(request.getBase())) {
            log.warn("Invalid currency pair for txId: {} - base: {}, target: {}", 
                request.getTxId(), request.getBase(), request.getCurrencies());
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    ApiCode.FX_ERR_001,
                    "Invalid currency pair"
            );
        }
        log.debug("Treasury rate request validation passed");
    }
}
