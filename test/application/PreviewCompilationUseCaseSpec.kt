package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.PreviewCompilationUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private val store = Store(
    id = "s1", storeType = StoreType.PUREGOLD, branchCode = "118", name = "Store 118",
    proofOfInstallationId = "att1", createdAt = Instant.fromEpochSeconds(0),
)

private fun acct(
    id: String,
    rate: String = "1000",
    installationDate: LocalDate = LocalDate(2026, 7, 1),
    contractStartDate: LocalDate? = null,
) = Account(
    id = id, accountNumber = "acc-$id", providerId = "p1", storeId = "s1",
    rate = rate, installationDate = installationDate, contractStartDate = contractStartDate,
    status = AccountStatus.ACTIVE, createdAt = Instant.fromEpochSeconds(0),
)

/**
 * Read-only preview shown to a Secretary before compiling. Proven with mocks (no DB).
 * The ProrationEngine runs for real, so eligibility/arrears/proration math is covered
 * transitively.
 */
class PreviewCompilationUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val clock = FakeClock(Instant.parse("2026-07-20T08:00:00Z"))
    val accounts = mockk<AccountRepository>()
    val stores = mockk<StoreRepository>()
    val topsheets = mockk<TopSheetRepository>()
    val useCase = PreviewCompilationUseCase(accounts, stores, topsheets, clock, ImmediateTransactionRunner())

    Given("two eligible full-month accounts") {
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1", "1000"), acct("a2", "500"))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { topsheets.billedPeriodsByAccount("p1") } returns emptyMap()

        When("previewing") {
            val preview = useCase("p1", "2026-07")

            Then("it returns both lines with the summed total, without mutating anything") {
                preview.lines.size shouldBe 2
                preview.totalAmount shouldBe "1500.00"
                preview.arrears.shouldBeEmpty()
                preview.notYetSubscribed.shouldBeEmpty()
                preview.providerId shouldBe "p1"
                preview.billingPeriod shouldBe "2026-07"
            }
        }
    }

    Given("no eligible accounts (all already billed this period)") {
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"))
        every { topsheets.billedAccountIds("2026-07") } returns setOf("a1")
        every { topsheets.billedPeriodsByAccount("p1") } returns emptyMap()

        When("previewing") {
            val preview = useCase("p1", "2026-07")

            Then("it returns an empty preview rather than throwing (unlike compile/draft creation)") {
                preview.lines.shouldBeEmpty()
                preview.totalAmount shouldBe "0.00"
            }
        }
    }

    Given("an account subscribed two months ago that was never billed") {
        // installed 2026-05-10, no billing history: May (partial) + June (full) are arrears.
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns
            listOf(acct("a1", "1000", installationDate = LocalDate(2026, 5, 10)))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { topsheets.billedPeriodsByAccount("p1") } returns emptyMap()

        When("previewing the current period") {
            val preview = useCase("p1", "2026-07")

            Then("the line is flagged as arrears and surfaced on the separate arrears list") {
                preview.lines.size shouldBe 1
                val line = preview.lines.single()
                line.proratedAmount shouldBe "1000.00"          // current July charge
                line.isArrears shouldBe true
                line.arrearsAmount shouldBe "1709.68"           // May 709.68 + June 1000.00
                line.arrearsPeriods shouldBe listOf("2026-05", "2026-06")
                preview.arrears.size shouldBe 1
                preview.totalAmount shouldBe "2709.68"
            }
        }
    }

    Given("an account whose contractStartDate is after the selected period") {
        // subscription anchors on contractStartDate (Aug), so a July preview is too early.
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns
            listOf(acct("a1", contractStartDate = LocalDate(2026, 8, 1)))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()
        every { topsheets.billedPeriodsByAccount("p1") } returns emptyMap()

        When("previewing a period before the subscription begins") {
            val preview = useCase("p1", "2026-07")

            Then("it is surfaced as a not-yet-subscribed validation warning, not billed") {
                preview.lines.shouldBeEmpty()
                preview.notYetSubscribed.size shouldBe 1
                preview.notYetSubscribed.single().subscriptionStart shouldBe "2026-08-01"
                preview.totalAmount shouldBe "0.00"
            }
        }
    }

    Given("a malformed billing period") {
        When("previewing") {
            Then("it is rejected with a Validation error") {
                shouldThrow<DomainError.Validation> { useCase("p1", "not-a-period") }
            }
        }
    }

    Given("a future billing period") {
        When("previewing a period after the current month") {
            Then("it is rejected (Preview now shares the future-period guard with CreateDraft)") {
                shouldThrow<DomainError.Validation> { useCase("p1", "2099-01") }
            }
        }
    }
})
