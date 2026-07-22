package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ListTopSheetsUseCase
import com.puregoldbe.ibms.domain.model.CursorPage
import com.puregoldbe.ibms.domain.model.TopSheet
import com.puregoldbe.ibms.domain.model.TopSheetStatus
import com.puregoldbe.ibms.domain.port.TopSheetRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant

private fun topsheet(id: String) = TopSheet(
    id = id, invoiceNumber = "CONV-202607-0001", billingPeriod = "2026-07",
    providerId = "p1", providerName = "Converge", accountCount = 1, totalAmount = "1000.00",
    status = TopSheetStatus.COMPILED, compilerId = "compiler", compilationDate = Instant.fromEpochSeconds(0),
)

/** Paged listing/filtering; a thin pass-through over the repository. Proven with mocks. */
class ListTopSheetsUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val topsheets = mockk<TopSheetRepository>()
    val useCase = ListTopSheetsUseCase(topsheets, ImmediateTransactionRunner())

    Given("matching topsheets exist") {
        every { topsheets.page("p1", "2026-07", TopSheetStatus.COMPILED, null, 20) } returns
            CursorPage(listOf(topsheet("ts1")), nextCursor = null)

        When("listing with filters") {
            val result = useCase("p1", "2026-07", TopSheetStatus.COMPILED, null, 20)

            Then("it forwards the filters and returns the page as-is") {
                result.items.size shouldBe 1
                result.items[0].id shouldBe "ts1"
                result.nextCursor shouldBe null
                verify(exactly = 1) { topsheets.page("p1", "2026-07", TopSheetStatus.COMPILED, null, 20) }
            }
        }
    }

    Given("no filters and no results") {
        every { topsheets.page(null, null, null, null, 20) } returns CursorPage(emptyList(), nextCursor = null)

        When("listing everything") {
            val result = useCase(null, null, null, null, 20)

            Then("it returns an empty page") {
                result.items.shouldBeEmpty()
            }
        }
    }

    Given("a cursor from a previous page") {
        every { topsheets.page(null, null, null, "cursor-abc", 20) } returns
            CursorPage(listOf(topsheet("ts2")), nextCursor = "cursor-def")

        When("listing the next page") {
            val result = useCase(null, null, null, "cursor-abc", 20)

            Then("it forwards the cursor and returns the next one") {
                result.items[0].id shouldBe "ts2"
                result.nextCursor shouldBe "cursor-def"
            }
        }
    }
})
