package com.example.payment.event.processor.integration;

import com.example.payment.event.processor.entity.Wallet;
import com.example.payment.event.processor.repository.WalletRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class TransactionIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WalletRepository walletRepository;

    // ────────────────────────────────────────────────────────────────
    // 1. HAPPY PATH
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Processes a single valid debit transaction successfully.")
    void processesSingleValidDebitSuccessfully() throws Exception {

        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        Wallet wallet = new Wallet(userId, new BigDecimal("500.00"));
        walletRepository.saveAndFlush(wallet);

        String requestBody = """
                {
                    "transactionId": "%s",
                    "userId": "%s",
                    "amount": 100.00,
                    "type": "DEBIT"
                }
                """.formatted(transactionId, userId);

        mockMvc.perform(
                        post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId")
                        .value(transactionId.toString()))
                .andExpect(jsonPath("$.userId")
                        .value(userId.toString()))
                .andExpect(jsonPath("$.amount")
                        .value(100.0))
                .andExpect(jsonPath("$.type")
                        .value("DEBIT"))
                .andExpect(jsonPath("$.remainingBalance")
                        .value(400.0))
                .andExpect(jsonPath("$.status")
                        .value("SUCCESS"));

        Wallet updatedWallet = walletRepository
                .findWalletByUserId(userId)
                .orElseThrow();

        assertEquals(
                new BigDecimal("400.00"),
                updatedWallet.getBalance()
        );

        System.out.println(
                "HAPPY PATH RESULT: 500.00 -> 400.00, transaction succeeded."
        );
    }

    // ────────────────────────────────────────────────────────────────
    // 2. IDEMPOTENCY — 3 identical concurrent requests
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sends 3 identical transactionIDs simultaneously. Ensures the balance is only deducted once.")
    void sendsThreeIdenticalTransactionsSimultaneously() throws Exception {

        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        Wallet wallet = new Wallet(userId, new BigDecimal("500.00"));
        walletRepository.saveAndFlush(wallet);

        String requestBody = """
                {
                    "transactionId": "%s",
                    "userId": "%s",
                    "amount": 100.00,
                    "type": "DEBIT"
                }
                """.formatted(transactionId, userId);

        int numberOfRequests = 3;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfRequests);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            List<Future<Integer>> futures = new ArrayList<>();

            for (int i = 0; i < numberOfRequests; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return mockMvc.perform(
                                    post("/api/v1/transactions/process")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(requestBody)
                            )
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }

            System.out.println(
                    "IDEMPOTENCY TEST: Sending 3 identical requests simultaneously..."
            );

            startGate.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            long successfulRequests = statuses.stream()
                    .filter(s -> s == 200)
                    .count();

            long conflictRequests = statuses.stream()
                    .filter(s -> s == 409)
                    .count();

            System.out.println(
                    "IDEMPOTENCY TEST RESULTS: statuses = " + statuses
            );
            System.out.println(
                    "Successful requests = " + successfulRequests
            );
            System.out.println(
                    "Conflict requests = " + conflictRequests
            );

            assertEquals(1, successfulRequests);
            assertEquals(2, conflictRequests);

            Wallet updatedWallet = walletRepository
                    .findWalletByUserId(userId)
                    .orElseThrow();

            assertEquals(
                    new BigDecimal("400.00"),
                    updatedWallet.getBalance()
            );

            System.out.println(
                    "IDEMPOTENCY TEST PASSED: balance deducted exactly once."
            );

        } finally {
            executor.shutdown();
        }
    }

    // ────────────────────────────────────────────────────────────────
    // 3. INSUFFICIENT FUNDS
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rejects a debit transaction when the wallet has insufficient funds.")
    void rejectsDebitWhenInsufficientFunds() throws Exception {

        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        Wallet wallet = new Wallet(userId, new BigDecimal("50.00"));
        walletRepository.saveAndFlush(wallet);

        String requestBody = """
                {
                    "transactionId": "%s",
                    "userId": "%s",
                    "amount": 100.00,
                    "type": "DEBIT"
                }
                """.formatted(transactionId, userId);

        mockMvc.perform(
                        post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").exists());

        Wallet updatedWallet = walletRepository
                .findWalletByUserId(userId)
                .orElseThrow();

        assertEquals(
                new BigDecimal("50.00"),
                updatedWallet.getBalance()
        );

        System.out.println(
                "INSUFFICIENT FUNDS TEST PASSED: balance remains 50.00, transaction rejected with 400."
        );
    }

    // ────────────────────────────────────────────────────────────────
    // 4. WALLET NOT FOUND
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Returns 404 when the wallet does not exist for the given user.")
    void rejectsDebitForNonExistentWallet() throws Exception {

        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        String requestBody = """
                {
                    "transactionId": "%s",
                    "userId": "%s",
                    "amount": 100.00,
                    "type": "DEBIT"
                }
                """.formatted(transactionId, userId);

        mockMvc.perform(
                        post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").exists());

        System.out.println(
                "WALLET NOT FOUND TEST PASSED: non-existent wallet rejected with 404."
        );
    }

    // ────────────────────────────────────────────────────────────────
    // 5. DUPLICATE TRANSACTION (sequential)
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Rejects a duplicate transactionId on the second sequential request.")
    void rejectsDuplicateTransactionId() throws Exception {

        UUID userId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        Wallet wallet = new Wallet(userId, new BigDecimal("500.00"));
        walletRepository.saveAndFlush(wallet);

        String requestBody = """
                {
                    "transactionId": "%s",
                    "userId": "%s",
                    "amount": 100.00,
                    "type": "DEBIT"
                }
                """.formatted(transactionId, userId);

        // First request — should succeed.
        mockMvc.perform(
                        post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isOk());

        // Second request with the same transactionId — should be rejected.
        mockMvc.perform(
                        post("/api/v1/transactions/process")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(requestBody)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").exists());

        Wallet updatedWallet = walletRepository
                .findWalletByUserId(userId)
                .orElseThrow();

        assertEquals(
                new BigDecimal("400.00"),
                updatedWallet.getBalance()
        );

        System.out.println(
                "DUPLICATE TRANSACTION TEST PASSED: second request rejected with 409, balance deducted only once (400.00)."
        );
    }

    // ────────────────────────────────────────────────────────────────
    // 6. RACE CONDITION — 10 concurrent ₹100 debits on ₹500 wallet
    // ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("Sends 10 concurrent debit requests of ₹100 for a wallet with a ₹500 balance. Ensures the final balance is exactly ₹0 and 5 requests fail with insufficient funds.")
    void tenConcurrentDebitsOf100OnWalletWith500() throws Exception {

        UUID userId = UUID.randomUUID();

        Wallet wallet = new Wallet(userId, new BigDecimal("500.00"));
        walletRepository.saveAndFlush(wallet);

        int numberOfRequests = 10;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfRequests);
        CountDownLatch startGate = new CountDownLatch(1);

        try {
            List<Future<Integer>> futures = new ArrayList<>();

            for (int i = 0; i < numberOfRequests; i++) {

                // Each request has a unique transactionId.
                UUID txId = UUID.randomUUID();

                String requestBody = """
                        {
                            "transactionId": "%s",
                            "userId": "%s",
                            "amount": 100.00,
                            "type": "DEBIT"
                        }
                        """.formatted(txId, userId);

                futures.add(executor.submit(() -> {
                    startGate.await();
                    return mockMvc.perform(
                                    post("/api/v1/transactions/process")
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content(requestBody)
                            )
                            .andReturn()
                            .getResponse()
                            .getStatus();
                }));
            }

            System.out.println(
                    "RACE CONDITION TEST: Firing 10 concurrent ₹100 debits against ₹500 wallet..."
            );

            startGate.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get());
            }

            long successCount = statuses.stream()
                    .filter(s -> s == 200)
                    .count();

            long insufficientFundsCount = statuses.stream()
                    .filter(s -> s == 400)
                    .count();

            System.out.println(
                    "RACE CONDITION TEST RESULTS: statuses = " + statuses
            );
            System.out.println(
                    "Successful requests = " + successCount
            );
            System.out.println(
                    "Insufficient funds rejections = " + insufficientFundsCount
            );

            assertEquals(5, successCount,
                    "Exactly 5 debits should succeed (₹500 / ₹100 = 5)");

            assertEquals(5, insufficientFundsCount,
                    "Exactly 5 debits should fail with insufficient funds");

            Wallet updatedWallet = walletRepository
                    .findWalletByUserId(userId)
                    .orElseThrow();

            assertEquals(
                    new BigDecimal("0.00"),
                    updatedWallet.getBalance()
            );

            System.out.println(
                    "RACE CONDITION TEST PASSED: 5 succeeded, 5 rejected (insufficient funds), final balance = ₹0.00."
            );

        } finally {
            executor.shutdown();
        }
    }
}