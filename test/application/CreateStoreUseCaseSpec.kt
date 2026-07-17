package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.CreateStoreUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Store
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.StoreRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant

/** Business rules: a store needs a valid installation proof and a unique branch code. */
class CreateStoreUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val stores = mockk<StoreRepository>()
    val attachments = mockk<AttachmentRepository>()
    val useCase = CreateStoreUseCase(stores, attachments, ImmediateTransactionRunner())

    val input = StoreUpsertRequest(
        storeType = StoreType.PUREGOLD,
        branchCode = "PG-001",
        name = "Puregold Makati",
        proofOfInstallationId = "att-1",
    )

    Given("the referenced installation proof does not exist") {
        every { attachments.exists("att-1") } returns false

        When("creating the store") {
            Then("it is rejected: mandatory proof missing") {
                shouldThrow<DomainError.Validation> { useCase(input, actorId = "actor") }
            }
        }
    }

    Given("a valid proof but a branch code already in use") {
        every { attachments.exists("att-1") } returns true
        every { stores.existsByBranchCode("PG-001") } returns true

        When("creating the store") {
            Then("it is rejected as a duplicate branch code") {
                shouldThrow<DomainError.Conflict> { useCase(input, actorId = "actor") }
            }
        }
    }

    Given("a valid proof and a free branch code") {
        every { attachments.exists("att-1") } returns true
        every { stores.existsByBranchCode("PG-001") } returns false
        val created = Store(
            id = "s1", storeType = StoreType.PUREGOLD, branchCode = "PG-001", name = "Puregold Makati",
            proofOfInstallationId = "att-1", createdAt = Instant.fromEpochSeconds(0),
        )
        every { stores.create(input, "actor") } returns created

        When("creating the store") {
            Then("the store is persisted") {
                useCase(input, actorId = "actor").id shouldBe "s1"
            }
        }
    }
})
