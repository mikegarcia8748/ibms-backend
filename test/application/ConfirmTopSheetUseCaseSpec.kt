package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ConfirmTopSheetUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetDetail
import com.puregoldbe.ibms.domain.model.TopSheetLineStatus
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.IdempotencyContext
import com.puregoldbe.ibms.domain.port.IdempotencyKeyRepository
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.FakeIdempotencyKeyRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private val clock = FakeClock(Instant.parse("2026-07-15T08:00:00Z"))

private val draftTopsheet = TopSheet(
    id = "ts1", invoiceNumber = null, batchNumber = "CONV-202607-B001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
    status = TopSheetStatus.DRAFT, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

private val compiledTopsheet = TopSheet(
    id = "ts1", invoiceNumber = "CONV-202607-0001", batchNumber = "CONV-202607-B001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
    status = TopSheetStatus.COMPILED, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

private fun acct(id: String, status: AccountStatus = AccountStatus.ACTIVE) = Account(
    id = id, accountNumber = "acc-$id", providerId = "p1", storeId = "s1",
    rate = "1000", installationDate = LocalDate(2026, 1, 1),
    status = status, createdAt = Instant.fromEpochSeconds(0),
)

/** A draft line; [rfpNumber] is null until the Secretary fills it in. */
private fun line(
    id: String,
    accountId: String,
    rfpNumber: String?,
    amount: String = "1000.00",
    arrearsAmount: String = "0.00",
    arrearsPeriods: List<String> = emptyList(),
) = TopSheetDetail(
    id = id, topsheetId = "ts1", accountId = accountId, billingPeriod = "2026-07",
    proratedAmount = amount, fullAmount = "1000.00", status = TopSheetLineStatus.BILLED,
    rfpNumber = rfpNumber, rfpSortOrder = 1,
    arrearsAmount = arrearsAmount, arrearsPeriods = arrearsPeriods,
)

/**
 * Unit specs for Phase 2 of two-phase compilation: confirming a DRAFT topsheet. Proven
 * with mocks + fakes (no DB). Verifies the re-eligibility guard, double-billing guard,
 * and the idempotent replay of a successful confirmation.
 */
class ConfirmTopSheetUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>()
    val stores = mockk<StoreRepository>(relaxed = true)
    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val sequences = mockk<InvoiceSequenceRepository>()
    val idempotency: IdempotencyKeyRepository = FakeIdempotencyKeyRepository()
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val useCase = ConfirmTopSheetUseCase(
        accounts, stores, topsheets, sequences, idempotency, activity, clock, ImmediateTransactionRunner(),
    )

    Given("a DRAFT topsheet with all RFP numbers present and all accounts still eligible") {
        every { topsheets.findById("ts1") } returns draftTopsheet
        every { topsheets.findLines("ts1") } returns listOf(
            line("l1", "a1", "010001"), line("l2", "a2", "010002"),
        )
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"), acct("a2"))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { sequences.nextValue("p1") } returns 1
        every { sequences.prefixOf("p1") } returns "CONV-"
        every { topsheets.confirm(any(), any(), any(), any()) } returns compiledTopsheet

        When("confirming") {
            val result = useCase("ts1", "confirmer")

            Then("mints the invoice number and returns a COMPILED topsheet") {
                result.id shouldBe "ts1"
                result.status shouldBe TopSheetStatus.COMPILED
                result.invoiceNumber shouldBe "CONV-202607-0001"
                verify(exactly = 1) {
                    topsheets.confirm("ts1", "CONV-202607-0001", 2, "2000.00")
                }
                verify { activity.record("confirmer", "topsheet.compiled", "topsheet", "ts1") }
            }
        }
    }

    Given("a DRAFT topsheet where some lines are missing RFP numbers") {
        every { topsheets.findById("ts1") } returns draftTopsheet
        every { topsheets.findLines("ts1") } returns listOf(
            line("l1", "a1", null), line("l2", "a2", "010002"),
        )

        When("confirming") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "confirmer") }
                verify(exactly = 0) { sequences.nextValue(any()) }
            }
        }
    }

    Given("a topsheet that is already COMPILED") {
        every { topsheets.findById("ts1") } returns compiledTopsheet

        When("confirming") {
            Then("it is rejected with a Conflict (only DRAFT can be confirmed)") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "confirmer") }
                verify(exactly = 0) { topsheets.findLines(any()) }
            }
        }
    }

    Given("a DRAFT topsheet where one account was terminated since draft creation") {
        every { topsheets.findById("ts1") } returns draftTopsheet
        every { topsheets.findLines("ts1") } returns listOf(
            line("l1", "a1", "010001"), line("l2", "a2", "010002"),
        )
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"), acct("a2", AccountStatus.TERMINATED))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()

        When("confirming") {
            Then("it is rejected with a Conflict naming the ineligible account") {
                val err = shouldThrow<DomainError.Conflict> { useCase("ts1", "confirmer") }
                err.message shouldContain "a2"
                verify(exactly = 0) { topsheets.confirm(any(), any(), any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet where one account was billed elsewhere since draft creation") {
        every { topsheets.findById("ts1") } returns draftTopsheet
        every { topsheets.findLines("ts1") } returns listOf(
            line("l1", "a1", "010001"), line("l2", "a2", "010002"),
        )
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"), acct("a2"))
        every { topsheets.billedAccountIds("2026-07") } returns setOf("a2")

        When("confirming") {
            Then("it is rejected with a Conflict naming the double-billed account") {
                val err = shouldThrow<DomainError.Conflict> { useCase("ts1", "confirmer") }
                err.message shouldContain "a2"
                verify(exactly = 0) { topsheets.confirm(any(), any(), any(), any()) }
            }
        }
    }

    Given("a DRAFT topsheet with no providerId (legacy/manual data)") {
        val orphanDraft = draftTopsheet.copy(providerId = null)
        every { topsheets.findById("ts1") } returns orphanDraft
        every { topsheets.findLines("ts1") } returns listOf(
            line("l1", "a1", "010001"), line("l2", "a2", "010002"),
        )

        When("confirming") {
            Then("it is rejected with a clean Conflict instead of a raw NPE") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "confirmer") }
                verify(exactly = 0) { accounts.list(any(), any(), any()) }
                verify(exactly = 0) { sequences.nextValue(any()) }
            }
        }
    }

    Given("a DRAFT topsheet whose lines were all removed (empty line list)") {
        every { topsheets.findById("ts1") } returns draftTopsheet
        every { topsheets.findLines("ts1") } returns emptyList()

        When("confirming") {
            Then("it is rejected with a Conflict instead of minting a 0-account invoice") {
                shouldThrow<DomainError.Conflict> { useCase("ts1", "confirmer") }
                verify(exactly = 0) { sequences.nextValue(any()) }
            }
        }
    }

    Given("a DRAFT topsheet carrying an arrears line") {
        every { topsheets.findById("ts1") } returns draftTopsheet
        every { topsheets.findLines("ts1") } returns listOf(
            line("l1", "a1", "010001"),
            line("l2", "a2", "010002", arrearsAmount = "500.00", arrearsPeriods = listOf("2026-06")),
        )
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"), acct("a2"))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { sequences.nextValue("p1") } returns 1
        every { sequences.prefixOf("p1") } returns "CONV-"
        every { topsheets.confirm(any(), any(), any(), any()) } returns compiledTopsheet

        When("confirming without acknowledging arrears") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("ts1", "confirmer") }
                verify(exactly = 0) { topsheets.confirm(any(), any(), any(), any()) }
            }
        }

        When("confirming with acknowledgeArrears = true") {
            useCase("ts1", "confirmer", acknowledgeArrears = true)

            Then("the total includes the arrears (2 × 1000 + 500)") {
                verify(exactly = 1) { topsheets.confirm("ts1", "CONV-202607-0001", 2, "2500.00") }
            }
        }
    }

    Given("an Idempotency-Key and two identical confirm requests") {
        every { topsheets.findById("ts1") } returns draftTopsheet
        every { topsheets.findLines("ts1") } returns listOf(
            line("l1", "a1", "010001"), line("l2", "a2", "010002"),
        )
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"), acct("a2"))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { sequences.nextValue("p1") } returns 1
        every { sequences.prefixOf("p1") } returns "CONV-"
        every { topsheets.confirm(any(), any(), any(), any()) } returns compiledTopsheet
        val ctx = IdempotencyContext(key = "idem-confirm-1", requestHash = "hash-1", userId = "confirmer")

        When("confirming twice with the same key") {
            val first = useCase("ts1", "confirmer", idem = ctx)
            val second = useCase("ts1", "confirmer", idem = ctx)

            Then("the second call replays the stored result and confirm ran only once") {
                first.id shouldBe "ts1"
                first.status shouldBe TopSheetStatus.COMPILED
                second.id shouldBe "ts1"
                second.status shouldBe TopSheetStatus.COMPILED
                verify(exactly = 1) { topsheets.confirm(any(), any(), any(), any()) }
                verify(exactly = 1) { sequences.nextValue("p1") }
            }
        }
    }
})
