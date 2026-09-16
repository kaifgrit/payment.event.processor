# DECISIONS.md — Payment Event Processor

This document records the key design and architectural decisions made for the
payment event processing system.

---

## 1. Pessimistic Locking over Optimistic Locking

**Decision:** Use database-level pessimistic write locks (`SELECT ... FOR UPDATE`)
via JPA `@Lock(LockModeType.PESSIMISTIC_WRITE)` on wallet reads during
transaction processing.

**Why:**
- Payment processing requires absolute consistency — a race condition that
  results in double-deduction or overdraft is unacceptable.
- Pessimistic locking serializes concurrent access to the same wallet row at
  the database level, so no two threads can read-and-deduct simultaneously.
- Optimistic locking (`@Version`) would detect conflicts after the fact and
  require retry logic, adding complexity and still allowing a brief window
  where stale reads can cause incorrect behavior if not implemented carefully.
- For a financial system where correctness trumps throughput, the stricter
  guarantee of pessimistic locking is the right trade-off.

**Trade-off:** Reduced throughput for concurrent transactions against the same
wallet. Transactions targeting different wallets are unaffected.

---

## 2. Idempotency via transactionId Unique Constraint + Check-Within-Lock

**Decision:** Each transaction request carries a client-supplied `transactionId`
(UUID). The `transactions` table enforces a unique constraint on this column.
Inside the `@Transactional` service method, after acquiring the wallet lock,
we check `existsByTransactionId()` before processing.

**Why:**
- Network retries, at-least-once message delivery, or duplicate form submissions
  must not cause double-charges.
- The database unique constraint provides a hard safety net — even if the
  application-level check is somehow bypassed, the DB will reject the insert.
- Performing the idempotency check *after* acquiring the pessimistic lock ensures
  that two concurrent requests with the same `transactionId` are serialized:
  the first succeeds, the second sees the existing record and is rejected.

**Alternative considered:** Using the database unique constraint alone and
catching the resulting `DataIntegrityViolationException`. This was rejected
because it mixes control flow with exception handling and produces less
informative error messages.

---

## 3. H2 In-Memory Database for Integration Testing

**Decision:** Use H2 (`jdbc:h2:mem:paymentdb`) with `spring.jpa.hibernate.ddl-auto=create-drop`
for all integration tests.

**Why:**
- H2 supports `SELECT ... FOR UPDATE` (pessimistic locking), making it suitable
  for testing the concurrency behavior that is central to this system.
- In-memory mode ensures tests are fast, isolated, and require no external
  infrastructure.
- `create-drop` regenerates the schema for each application context, preventing
  stale data from leaking between test runs.

**Note:** H2's locking behavior is not identical to production databases
(e.g., PostgreSQL). In production, additional testing against the real database
is recommended.

---

## 4. @DirtiesContext Per Integration Test

**Decision:** Apply `@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)`
to the integration test class.

**Why:**
- Each test creates wallet records with random UUIDs. Without context reset,
  the in-memory database accumulates state across tests.
- `@DirtiesContext` ensures every test starts with a clean Spring context and
  a freshly created database, preventing inter-test data pollution.
- This is a deliberate trade-off: test startup is slower, but test isolation
  is guaranteed.

**Alternative considered:** Using `@Sql` scripts or `@BeforeEach` cleanup. These
are fragile when the schema evolves and don't reset JPA caches or connection
pools.

---

## 5. Separate Locking vs Non-Locking Repository Methods

**Decision:** `WalletRepository` exposes two query methods for `userId` lookup:
- `findByUserId(UUID)` — annotated with `@Lock(PESSIMISTIC_WRITE)`, used by
  the service layer during transaction processing.
- `findWalletByUserId(UUID)` — a plain `@Query` with no locking, used by
  test verification code.

**Why:**
- `@Lock(PESSIMISTIC_WRITE)` requires an active JPA transaction. The service
  method runs inside `@Transactional`, so this works correctly.
- Test verification code runs outside the service transaction (after the HTTP
  call returns). Calling the locked method here causes
  `InvalidDataAccessApiUsageException: No active transaction`.
- Rather than wrapping test assertions in transactions (which would hide bugs)
  or removing the lock (which would weaken production safety), we provide a
  clean non-locking alternative specifically for read-only verification.

---

## 6. Only DEBIT Transactions Supported

**Decision:** The service explicitly rejects any transaction type other than
`DEBIT` with an `IllegalArgumentException`.

**Why:**
- The assignment scope only requires debit processing. Accepting credits without
  proper business rules could introduce incorrect balance mutations.
- The `TransactionType` enum includes `CREDIT` for future extensibility, but
  the service enforces the current scope.

---

## 7. Transaction Service Uses jakarta.transaction.Transactional

**Decision:** The `@Transactional` annotation is imported from
`jakarta.transaction.Transactional` rather than Spring's
`org.springframework.transaction.annotation.Transactional`.

**Why:**
- Both annotations work with Spring's transaction management.
- `jakarta.transaction.Transactional` is the standard JTA annotation and is
  portable across application servers.
- For this project, either annotation produces identical behavior. The existing
  code used `jakarta.transaction.Transactional`, and there is no reason to
  change it.

---

## 8. AI Assistant Corrections & Sub-optimal Suggestions

**Where did the AI assistant give an incorrect or sub-optimal suggestion?**

During the implementation, the AI assistant made the following incorrect assumption:
- **Incorrect Test Condition**: In the race condition test, the assistant initially assumed the requirement was to test 10 concurrent debits on a ₹1000 wallet where *all* 10 requests would succeed.
- **Correction**: The assignment explicitly required 10 concurrent debit requests of ₹100 for a wallet with a **₹500 balance**. The correct behavior was to ensure exactly 5 requests succeed, exactly 5 requests fail with insufficient funds (400 Bad Request), and the final balance is exactly ₹0. 
- **Action Taken**: The test was subsequently rewritten to use a ₹500 wallet and explicitly assert that 5 requests returned a 200 OK status and 5 requests returned a 400 Bad Request status due to insufficient funds, strictly matching the assignment constraints.
