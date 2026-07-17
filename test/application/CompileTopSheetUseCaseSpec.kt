package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.CompileTopSheetUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.InvoiceSequenceRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TopSheetRepository
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

private fun acct(id: String) = Account(
    id = id, accountNumber = "acc-$id", providerId = "p1", storeId = "s1",
    rate = "1000", installationDate = LocalDate(2026, 7, 1),
    status = AccountStatus.ACTIVE, createdAt = Instant.fromEpochSeconds(0),
)

private val provider = Provider(id = "p1", name = "Converge", paymentScheduleDay = 15, createdAt = Instant.fromEpochSeconds(0))
private val store = Store(
    id = "s1", storeType = StoreType.PUREGOLD, branchCode = "PG-1", name = "Store One",
    proofOfInstallationId = "att", createdAt = Instant.fromEpochSeconds(0),
)

/** Compile atomicity + double-billing guard, proven with fakes (no DB). */
class CompileTopSheetUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>()
    val stores = mockk<StoreRepository>()
    val providers = mockk<ProviderRepository>()
    val topsheets = mockk<TopSheetRepository>(relaxed = true)
    val sequences = mockk<InvoiceSequenceRepository>()
    val useCase = CompileTopSheetUseCase(accounts, stores, providers, topsheets, sequences, ImmediateTransactionRunner())

    val compiled = TopSheet(
        id = "ts1", invoiceNumber = "CONV-202608-0001", billingPeriod = "2026-08",
        providerId = "p1", providerName = "Converge", accountCount = 2, totalAmount = "2000.00",
        status = TopSheetStatus.COMPILED, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
    )

    Given("two eligible full-month accounts and a fresh period") {
        every { providers.findById("p1") } returns provider
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"), acct("a2"))
        every { topsheets.billedAccountIds("2026-08") } returns emptySet()
        every { sequences.nextValue("p1") } returns 1
        every { sequences.prefixOf("p1") } returns "CONV-"
        every { topsheets.create(any(), any(), any(), any(), any(), any(), any()) } returns compiled

        When("compiling") {
            val result = useCase("p1", "2026-08", compilerId = "compiler")
            Then("it mints CONV-202608-0001 with the summed total and one line per account") {
                result.id shouldBe "ts1"
                verify(exactly = 1) {
                    topsheets.create("CONV-202608-0001", "2026-08", "p1", "Converge", 2, "2000.00", "compiler")
                }
                verify(exactly = 2) { topsheets.addLine(eq("ts1"), any()) }
            }
        }
    }

    Given("all candidate accounts already billed this period") {
        every { providers.findById("p1") } returns provider
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"), acct("a2"))
        every { topsheets.billedAccountIds("2026-08") } returns setOf("a1", "a2")

        When("compiling again") {
            Then("nothing is eligible and it is rejected (double-billing guard)") {
                shouldThrow<DomainError.Conflict> { useCase("p1", "2026-08", "compiler") }
                verify(exactly = 0) { sequences.nextValue(any()) }
            }
        }
    }

    Given("an unknown provider") {
        every { providers.findById("nope") } returns null
        When("compiling") {
            Then("it fails as not found") {
                shouldThrow<DomainError.NotFound> { useCase("nope", "2026-08", "compiler") }
            }
        }
    }
})
