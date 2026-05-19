package com.example.sellforeignprocessorservice.service.impl;

import com.example.common.dto.message.NotificationEvent;
import com.example.common.dto.message.SellForeignMessage;
import com.example.sellforeignprocessorservice.client.CoreBankingClient;
import com.example.sellforeignprocessorservice.client.TreasuryClient;
import com.example.sellforeignprocessorservice.dto.*;
import com.example.sellforeignprocessorservice.entity.SellForeignTransaction;
import com.example.sellforeignprocessorservice.entity.TransactionDetail;
import com.example.sellforeignprocessorservice.enums.TransactionStatus;
import com.example.sellforeignprocessorservice.publisher.NotificationEventPublisher;
import com.example.sellforeignprocessorservice.repository.TransactionDetailRepository;
import com.example.sellforeignprocessorservice.repository.TransactionRepository;
import com.example.sellforeignprocessorservice.service.SellForeignProcessorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class SellForeignProcessorServiceImpl implements SellForeignProcessorService {

    private final TransactionRepository transactionRepository;
    private final TransactionDetailRepository transactionDetailRepository;
    private final TreasuryClient treasuryClient;
    private final CoreBankingClient coreBankingClient;
    private final NotificationEventPublisher notificationEventPublisher;

    @Value("${key.throw-exception}")
    private String IDEMPO_KEY_THROW_E;

    @Override
    public void processTransaction(SellForeignMessage message) {
        log.info("Starting transaction processing - idempotencyKey: {}, customerId: {}, {} to {}, amount: {}",
            message.getIdempotencyKey(), message.getOwnerId(),
            message.getBaseCurrency(), message.getTargetCurrency(), message.getAmount());

        SellForeignTransaction transaction = initTransaction(message);
        // Duplicate deliveries must not replay completed money movement.
        if (!(transaction.getStatus() == TransactionStatus.PROCESSING)) {
            log.info("Skipping already terminal transaction [{}] with status [{}]", transaction.getTxId(), transaction.getStatus());
            return;
        }
        log.debug("Transaction initialized with txId: {}, status: PROCESSING", transaction.getTxId());
        
        initTransactionDetail(message, transaction);
        log.debug("Transaction detail initialized for txId: {}", transaction.getTxId());
        
        HoldResponse holdData = null;
        BigDecimal convertedAmount = null;

        try {
            log.info("Step 1: Holding balance for txId: {}", transaction.getTxId());
            holdData = holdBalance(message, transaction);

            log.info("Step 2: Getting exchange rate for txId: {}", transaction.getTxId());
            TreasuryRateResponse rateData = getExchangeRate(message, transaction);

            log.info("Step 3: Calculating converted amount for txId: {}", transaction.getTxId());
            convertedAmount = calculateConvertedAmount(message, rateData);
            log.debug("Converted amount calculated - source: {}, converted: {}, rate: {}", 
                message.getAmount(), convertedAmount, rateData.getRateExchange());

            log.info("Step 4: Updating transaction detail with rate for txId: {}", transaction.getTxId());
            updateTransactionDetailWithRate(transaction.getTxId(), rateData.getRateExchange(), convertedAmount);

            log.info("Step 5: Releasing hold and creating entry for txId: {}", transaction.getTxId());
            releaseHoldAndCreateEntry(message, holdData, rateData, transaction);

            log.info("Step 6: Marking transaction as successful for txId: {}", transaction.getTxId());
            markTransactionSuccess(transaction, transaction.getTxId());
            log.info("Transaction [{}] completed successfully - converted amount: {}", transaction.getTxId(), convertedAmount);

            publishSuccessEvent(message, convertedAmount, transaction);

        } catch (Exception e) {
            log.error("Transaction [{}] failed at step: {}", transaction.getTxId(), e.getMessage(), e);

            // If money was held, release it before marking the transaction failed.
            log.info("Initiating compensation for txId: {}", transaction.getTxId());
            compensateHoldIfNeeded(message, holdData, transaction);
            markTransactionFailed(transaction, message, e);
        }
    }

    private HoldResponse holdBalance(SellForeignMessage message, SellForeignTransaction transaction) {
        log.debug("Calling core-banking service to hold funds for txId: {}", transaction.getTxId());
        
        HoldRequest holdRequest = HoldRequest.builder()
                .txId(transaction.getTxId().toString())
                .accountNumberId(message.getAccountNumberId())
                .ownerId(message.getOwnerId())
                .currency(message.getBaseCurrency().name())
                .amount(message.getAmount())
                .build();

        try {
            ExternalApiResponse<HoldResponse> holdResponse = coreBankingClient.checkAndHold(holdRequest);
            HoldResponse holdData = holdResponse.getData();
            log.info("Hold success [{}] for txId [{}] - amount: {}, currency: {}", 
                holdData.getHoldId(), transaction.getTxId(), holdData.getHeldAmount(), holdData.getCurrency());
            return holdData;
        } catch (Exception e) {
            log.error("Failed to hold balance for txId: {}", transaction.getTxId(), e);
            throw e;
        }
    }

    private TreasuryRateResponse getExchangeRate(SellForeignMessage message, SellForeignTransaction transaction) {
        log.debug("Calling treasury service to get exchange rate for txId: {}", transaction.getTxId());
        
        TreasuryRateRequest rateRequest = TreasuryRateRequest.builder()
                .txId(transaction.getTxId().toString())
                .base(message.getBaseCurrency().name())
                .currencies(message.getTargetCurrency().name())
                .build();

        try {
            ExternalApiResponse<TreasuryRateResponse> rateResponse = treasuryClient.getRate(rateRequest);
            TreasuryRateResponse rateData = rateResponse.getData();

            // Testing exception scenario
            if (IDEMPO_KEY_THROW_E.equals(message.getIdempotencyKey())) {
                log.warn("Forced exception for testing - idempotencyKey: {}", message.getIdempotencyKey());
                throw new RuntimeException("Forced exception at getExchangeRate for testing");
            }

            log.info("Exchange rate retrieved for txId: {} - {} to {}, rate: {}, timestamp: {}",
                    transaction.getTxId(), rateData.getBase(), rateData.getTarget(),
                    rateData.getRateExchange(), rateData.getTimestamp());

            return rateData;
        } catch (Exception e) {
            log.error("Failed to get exchange rate for txId: {}", transaction.getTxId(), e);
            throw e;
        }
    }
    private BigDecimal calculateConvertedAmount(
            SellForeignMessage message,
            TreasuryRateResponse rateData
    ) {
        return message.getAmount().multiply(rateData.getRateExchange());
    }


    private ReleaseAndEntryResponse releaseHoldAndCreateEntry(
            SellForeignMessage message,
            HoldResponse holdData,
            TreasuryRateResponse rateData,
            SellForeignTransaction transaction
    ) {
        ReleaseAndEntryRequest releaseRequest = ReleaseAndEntryRequest.builder()
                .txId(transaction.getTxId().toString())
                .holdId(holdData.getHoldId())
                .accountNumberId(message.getAccountNumberId())
                .ownerId(message.getOwnerId())
                .rateExchange(rateData.getRateExchange())
                .baseCurrency(message.getBaseCurrency())
                .targetCurrency(message.getTargetCurrency())
                .build();

        ExternalApiResponse<ReleaseAndEntryResponse> releaseResponse =
                coreBankingClient.releaseAndEntry(releaseRequest);

        ReleaseAndEntryResponse releaseData = releaseResponse.getData();

        log.info(
                "Release success [{}] entry [{}]",
                holdData.getHoldId(),
                releaseData.getEntryId()
        );

        return releaseData;
    }

    private SellForeignTransaction initTransaction(SellForeignMessage message) {
        UUID txId = UUID.randomUUID();
        log.info("Initializing transaction [{}] - idempotencyKey: {}, customerId: {}", 
            txId, message.getIdempotencyKey(), message.getOwnerId());

        SellForeignTransaction transaction = SellForeignTransaction.builder()
                .txId(txId)
                .idempotencyKey(message.getIdempotencyKey())
                .ownerId(message.getOwnerId())
                .status(TransactionStatus.PROCESSING)
                .build();

        SellForeignTransaction savedTransaction = transactionRepository.save(transaction);
        log.debug("Transaction persisted to database - txId: {}, status: {}", 
            savedTransaction.getTxId(), savedTransaction.getStatus());
        return savedTransaction;
    }
    private TransactionDetail initTransactionDetail(SellForeignMessage message, SellForeignTransaction transaction) {
        return transactionDetailRepository.findByTxId(transaction.getTxId())
                .orElseGet(() -> {
                    TransactionDetail detail = TransactionDetail.builder()
                            .txId(transaction.getTxId())
                            .accountNumberId(message.getAccountNumberId())
                            .baseCurrency(message.getBaseCurrency())
                            .targetCurrency(message.getTargetCurrency())
                            .sourceAmount(message.getAmount())
                            .build();

                    return transactionDetailRepository.save(detail);
                });
    }

    private void compensateHoldIfNeeded(SellForeignMessage message, HoldResponse holdData, SellForeignTransaction transaction) {
        if (holdData == null) {
            return;
        }

        try {
            coreBankingClient.releaseHold(ReleaseHoldRequest.builder()
                    .txId(transaction.getTxId().toString())
                    .holdId(holdData.getHoldId())
                    .accountNumberId(message.getAccountNumberId())
                    .build());
            log.info("Released hold [{}] for failed tx [{}]", holdData.getHoldId(), transaction.getTxId());
        } catch (Exception compensationError) {
            log.error("Failed to compensate hold [{}] for tx [{}]: {}",
                    holdData.getHoldId(),
                    transaction.getTxId(),
                    compensationError.getMessage(),
                    compensationError);
        }
    }

    private void markTransactionFailed(
            SellForeignTransaction transaction,
            SellForeignMessage message,
            Exception e
    ) {
        TransactionDetail detail = transactionDetailRepository.findByTxId(transaction.getTxId())
                .orElseGet(() -> initTransactionDetail(message,transaction));

        detail.setFailureReason(e.getMessage());
        detail.setCompletedAt(LocalDateTime.now());
        transactionDetailRepository.save(detail);

        transaction.setStatus(TransactionStatus.FAILED);
        transactionRepository.save(transaction);

        publishFailedEvent(message, e,transaction);
    }





    private void markTransactionSuccess(SellForeignTransaction transaction, UUID txId) {
        transaction.setStatus(TransactionStatus.SUCCESS);
        transactionRepository.save(transaction);

        transactionDetailRepository.findByTxId(txId).ifPresent(detail -> {
            detail.setCompletedAt(LocalDateTime.now());
            transactionDetailRepository.save(detail);
        });

    }

    private void updateTransactionDetailWithRate(
            UUID txId,
            BigDecimal rateExchange,
            BigDecimal convertedAmount
    ) {
        TransactionDetail detail = transactionDetailRepository.findByTxId(txId)
                .orElseThrow(() -> new IllegalStateException(
                        "Transaction detail not found for txId: " + txId
                ));

        detail.setRateExchange(rateExchange);
        detail.setConvertedAmount(convertedAmount);

        transactionDetailRepository.save(detail);
    }

    private void publishSuccessEvent(
            SellForeignMessage message,
            BigDecimal convertedAmount,
            SellForeignTransaction transaction
    ) {
        notificationEventPublisher.publishTransactionResult(
                NotificationEvent.builder()
                        .txId(transaction.getTxId().toString())
                        .ownerId(message.getOwnerId())
                        .status("SUCCESS")
                        .baseCurrency(message.getBaseCurrency().name())
                        .targetCurrency(message.getTargetCurrency().name())
                        .sourceAmount(message.getAmount())
                        .convertedAmount(convertedAmount)
                        .timestamp(java.time.Instant.now())
                        .build()
        );
    }

    private void publishFailedEvent(
            SellForeignMessage message,
            Exception e,
            SellForeignTransaction transaction
    ) {
        notificationEventPublisher.publishTransactionResult(
                NotificationEvent.builder()
                        .txId(transaction.getTxId().toString())
                        .ownerId(message.getOwnerId())
                        .status("FAILED")
                        .baseCurrency(message.getBaseCurrency().name())
                        .targetCurrency(message.getTargetCurrency().name())
                        .sourceAmount(message.getAmount())
                        .failureReason(e.getMessage())
                        .timestamp(java.time.Instant.now())
                        .build()
        );
    }
}
