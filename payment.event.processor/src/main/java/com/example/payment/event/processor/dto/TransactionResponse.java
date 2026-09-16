package com.example.payment.event.processor.dto;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionResponse(
        UUID transactionId,
        UUID userId,
        BigDecimal amount,
        String type,
        BigDecimal remainingBalance,
        String status
) {
}