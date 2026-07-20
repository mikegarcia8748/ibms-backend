package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.UpdateUserRoleUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.UserRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/** Business rule: the last remaining sysadmin cannot be demoted. */
class UpdateUserRoleUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val users = mockk<UserRepository>(relaxed = false)
    val useCase = UpdateUserRoleUseCase(users, ImmediateTransactionRunner())

    val admin = UserProfile(
        id = "u1",
        username = "admin",
        name = "Admin",
        role = UserRole.SYSADMIN,
        mustChangePassword = false,
    )

    Given("the target is the only sysadmin") {
        every { users.findById("u1") } returns admin
        every { users.countByRole(UserRole.SYSADMIN) } returns 1

        When("demoting them to secretary") {
            Then("it is rejected as a conflict and no update happens") {
                shouldThrow<DomainError.Conflict> { useCase("u1", UserRole.SECRETARY) }
                verify(exactly = 0) { users.updateRole(any(), any()) }
            }
        }
    }

    Given("another sysadmin still exists") {
        every { users.findById("u1") } returns admin
        every { users.countByRole(UserRole.SYSADMIN) } returns 2
        every { users.updateRole("u1", UserRole.SECRETARY) } returns admin.copy(role = UserRole.SECRETARY)

        When("demoting one of them") {
            Then("the demotion succeeds") {
                useCase("u1", UserRole.SECRETARY).role shouldBe UserRole.SECRETARY
            }
        }
    }

    Given("the target does not exist") {
        every { users.findById("ghost") } returns null

        When("updating their role") {
            Then("it fails as not found") {
                shouldThrow<DomainError.NotFound> { useCase("ghost", UserRole.FINANCE) }
            }
        }
    }
})
