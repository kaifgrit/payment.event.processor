package com.example.payment.event.processor.dto;

import com.example.payment.event.processor.entity.TransactionType;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.util.UUID;

public record TransactionRequest(

        @NotNull
        UUID transactionId,

        @NotNull
        UUID userId,

        @NotNull
        @DecimalMin(value = "0.01")
        BigDecimal amount,

        @NotNull
        TransactionType type

) {
}