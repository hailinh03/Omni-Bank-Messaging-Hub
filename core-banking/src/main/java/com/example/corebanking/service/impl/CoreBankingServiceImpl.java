package com.example.corebanking.service.impl;

import com.example.common.config.api.ApiCode;
import com.example.common.enums.Currency;
import com.example.corebanking.dto.HoldRequest;
import com.example.corebanking.dto.HoldResponse;
import com.example.corebanking.dto.ReleaseAndEntryRequest;
import com.example.corebanking.dto.ReleaseAndEntryResponse;
import com.example.corebanking.dto.ReleaseHoldRequest;
import com.example.corebanking.dto.ReleaseHoldResponse;
import com.example.corebanking.entity.Account;
import com.example.corebanking.entity.Entry;
import com.example.corebanking.enums.EntryType;
import com.example.corebanking.exception.BusinessException;
import com.example.corebanking.repository.AccountRepository;
import com.example.corebanking.repository.EntryRepository;
import com.example.corebanking.service.CoreBankingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CoreBankingServiceImpl implements CoreBankingService {

    private final AccountRepository accountRepository;
    private final EntryRepository entryRepository;

    @Override
    @Transactional
    public HoldResponse processCheckAndHold(HoldRequest request) {
        log.info("Processing Hold for txId: {}", request.getTxId());

        if (request.getTxId() == null || request.getTxId().isBlank()) {
            log.warn("Hold request validation failed: Missing txId");
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiCode.MISSING_FIELD, "Missing required field");
        }
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Hold request validation failed: Invalid amount [{}]", request.getAmount());
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiCode.AMOUNT_NOT_POSITIVE, "Amount must be greater than 0");
        }
        log.debug("Validating hold request - accountId: {}, amount: {}, currency: {}, ownerId: {}", 
            request.getAccountNumberId(), request.getAmount(), request.getCurrency(), request.getOwnerId());

        // Idempotency guard: do not hold twice for the same transaction.
        log.debug("Checking for existing hold entry for txId: {}", request.getTxId());
        Entry existingHold = entryRepository.findByTxIdAndType(request.getTxId(), EntryType.HOLD)
                .orElse(null);
        if (existingHold != null) {
            log.info("Hold already exists for txId: {}, holdId: {}", request.getTxId(), existingHold.getEntryId());
            return HoldResponse.builder()
                    .holdId(existingHold.getEntryId())
                    .txId(existingHold.getTxId())
                    .accountNumberId(existingHold.getAccountNumberId())
                    .heldAmount(existingHold.getAmount())
                    .currency(existingHold.getCurrency())
                    .createdAt(existingHold.getCreatedAt().toString())
                    .build();
        }

        log.debug("Fetching account for accountId: {}", request.getAccountNumberId());
        Account account = accountRepository.findByAccountNumberId(request.getAccountNumberId())
                .orElseThrow(() -> {
                    log.warn("Account not found for accountId: {}", request.getAccountNumberId());
                    return new BusinessException(HttpStatus.NOT_FOUND, ApiCode.FX_ERR_009, "Account not found");
                });

        if (!account.getCustomerId().equals(request.getOwnerId())) {
            log.warn("Account owner mismatch: expected customerId: {}, account customerId: {}", request.getOwnerId(), account.getCustomerId());
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiCode.FX_ERR_009, "Account not found");
        }
        if (!"ACTIVE".equalsIgnoreCase(account.getStatus())) {
            log.warn("Account is not active for accountId: {}, status: {}", request.getAccountNumberId(), account.getStatus());
            throw new BusinessException(HttpStatus.LOCKED, ApiCode.FX_ERR_010, "Account locked");
        }
        if (!account.getCurrency().name().equalsIgnoreCase(request.getCurrency())) {
            log.warn("Currency mismatch: expected: {}, account currency: {}", request.getCurrency(), account.getCurrency().name());
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ApiCode.FX_ERR_007, "Source account currency mismatch");
        }

        if (account.getAvailableBalance().compareTo(request.getAmount()) < 0) {
            log.warn("Insufficient funds: accountId: {}, available: {}, requested: {}", 
                request.getAccountNumberId(), account.getAvailableBalance(), request.getAmount());
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ApiCode.FX_ERR_002, "Insufficient funds");
        }
        // Validation passed, now hold the funds
        log.debug("Holding funds atomically for txId: {}, amount: {}", request.getTxId(), request.getAmount());
        int updatedRows = accountRepository.holdFundsAtomically(
                request.getAccountNumberId(),
                request.getAmount(),
                Currency.valueOf(request.getCurrency())
        );
        if (updatedRows == 0) {
            log.warn("Hold operation failed for txId: {}, performing verification", request.getTxId());
            boolean accountExists = accountRepository.existsByAccountNumberId(request.getAccountNumberId());
            if (!accountExists) {
                log.error("Account not found during hold verification for accountId: {}", request.getAccountNumberId());
                throw new BusinessException(HttpStatus.NOT_FOUND, ApiCode.FX_ERR_009, "Account not found");
            } else {
                log.error("Hold operation failed due to insufficient funds for txId: {}", request.getTxId());
                throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ApiCode.FX_ERR_002, "Insufficient funds");
            }
        }
        log.debug("Funds held successfully for txId: {}", request.getTxId());

        // Save hold entry after successful fund hold
        log.debug("Creating hold entry for txId: {}", request.getTxId());
        String holdId = "ENTRY-HOLD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Entry holdEntry = Entry.builder()
                .entryId(holdId)
                .txId(request.getTxId())
                .accountNumberId(account.getAccountNumberId())
                .type(EntryType.HOLD)
                .amount(request.getAmount())
                .currency(request.getCurrency())
                .build();
        entryRepository.save(holdEntry);
        log.info("Successfully processed Hold [{}] for txId: {}, amount: {}, currency: {}", 
            holdId, request.getTxId(), request.getAmount(), request.getCurrency());

        return HoldResponse.builder()
                .holdId(holdEntry.getEntryId())
                .txId(holdEntry.getTxId())
                .accountNumberId(holdEntry.getAccountNumberId())
                .heldAmount(holdEntry.getAmount())
                .currency(holdEntry.getCurrency())
                .createdAt(LocalDateTime.now().toString())
                .build();
    }

    @Override
    @Transactional
    public ReleaseAndEntryResponse processReleaseAndEntry(ReleaseAndEntryRequest request) {

        // Idempotency guard: do not debit/credit twice for the same transaction.
        Entry existingDebit = entryRepository.findByTxIdAndType(request.getTxId(), EntryType.DEBIT)
                .orElse(null);
        if (existingDebit != null) {
            return ReleaseAndEntryResponse.builder()
                    .entryId(existingDebit.getEntryId())
                    .holdId(request.getHoldId())
                    .txId(existingDebit.getTxId())
                    .status("SUCCESS")
                    .createdAt(existingDebit.getCreatedAt().toString())
                    .build();
        }

        Entry holdEntry = entryRepository.findById(request.getHoldId())
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiCode.SYS_ERR_001, "Ledger posting failed"));

        Account sourceAccount = accountRepository.findByAccountNumberId(request.getAccountNumberId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiCode.FX_ERR_009, "Account not found"));
        if (!sourceAccount.getCustomerId().equals(request.getOwnerId())) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiCode.FX_ERR_009, "Account not found");
        }
        if (!sourceAccount.getCurrency().equals(request.getBaseCurrency())) {
            throw new BusinessException(HttpStatus.UNPROCESSABLE_ENTITY, ApiCode.FX_ERR_007, "Source account currency mismatch");
        }

        Account targetAccount = accountRepository.findByCustomerIdAndCurrency(request.getOwnerId(), request.getTargetCurrency())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiCode.FX_ERR_009, "Account not found"));

        //release xong trừ
        int updatedRealease = accountRepository.realeaseHold(
                request.getAccountNumberId(),
                holdEntry.getAmount(),
                request.getBaseCurrency()
        );

        if (updatedRealease == 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiCode.SYS_ERR_001, "Ledger posting failed");
        }

        // cộng account còn lại
        BigDecimal convertedAmount = holdEntry.getAmount()
                .multiply(request.getRateExchange());

        int updatedCredit = accountRepository.creditAfterRelease(
                // Credit the converted amount to the target-currency account.
                targetAccount.getAccountNumberId(),
                convertedAmount,
                request.getTargetCurrency()
        );

        if (updatedCredit == 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiCode.SYS_ERR_001, "Ledger posting failed");
        }

        String holdId = "ENTRY-RELEASED-HOLD" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Entry releasedHoldEntry = Entry.builder()
                .entryId(holdId)
                .txId(request.getTxId())
                .accountNumberId(request.getAccountNumberId())
                .type(EntryType.RELEASED_HOLD)
                .amount(holdEntry.getAmount())
                .currency(holdEntry.getCurrency())
                .build();
        entryRepository.save(releasedHoldEntry);

        //holdEntry.setStatus("RELEASED");
      //  entryRepository.save(holdEntry);

        // ── Create DEBIT Entry ──
        String entryDebitId = "ENTRY-DEBIT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Entry debitEntry = Entry.builder()
                .entryId(entryDebitId)
                .txId(request.getTxId())
                .accountNumberId(request.getAccountNumberId())
                .type(EntryType.DEBIT)
                .currency(holdEntry.getCurrency())
                .amount(holdEntry.getAmount())
                .rateExchange(request.getRateExchange())
//                .holdId(holdEntry.getEntryId())
//                .status("COMPLETED")
                .build();
        entryRepository.save(debitEntry);
        String entryCreditId = "ENTRY-CREDIT" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Entry creditEntry = Entry.builder()
                .entryId(entryCreditId)
                .txId(request.getTxId())
                .accountNumberId(targetAccount.getAccountNumberId())
                .type(EntryType.CREDIT)
                .currency(request.getTargetCurrency().name())
                .amount(convertedAmount)
                .rateExchange(request.getRateExchange())
//                .holdId(holdEntry.getEntryId())
//                .status("COMPLETED")
                .build();
        entryRepository.save(creditEntry);


      //  log.info("Successfully released hold [{}] and created entry [{}] for txId: {}", request.getHoldId(), entryId, request.getTxId());

        return ReleaseAndEntryResponse.builder()
                .entryId(debitEntry.getEntryId())
                .holdId(holdEntry.getEntryId())
                .txId(debitEntry.getTxId())
                .status("SUCCESS")
                .createdAt(LocalDateTime.now().toString())
                .build();
    }

    @Override
    @Transactional
    public ReleaseHoldResponse processReleaseHold(ReleaseHoldRequest request) {
        Entry holdEntry = entryRepository.findById(request.getHoldId())
                .orElseThrow(() -> new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiCode.SYS_ERR_001, "Ledger posting failed"));

        // Idempotency guard for compensation retries.
        Entry existingRelease = entryRepository.findByTxIdAndType(request.getTxId(), EntryType.RELEASED_HOLD)
                .orElse(null);
        if (existingRelease != null) {
            return ReleaseHoldResponse.builder()
                    .holdId(request.getHoldId())
                    .txId(request.getTxId())
                    .status("RELEASED_HOLD")
                    .createdAt(existingRelease.getCreatedAt().toString())
                    .build();
        }

        int updatedRows = accountRepository.releaseHoldOnly(
                request.getAccountNumberId(),
                holdEntry.getAmount(),
                Currency.valueOf(holdEntry.getCurrency())
        );

        if (updatedRows == 0) {
            throw new BusinessException(HttpStatus.INTERNAL_SERVER_ERROR, ApiCode.SYS_ERR_001, "Ledger posting failed");
        }

        Entry releaseEntry = Entry.builder()
                .entryId("ENTRY-RELEASED-HOLD" + UUID.randomUUID().toString().substring(0, 8).toUpperCase())
                .txId(request.getTxId())
                .accountNumberId(request.getAccountNumberId())
                .type(EntryType.RELEASED_HOLD)
                .currency(holdEntry.getCurrency())
                .amount(holdEntry.getAmount())
                .build();
        entryRepository.save(releaseEntry);

        return ReleaseHoldResponse.builder()
                .holdId(request.getHoldId())
                .txId(request.getTxId())
                .status("RELEASED_HOLD")
                .createdAt(LocalDateTime.now().toString())
                .build();
    }
}
