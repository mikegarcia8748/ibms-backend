package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.CreateAccountUseCase
import com.puregoldbe.ibms.application.usecase.CreateISPAccountUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.CreateISPAccountInput
import com.puregoldbe.ibms.domain.model.Provider
import com.puregoldbe.ibms.domain.model.ProviderStatus
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.support.FakeClock
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

class CreateISPAccountUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val providers = mockk<ProviderRepository>()
    val attachments = mockk<AttachmentRepository>()
    val createAccount = mockk<CreateAccountUseCase>()
    // Fixed clock: 2026-07-23T00:00:00Z → Manila is UTC+8 → local date = 2026-07-23
    val clock = FakeClock(Instant.parse("2026-07-23T00:00:00Z"))
    val tx = ImmediateTransactionRunner()

    val useCase = CreateISPAccountUseCase(createAccount, providers, attachments, clock, tx)

    fun provider(paymentScheduleDay: Int) = Provider(
        id = "prov-1",
        name = "Globe",
        paymentScheduleDay = paymentScheduleDay,
        status = ProviderStatus.ACTIVE,
        createdAt = Instant.fromEpochSeconds(0),
    )

    fun validInput(
        accountNumber: String = "ACCT-001",
        circuitId: String? = "CKT-100",
        providerId: String = "prov-1",
        storeId: String = "store-1",
        rate: String = "1500.00",
        installationDate: LocalDate = LocalDate(2026, 7, 20),
        subscriptionProofId: String = "att-proof",
    ) = CreateISPAccountInput(
        accountNumber = accountNumber,
        circuitId = circuitId,
        providerId = providerId,
        storeId = storeId,
        rate = rate,
        installationDate = installationDate,
        subscriptionProofId = subscriptionProofId,
    )

    val sampleAccount = Account(
        id = "acc-1",
        accountNumber = "ACCT-001",
        circuitId = "CKT-100",
        providerId = "prov-1",
        storeId = "store-1",
        rate = "1500.00",
        installationDate = LocalDate(2026, 7, 20),
        isProrated = true,
        status = AccountStatus.ACTIVE,
        createdAt = Instant.fromEpochSeconds(0),
    )

    // ======================================================================
    //  Happy Path
    // ======================================================================
    Given("valid input with installationDate AFTER paymentScheduleDay") {
        val input = validInput(installationDate = LocalDate(2026, 7, 20)) // day 20
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 15)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            val result = useCase(input, "actor")

            Then("the account is created with isProrated = true") {
                result.id shouldBe "acc-1"
                reqSlot.captured.isProrated shouldBe true
            }
        }
    }

    Given("valid input with installationDate BEFORE paymentScheduleDay") {
        val input = validInput(installationDate = LocalDate(2026, 7, 10)) // day 10
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 15)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("the account is created with isProrated = false") {
                reqSlot.captured.isProrated shouldBe false
            }
        }
    }

    Given("valid input with installationDate ON paymentScheduleDay") {
        val input = validInput(installationDate = LocalDate(2026, 7, 15)) // day 15
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 15)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("isProrated = false because == is not >") {
                reqSlot.captured.isProrated shouldBe false
            }
        }
    }

    // ======================================================================
    //  Validation Failures
    // ======================================================================
    Given("a blank accountNumber") {
        val input = validInput(accountNumber = "   ")

        When("creating the ISP account") {
            Then("it throws validation error") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "accountNumber is required"
            }
        }
    }

    Given("a null circuitId") {
        val input = validInput(circuitId = null)

        When("creating the ISP account") {
            Then("it throws validation error for circuitId") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "circuitId is required for ISP accounts"
            }
        }
    }

    Given("a blank circuitId") {
        val input = validInput(circuitId = "   ")

        When("creating the ISP account") {
            Then("it throws validation error for circuitId") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "circuitId is required for ISP accounts"
            }
        }
    }

    Given("a rate of zero") {
        val input = validInput(rate = "0.00")

        When("creating the ISP account") {
            Then("it throws validation error for rate") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "rate (MRC) must be greater than 0"
            }
        }
    }

    Given("a negative rate") {
        val input = validInput(rate = "-100.00")

        When("creating the ISP account") {
            Then("it throws validation error for rate") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "rate (MRC) must be greater than 0"
            }
        }
    }

    Given("a future installationDate") {
        // Clock returns 2026-07-23 Manila; date = 2026-08-01 is in the future
        val input = validInput(installationDate = LocalDate(2026, 8, 1))

        When("creating the ISP account") {
            Then("it throws validation error") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "installationDate cannot be in the future"
            }
        }
    }

    Given("a blank subscriptionProofId") {
        val input = validInput(subscriptionProofId = "   ")

        When("creating the ISP account") {
            Then("it throws validation error for subscriptionProofId") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "subscriptionProofId is required"
            }
        }
    }

    Given("a non-existent subscriptionProofId") {
        val input = validInput(subscriptionProofId = "missing-att")
        every { attachments.exists("missing-att") } returns false

        When("creating the ISP account") {
            Then("it throws validation error for missing attachment") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "subscription proof attachment not found"
            }
        }
    }

    Given("an unknown providerId") {
        val input = validInput(providerId = "unknown-prov")
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("unknown-prov") } returns null

        When("creating the ISP account") {
            Then("it throws validation error for unknown provider") {
                val err = shouldThrow<DomainError.Validation> { useCase(input, "actor") }
                err.message shouldBe "unknown providerId unknown-prov"
            }
        }
    }

    // ======================================================================
    //  Proration Edge Cases
    // ======================================================================
    Given("installDate day 1 with paymentScheduleDay 5") {
        val input = validInput(installationDate = LocalDate(2026, 3, 1))
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 5)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("isProrated = false (1 < 5)") {
                reqSlot.captured.isProrated shouldBe false
            }
        }
    }

    Given("installDate day 5 with paymentScheduleDay 5") {
        val input = validInput(installationDate = LocalDate(2026, 3, 5))
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 5)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("isProrated = false (== is not >)") {
                reqSlot.captured.isProrated shouldBe false
            }
        }
    }

    Given("installDate day 6 with paymentScheduleDay 5") {
        val input = validInput(installationDate = LocalDate(2026, 3, 6))
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 5)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("isProrated = true (6 > 5)") {
                reqSlot.captured.isProrated shouldBe true
            }
        }
    }

    Given("installDate day 31 with paymentScheduleDay 31") {
        val input = validInput(installationDate = LocalDate(2026, 1, 31))
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 31)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("isProrated = false (== is not >)") {
                reqSlot.captured.isProrated shouldBe false
            }
        }
    }

    // ======================================================================
    //  Input Sanitization
    // ======================================================================
    Given("accountNumber with leading/trailing spaces") {
        val input = validInput(accountNumber = "  ACCT-001  ", installationDate = LocalDate(2026, 7, 10))
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 15)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("accountNumber is trimmed in the request") {
                reqSlot.captured.accountNumber shouldBe "ACCT-001"
            }
        }
    }

    Given("circuitId with leading/trailing spaces") {
        val input = validInput(circuitId = "  CKT-100  ", installationDate = LocalDate(2026, 7, 10))
        every { attachments.exists("att-proof") } returns true
        every { providers.findById("prov-1") } returns provider(paymentScheduleDay = 15)
        val reqSlot = slot<AccountUpsertRequest>()
        coEvery { createAccount(capture(reqSlot), "actor") } returns sampleAccount

        When("creating the ISP account") {
            useCase(input, "actor")

            Then("circuitId is trimmed in the request") {
                reqSlot.captured.circuitId shouldBe "CKT-100"
            }
        }
    }
})
