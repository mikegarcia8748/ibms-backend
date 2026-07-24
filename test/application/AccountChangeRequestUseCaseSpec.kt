package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ApproveAccountChangeRequestUseCase
import com.puregoldbe.ibms.application.usecase.CancelAccountChangeRequestUseCase
import com.puregoldbe.ibms.application.usecase.GetAccountChangeRequestWithDiffUseCase
import com.puregoldbe.ibms.application.usecase.RejectAccountChangeRequestUseCase
import com.puregoldbe.ibms.application.usecase.SubmitAccountChangeRequestUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountChangeRequest
import com.puregoldbe.ibms.domain.model.AccountChangeRequestStatus
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.Money
import com.puregoldbe.ibms.domain.model.SubmitAccountChangeRequestInput
import com.puregoldbe.ibms.domain.port.AccountChangeRequestRepository
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ActivityRecorder
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
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
 * Unit specs for the account-change-request use cases. Each use case is tested in
 * isolation with mocked repository ports and [ImmediateTransactionRunner], so the
 * only logic under test is the domain rules — not Exposed, Ktor, or Postgres.
 */
class AccountChangeRequestUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val now = Instant.parse("2026-01-01T00:00:00Z")

    val requests = mockk<AccountChangeRequestRepository>(relaxed = false)
    val accounts = mockk<AccountRepository>(relaxed = false)
    val providers = mockk<ProviderRepository>(relaxed = false)
    val attachments = mockk<AttachmentRepository>(relaxed = false)
    val activity = mockk<ActivityRecorder>(relaxed = true)
    val clock = FakeClock(now)

    val submitUseCase = SubmitAccountChangeRequestUseCase(requests, accounts, providers, attachments, activity, clock, ImmediateTransactionRunner())
    val approveUseCase = ApproveAccountChangeRequestUseCase(requests, accounts, providers, activity, clock, ImmediateTransactionRunner())
    val rejectUseCase = RejectAccountChangeRequestUseCase(requests, activity, clock, ImmediateTransactionRunner())
    val cancelUseCase = CancelAccountChangeRequestUseCase(requests, activity, clock, ImmediateTransactionRunner())
    val getWithDiffUseCase = GetAccountChangeRequestWithDiffUseCase(requests, accounts, ImmediateTransactionRunner())

    // --- mock-data helpers ---

    fun activeAccount(
        id: String = "acc-1",
        accountNumber: String = "ACC-001",
        providerId: String = "prov-1",
        storeId: String = "store-1",
        rate: Money = "1000.00",
        installationDate: LocalDate = LocalDate(2025, 1, 1),
        circuitId: String? = "CIR-001",
        planName: String? = "Basic Plan",
    ) = Account(
        id = id,
        accountNumber = accountNumber,
        circuitId = circuitId,
        providerId = providerId,
        storeId = storeId,
        planName = planName,
        rate = rate,
        installationDate = installationDate,
        status = AccountStatus.ACTIVE,
        createdAt = Instant.fromEpochSeconds(0),
    )

    fun pendingRequest(
        id: String = "req-1",
        accountId: String = "acc-1",
        submittedById: String = "sec-1",
        accountNumberNew: String? = null,
        installationDateNew: LocalDate? = null,
        rateNew: Money? = null,
        providerIdNew: String? = null,
        circuitIdNew: String? = null,
        planNameNew: String? = null,
        proofAttachmentId: String? = null,
        status: AccountChangeRequestStatus = AccountChangeRequestStatus.PENDING,
    ) = AccountChangeRequest(
        id = id,
        accountId = accountId,
        submittedById = submittedById,
        status = status,
        accountNumberNew = accountNumberNew,
        installationDateNew = installationDateNew,
        rateNew = rateNew,
        providerIdNew = providerIdNew,
        circuitIdNew = circuitIdNew,
        planNameNew = planNameNew,
        proofAttachmentId = proofAttachmentId,
        createdAt = Instant.fromEpochSeconds(0),
    )

    // =================================================================
    //  SubmitAccountChangeRequestUseCase
    // =================================================================

    Given("submit validates account is active") {
        every { accounts.findById("acc-1") } returns activeAccount().copy(status = AccountStatus.INACTIVE)
        When("submitting for a terminated account") {
            Then("a Conflict is thrown") {
                shouldThrow<DomainError.Conflict> {
                    submitUseCase("acc-1", SubmitAccountChangeRequestInput(accountNumber = "X"), "sec-1")
                }
            }
        }
    }

    Given("submit validates at least one field") {
        every { accounts.findById("acc-1") } returns activeAccount()
        When("submitting with no delta fields") {
            Then("a Validation is thrown") {
                shouldThrow<DomainError.Validation> {
                    submitUseCase("acc-1", SubmitAccountChangeRequestInput(), "sec-1")
                }
            }
        }
    }

    Given("submit auto-cancels existing pending") {
        val existing = pendingRequest(id = "old-req", accountNumberNew = "OLD")
        every { accounts.findById("acc-1") } returns activeAccount()
        every { requests.findPendingByAccountId("acc-1") } returns existing
        every { requests.cancel("old-req", any()) } returns
            existing.copy(status = AccountChangeRequestStatus.CANCELLED, cancelledAt = now)
        every { requests.create("acc-1", "sec-1", any()) } returns
            pendingRequest(id = "new-req", accountNumberNew = "NEW")
        When("submitting with an existing pending request for the same account") {
            Then("the prior request is cancelled and a new pending is created") {
                val result = submitUseCase("acc-1", SubmitAccountChangeRequestInput(accountNumber = "NEW"), "sec-1")
                result.id shouldBe "new-req"
                result.status shouldBe AccountChangeRequestStatus.PENDING
                verify(exactly = 1) { requests.cancel("old-req", any()) }
                verify(exactly = 1) { activity.record("sec-1", "account_change_request.auto_cancelled", "account_change_request", any()) }
                verify(exactly = 1) { activity.record("sec-1", "account_change_request.submitted", "account_change_request", any()) }
            }
        }
    }

    Given("submit validates provider exists") {
        every { accounts.findById("acc-1") } returns activeAccount()
        every { providers.findById("ghost-prov") } returns null
        When("submitting with a non-existent providerId") {
            Then("a Validation is thrown") {
                shouldThrow<DomainError.Validation> {
                    submitUseCase("acc-1", SubmitAccountChangeRequestInput(providerId = "ghost-prov"), "sec-1")
                }
            }
        }
    }

    Given("submit validates rate > 0") {
        every { accounts.findById("acc-1") } returns activeAccount()
        When("submitting with rate = 0") {
            Then("a Validation is thrown") {
                shouldThrow<DomainError.Validation> {
                    submitUseCase("acc-1", SubmitAccountChangeRequestInput(rate = "0"), "sec-1")
                }
            }
        }
        When("submitting with a negative rate") {
            Then("a Validation is thrown") {
                shouldThrow<DomainError.Validation> {
                    submitUseCase("acc-1", SubmitAccountChangeRequestInput(rate = "-50"), "sec-1")
                }
            }
        }
    }

    // =================================================================
    //  ApproveAccountChangeRequestUseCase
    // =================================================================

    Given("approve merges delta correctly") {
        val req = pendingRequest(accountNumberNew = "NEW-NUM", planNameNew = "Premium")
        val account = activeAccount(
            accountNumber = "ACC-001",
            rate = "1000.00",
            circuitId = "CIR-001",
            planName = "Basic",
        )
        every { requests.findById("req-1") } returns req
        every { accounts.findById("acc-1") } returns account
        every { accounts.existsByProviderAndNumber("prov-1", "NEW-NUM") } returns false
        val updateSlot = slot<AccountUpsertRequest>()
        every { accounts.update(any(), capture(updateSlot)) } returns account
        every { requests.approve("req-1", "mgr-1", any()) } returns
            req.copy(status = AccountChangeRequestStatus.APPROVED, approvedById = "mgr-1", approvedAt = now)
        When("approving a request with partial delta (accountNumber + planName)") {
            Then("only non-null delta fields overwrite the account; null fields keep current values") {
                approveUseCase("req-1", "mgr-1")
                val captured = updateSlot.captured
                captured.accountNumber shouldBe "NEW-NUM"
                captured.planName shouldBe "Premium"
                captured.rate shouldBe "1000.00"
                captured.circuitId shouldBe "CIR-001"
                captured.providerId shouldBe "prov-1"
                captured.storeId shouldBe "store-1"
                captured.installationDate shouldBe LocalDate(2025, 1, 1)
                verify(exactly = 1) { activity.record("mgr-1", "account_change_request.approved", "account_change_request", "req-1") }
            }
        }
    }

    Given("approve checks uniqueness when accountNumber changes") {
        val req = pendingRequest(accountNumberNew = "DUP-NUM")
        val account = activeAccount(accountNumber = "ACC-001", providerId = "prov-1")
        every { requests.findById("req-1") } returns req
        every { accounts.findById("acc-1") } returns account
        every { accounts.existsByProviderAndNumber("prov-1", "DUP-NUM") } returns true
        When("approving with a conflicting account number") {
            Then("a Conflict is thrown") {
                shouldThrow<DomainError.Conflict> {
                    approveUseCase("req-1", "mgr-1")
                }
            }
        }
    }

    Given("approve fails on non-pending") {
        every { requests.findById("req-1") } returns
            pendingRequest(status = AccountChangeRequestStatus.APPROVED)
        When("approving an already-approved request") {
            Then("a Conflict is thrown") {
                shouldThrow<DomainError.Conflict> {
                    approveUseCase("req-1", "mgr-1")
                }
            }
        }
    }

    // =================================================================
    //  RejectAccountChangeRequestUseCase
    // =================================================================

    Given("reject requires reason") {
        every { requests.findById("req-1") } returns pendingRequest()
        When("rejecting with a blank reason") {
            Then("a Validation is thrown") {
                shouldThrow<DomainError.Validation> {
                    rejectUseCase("req-1", "   ", "mgr-1")
                }
            }
        }
    }

    // =================================================================
    //  CancelAccountChangeRequestUseCase
    // =================================================================

    Given("cancel only by submitter") {
        every { requests.findById("req-1") } returns pendingRequest(submittedById = "sec-1")
        When("cancelling as a different user") {
            Then("a Forbidden is thrown") {
                shouldThrow<DomainError.Forbidden> {
                    cancelUseCase("req-1", "other-sec")
                }
            }
        }
    }

    // =================================================================
    //  GetAccountChangeRequestWithDiffUseCase
    // =================================================================

    Given("getWithDiff computes correct FieldDiff list") {
        val req = pendingRequest(
            accountNumberNew = "NEW-NUM",
            rateNew = "2000.00",
            circuitIdNew = "CIR-NEW",
        )
        val account = activeAccount(
            accountNumber = "ACC-001",
            rate = "1000.00",
            circuitId = "CIR-001",
        )
        every { requests.findById("req-1") } returns req
        every { accounts.findById("acc-1") } returns account
        When("getting the diff for a request with multiple deltas") {
            Then("the diff list contains one entry per changed field with correct current/proposed values") {
                val result = getWithDiffUseCase("req-1")
                result.request.id shouldBe "req-1"
                result.diff.size shouldBe 3
                result.diff.find { it.field == "accountNumber" }!!.let {
                    it.currentValue shouldBe "ACC-001"
                    it.proposedValue shouldBe "NEW-NUM"
                }
                result.diff.find { it.field == "rate" }!!.let {
                    it.currentValue shouldBe "1000.00"
                    it.proposedValue shouldBe "2000.00"
                }
                result.diff.find { it.field == "circuitId" }!!.let {
                    it.currentValue shouldBe "CIR-001"
                    it.proposedValue shouldBe "CIR-NEW"
                }
            }
        }
    }
})
