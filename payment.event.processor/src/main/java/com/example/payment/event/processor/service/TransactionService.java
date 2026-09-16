package com.example.payment.event.processor.service;

import com.example.payment.event.processor.dto.TransactionRequest;
import com.example.payment.event.processor.dto.TransactionResponse;
import com.example.payment.event.processor.entity.Transaction;
import com.example.payment.event.processor.entity.TransactionType;
import com.example.payment.event.processor.entity.Wallet;
import com.example.payment.event.processor.exception.DuplicateTransactionException;
import com.example.payment.event.processor.exception.InsufficientFundsException;
import com.example.payment.event.processor.exception.WalletNotFoundException;
import com.example.payment.event.processor.repository.TransactionRepository;
import com.example.payment.event.processor.repository.WalletRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

@Service
public class TransactionService {

    private final WalletRepository walletRepository;
    private final TransactionRepository transactionRepository;

    public TransactionService(
            WalletRepository walletRepository,
            TransactionRepository transactionRepository
    ) {
        this.walletRepository = walletRepository;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransactionResponse process(TransactionRequest request) {

        // 1. Check whether this transaction was already processed.
        if (transactionRepository.existsByTransactionId(request.transactionId())) {
            throw new DuplicateTransactionException(
                    "Transaction already processed: " + request.transactionId()
            );
        }

        // 2. Only DEBIT is required for this assignment.
        if (request.type() != TransactionType.DEBIT) {
            throw new IllegalArgumentException(
                    "Only DEBIT transactions are supported"
            );
        }

        // 3. Fetch the wallet with PESSIMISTIC_WRITE locking.
        //    This prevents concurrent debit requests from reading
        //    the same balance and causing a race condition.
        Wallet wallet = walletRepository.findByUserId(request.userId())
                .orElseThrow(() ->
                        new WalletNotFoundException(
                                "Wallet not found for user: " + request.userId()
                        )
                );

        // 4. Check whether the wallet has enough money.
        if (wallet.getBalance().compareTo(request.amount()) < 0) {
            throw new InsufficientFundsException(
                    "Insufficient funds for transaction: "
                            + request.transactionId()
            );
        }

        // 5. Deduct the requested amount.
        wallet.setBalance(
                wallet.getBalance().subtract(request.amount())
        );

        // 6. Save the updated wallet balance.
        walletRepository.save(wallet);

        // 7. Create a transaction record.
        Transaction transaction = new Transaction(
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.type()
        );

        // 8. Save the transaction.
        transactionRepository.save(transaction);

        // 9. Return the successful response.
        return new TransactionResponse(
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.type().name(),
                wallet.getBalance(),
                "SUCCESS"
        );
    }
}