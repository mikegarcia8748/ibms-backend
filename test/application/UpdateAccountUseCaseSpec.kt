package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.UpdateAccountUseCase
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.NotificationContext
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.NotificationEnqueuer
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

/**
 * A direct account edit used to be the one mutation that recorded no activity and
 * named no actor — so the audit trail simply had a hole in it, and its notification
 * email could say who changed the account only if the reader already knew.
 */
class UpdateAccountUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>(relaxed = true)
    val providers = mockk<ProviderRepository>(relaxed = true)
    val stores = mockk<StoreRepository>(relaxed = true)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val notifications = mockk<NotificationEnqueuer>(relaxed = true)
    val useCase = UpdateAccountUseCase(
        accounts, providers, stores, activity, notifications, ImmediateTransactionRunner(),
    )

    val request = AccountUpsertRequest(
        accountNumber = "ACC-001",
        providerId = "p1",
        storeId = "s1",
        rate = "1500.00",
        installationDate = LocalDate(2025, 1, 1),
    )

    beforeTest {
        every { providers.findById("p1") } returns Provider(
            id = "p1", name = "Converge", paymentScheduleDay = 5,
            status = ProviderStatus.ACTIVE, createdAt = Instant.fromEpochSeconds(0),
        )
        every { stores.findById("s1") } returns Store(
            id = "s1", storeType = StoreType.PUREGOLD, branchCode = "001", name = "Main",
            proofOfInstallationId = "att-1",
            status = StoreStatus.ACTIVE, createdAt = Instant.fromEpochSeconds(0),
        )
        every { accounts.update("acc-1", any()) } returns Account(
            id = "acc-1", accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
            rate = "1500.00", installationDate = LocalDate(2025, 1, 1),
            createdAt = Instant.fromEpochSeconds(0),
        )
    }

    Given("a secretary editing an account directly") {
        When("the update succeeds") {
            useCase("acc-1", request, "user-7")

            Then("the edit is written to the activity log against the account") {
                verify(exactly = 1) { activity.record("user-7", "account.updated", "account", "acc-1") }
            }

            Then("the notification names the actor and links at the activity tab") {
                val ctx = slot<NotificationContext>()
                verify(exactly = 1) { notifications.enqueue(NotificationEvent.ACCOUNT_UPDATED, capture(ctx)) }
                ctx.captured.actorId shouldBe "user-7"
                // The activity record above is what makes this tab worth opening.
                ctx.captured.linkPath shouldBe "/accounts/acc-1?tab=activity"
            }
        }
    }
})
