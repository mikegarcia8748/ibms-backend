package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.UpdateUserStatusUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.port.UserRepository
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

/** Business rule: sysadmin can toggle user status between active and inactive. */
class UpdateUserStatusUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val users = mockk<UserRepository>(relaxed = false)
    val useCase = UpdateUserStatusUseCase(users, ImmediateTransactionRunner())

    val activeUser = UserProfile(
        id = "u1",
        username = "jdoe",
        name = "John Doe",
        role = UserRole.SECRETARY,
        status = UserStatus.ACTIVE,
        mustChangePassword = false,
    )

    Given("an active user") {
        every { users.findById("u1") } returns activeUser
        every { users.updateStatus("u1", UserStatus.INACTIVE) } returns activeUser.copy(status = UserStatus.INACTIVE)

        When("deactivating them") {
            Then("status becomes INACTIVE") {
                val result = useCase("u1", UserStatus.INACTIVE)
                result.status shouldBe UserStatus.INACTIVE
                verify(exactly = 1) { users.updateStatus("u1", UserStatus.INACTIVE) }
            }
        }
    }

    Given("an inactive user") {
        val inactiveUser = activeUser.copy(status = UserStatus.INACTIVE)
        every { users.findById("u1") } returns inactiveUser
        every { users.updateStatus("u1", UserStatus.ACTIVE) } returns inactiveUser.copy(status = UserStatus.ACTIVE)

        When("reactivating them") {
            Then("status becomes ACTIVE") {
                val result = useCase("u1", UserStatus.ACTIVE)
                result.status shouldBe UserStatus.ACTIVE
            }
        }
    }

    Given("the target does not exist") {
        every { users.findById("ghost") } returns null

        When("updating their status") {
            Then("it fails as not found") {
                shouldThrow<DomainError.NotFound> { useCase("ghost", UserStatus.INACTIVE) }
                verify(exactly = 0) { users.updateStatus(any(), any()) }
            }
        }
    }

    Given("the last sysadmin is being deactivated") {
        every { users.findById("u1") } returns activeUser.copy(role = UserRole.SYSADMIN)
        every { users.updateStatus("u1", UserStatus.INACTIVE) } returns activeUser.copy(
            role = UserRole.SYSADMIN,
            status = UserStatus.INACTIVE,
        )

        When("deactivating them") {
            Then("it succeeds — status is independent of role safety") {
                val result = useCase("u1", UserStatus.INACTIVE)
                result.status shouldBe UserStatus.INACTIVE
            }
        }
    }
})
