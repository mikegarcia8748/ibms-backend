package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.CreateDraftTopSheetUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.BatchSequenceRepository
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.NewTopSheetLine
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.FakeIdempotencyKeyRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private val clock = FakeClock(Instant.parse("2026-07-15T08:00:00Z"))

private val provider = Provider(
    id = "p1", name = "Converge", paymentScheduleDay = 15, createdAt = Instant.fromEpochSeconds(0),
)

private val store118 = Store(
    id = "s1", storeType = StoreType.PUREGOLD, branchCode = "118", name = "Store 118",
    proofOfInstallationId = "att1", createdAt = Instant.fromEpochSeconds(0),
)

private val store050 = Store(
    id = "s2", storeType = StoreType.PUREGOLD, branchCode = "050", name = "Store 050",
    proofOfInstallationId = "att2", createdAt = Instant.fromEpochSeconds(0),
)

private fun acct(id: String, storeId: String) = Account(
    id = id, accountNumber = "acc-$id", providerId = "p1", storeId = storeId,
    rate = "1000", installationDate = LocalDate(2026, 1, 1),
    status = AccountStatus.ACTIVE, createdAt = Instant.fromEpochSeconds(0),
)

private val draftTopsheet = TopSheet(
    id = "ts1", invoiceNumber = null, batchNumber = "CONV-202607-B001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 3, totalAmount = "3000.00",
    status = TopSheetStatus.DRAFT, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

/**
 * Unit specs for Phase 1 of two-phase compilation: creating a DRAFT topsheet. Proven
 * with mocks + fakes (no DB). The ProrationEngine is exercised for real since it is a
 * pure domain service, so eligibility/amount math is covered transitively.
 */
class CreateDraftTopSheetUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>()
    val stores = mockk<StoreRepository>()
    val providers = mockk<ProviderRepository>()
    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val batchSequences = mockk<BatchSequenceRepository>()
    val sequences = mockk<InvoiceSequenceRepository>()
    val idempotency = FakeIdempotencyKeyRepository()
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val useCase = CreateDraftTopSheetUseCase(
        accounts, stores, providers, topsheets, batchSequences, sequences, idempotency, activity, clock,
        ImmediateTransactionRunner(),
    )

    Given("a provider with 3 eligible accounts across 2 stores (branchCodes 118 and 050)") {
        every { providers.findById("p1") } returns provider
        every { stores.list(null, null) } returns listOf(store118, store050)
        // Deliberately unordered so the sort-by-branchCode-DESC is observable.
        every { accounts.list(null, "p1", null) } returns listOf(acct("a3", "s2"), acct("a1", "s1"), acct("a2", "s1"))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { sequences.prefixOf("p1") } returns "CONV-"
        every { batchSequences.nextValue("p1") } returns 1
        every { topsheets.createDraft(any(), any(), any(), any(), any(), any(), any()) } returns draftTopsheet

        When("creating a draft") {
            val captured = mutableListOf<NewTopSheetLine>()
            every { topsheets.addLine(any(), any()) } answers { captured.add(secondArg<NewTopSheetLine>()) }
            val result = useCase("p1", "2026-07", "compiler")

            Then("creates a DRAFT topsheet sorted by branchCode DESC with rfpSortOrder and batch number") {
                result.id shouldBe "ts1"
                result.status shouldBe TopSheetStatus.DRAFT
                result.batchNumber shouldBe "CONV-202607-B001"
                verify(exactly = 1) {
                    topsheets.createDraft("2026-07", "p1", "Converge", 3, "3000.00", "CONV-202607-B001", "compiler")
                }
                captured.size shouldBe 3
                // 118 descends before 050, so the two 118-store accounts come first.
                captured[0].accountId shouldBe "a1"
                captured[0].branchCode shouldBe "118"
                captured[0].rfpSortOrder shouldBe 1
                captured[1].accountId shouldBe "a2"
                captured[1].branchCode shouldBe "118"
                captured[1].rfpSortOrder shouldBe 2
                captured[2].accountId shouldBe "a3"
                captured[2].branchCode shouldBe "050"
                captured[2].rfpSortOrder shouldBe 3
                verify { activity.record("compiler", "topsheet.draft_created", "topsheet", "ts1") }
            }
        }
    }

    Given("a future billing period") {
        every { providers.findById("p1") } returns provider

        When("creating a draft for next month") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("p1", "2026-08", "compiler") }
            }
        }
    }

    Given("no eligible accounts (all already billed this period)") {
        every { providers.findById("p1") } returns provider
        every { stores.list(null, null) } returns listOf(store118, store050)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1", "s1"), acct("a2", "s1"))
        every { topsheets.billedAccountIds("2026-07") } returns setOf("a1", "a2")

        When("creating a draft") {
            Then("it is rejected with a Conflict (nothing_to_compile)") {
                val err = shouldThrow<DomainError.Conflict> { useCase("p1", "2026-07", "compiler") }
                err.code shouldBe "nothing_to_compile"
                verify(exactly = 0) { batchSequences.nextValue(any()) }
            }
        }
    }

    Given("an unknown provider") {
        every { providers.findById("nope") } returns null

        When("creating a draft") {
            Then("it fails as NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "2026-07", "compiler") }
            }
        }
    }

    Given("an Idempotency-Key and two identical draft requests") {
        every { providers.findById("p1") } returns provider
        every { stores.list(null, null) } returns listOf(store118, store050)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a3", "s2"), acct("a1", "s1"), acct("a2", "s1"))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { sequences.prefixOf("p1") } returns "CONV-"
        every { batchSequences.nextValue("p1") } returns 1
        every { topsheets.createDraft(any(), any(), any(), any(), any(), any(), any()) } returns draftTopsheet
        val ctx = IdempotencyContext(key = "idem-1", requestHash = "hash-1", userId = "compiler")

        When("creating a draft twice with the same key") {
            val first = useCase("p1", "2026-07", "compiler", ctx)
            val second = useCase("p1", "2026-07", "compiler", ctx)

            Then("the second call replays the stored result and createDraft ran only once") {
                first.id shouldBe "ts1"
                second.id shouldBe "ts1"
                verify(exactly = 1) { topsheets.createDraft(any(), any(), any(), any(), any(), any(), any()) }
                verify(exactly = 3) { topsheets.addLine(eq("ts1"), any()) }
            }
        }
    }
})
