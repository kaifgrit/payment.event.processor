package com.example.payment.event.processor.service;

import com.example.payment.event.processor.dto.TransactionRequest;
import com.example.payment.event.processor.dto.TransactionResponse;
import com.example.payment.event.processor.entity.Transaction;
import com.example.payment.event.processor.entity.TransactionType;
import com.example.payment.event.processor.entity.Wallet;
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

        // 1. Acquire database lock on the wallet
        Wallet wallet = walletRepository.findByUserId(request.userId())
                .orElseThrow(() ->
                        new RuntimeException("Wallet not found")
                );

        // 2. Check for duplicate transaction
        if (transactionRepository.existsByTransactionId(request.transactionId())) {
            throw new RuntimeException("Transaction already processed");
        }

        // 3. Currently this assignment focuses on DEBIT
        if (request.type() != TransactionType.DEBIT) {
            throw new RuntimeException("Only DEBIT transactions are supported");
        }

        // 4. Check balance
        if (wallet.getBalance().compareTo(request.amount()) < 0) {
            throw new RuntimeException("Insufficient funds");
        }

        // 5. Deduct amount
        wallet.setBalance(
                wallet.getBalance().subtract(request.amount())
        );

        walletRepository.save(wallet);

        // 6. Store transaction
        Transaction transaction = new Transaction(
                request.transactionId(),
                request.userId(),
                request.amount(),
                request.type()
        );

        transactionRepository.save(transaction);

        // 7. Return result
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