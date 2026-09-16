package com.example.payment.event.processor.controller;

import com.example.payment.event.processor.dto.TransactionRequest;
import com.example.payment.event.processor.dto.TransactionResponse;
import com.example.payment.event.processor.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/process")
    public ResponseEntity<TransactionResponse> processTransaction(
            @Valid @RequestBody TransactionRequest request
    ) {
        TransactionResponse response = transactionService.process(request);

        return ResponseEntity.ok(response);
    }
}