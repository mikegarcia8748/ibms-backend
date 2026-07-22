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

private fun acct(id: String, rate: String = "1000") = Account(
    id = id, accountNumber = "acc-$id", providerId = "p1", storeId = "s1",
    rate = rate, installationDate = LocalDate(2026, 1, 1),
    status = AccountStatus.ACTIVE, createdAt = Instant.fromEpochSeconds(0),
)

/**
 * Read-only preview shown to a Secretary before compiling. Proven with mocks (no DB).
 * Unlike [com.puregoldbe.ibms.application.usecase.CreateDraftTopSheetUseCase], this never
 * calls the future-period guard — a Secretary can preview a period they cannot yet draft.
 * That asymmetry is intentional to document here, not a bug fixed in this pass.
 */
class PreviewCompilationUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>()
    val stores = mockk<StoreRepository>()
    val topsheets = mockk<TopSheetRepository>()
    val useCase = PreviewCompilationUseCase(accounts, stores, topsheets, ImmediateTransactionRunner())

    Given("two eligible full-month accounts") {
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1", "1000"), acct("a2", "500"))
        every { topsheets.billedAccountIds("2026-07") } returns emptySet()

        When("previewing") {
            val preview = useCase("p1", "2026-07")

            Then("it returns both lines with the summed total, without mutating anything") {
                preview.lines.size shouldBe 2
                preview.totalAmount shouldBe "1500.00"
                preview.providerId shouldBe "p1"
                preview.billingPeriod shouldBe "2026-07"
            }
        }
    }

    Given("no eligible accounts (all already billed this period)") {
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"))
        every { topsheets.billedAccountIds("2026-07") } returns setOf("a1")

        When("previewing") {
            val preview = useCase("p1", "2026-07")

            Then("it returns an empty preview rather than throwing (unlike compile/draft creation)") {
                preview.lines.shouldBeEmpty()
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
        every { stores.list(null, null) } returns listOf(store)
        every { accounts.list(null, "p1", null) } returns listOf(acct("a1"))
        every { topsheets.billedAccountIds("2099-01") } returns emptySet()

        When("previewing a period far in the future") {
            Then("it is allowed (Preview has no future-period guard, unlike CreateDraftTopSheetUseCase)") {
                val preview = useCase("p1", "2099-01")
                preview.lines.size shouldBe 1
            }
        }
    }
})
