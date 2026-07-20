package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ProvisionUserUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.PasswordHasher
import com.puregoldbe.ibms.domain.port.SecretGenerator
import com.puregoldbe.ibms.domain.port.UserRepository
import com.puregoldbe.ibms.domain.service.SessionPolicy
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotBeBlank
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

/** Business rule: sysadmin provisions users without email; status defaults to ACTIVE. */
class ProvisionUserUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val users = mockk<UserRepository>(relaxed = false)
    val hasher = mockk<PasswordHasher>()
    val secrets = mockk<SecretGenerator>()
    val clock = mockk<Clock>()
    val policy = SessionPolicy(
        refreshTtl = 720.hours,
        temporaryPasswordTtl = 72.hours,
        maxFailedLogins = 5,
        lockoutDuration = 15.hours,
    )
    val useCase = ProvisionUserUseCase(users, hasher, secrets, policy, clock, ImmediateTransactionRunner())

    val now = Instant.fromEpochSeconds(1_700_000_000)

    beforeTest {
        every { clock.now() } returns now
        every { secrets.temporaryPassword() } returns "Temp-Passw0rd!"
        every { hasher.hash(any()) } returns "\$2a\$12\$hashedvalue"
    }

    val createdProfile = UserProfile(
        id = "new-uuid",
        username = "jdoe",
        name = "John Doe",
        firstName = "John",
        lastName = "Doe",
        employeeNumber = "010005529",
        role = UserRole.SECRETARY,
        status = UserStatus.ACTIVE,
        mustChangePassword = true,
    )

    Given("a valid provision request without email") {
        val request = ProvisionUserRequest(
            username = "jdoe",
            name = "John Doe",
            firstName = "John",
            lastName = "Doe",
            employeeNumber = "010005529",
            role = UserRole.SECRETARY,
        )

        every { users.existsByUsername("jdoe") } returns false
        val inputSlot = slot<ProvisionUserRequest>()
        every { users.create(capture(inputSlot), any(), any(), any()) } answers {
            createdProfile.copy(
                username = inputSlot.captured.username,
                name = inputSlot.captured.name,
                role = inputSlot.captured.role,
                status = inputSlot.captured.status,
            )
        }

        When("provisioning the user") {
            Then("user is created with ACTIVE status and a temporary password is returned") {
                val result = useCase(request)
                result.user.status shouldBe UserStatus.ACTIVE
                result.temporaryPassword.shouldNotBeBlank()
                result.user.mustChangePassword shouldBe true
                result.user.role shouldBe UserRole.SECRETARY
            }
        }
    }

    Given("a request with explicit ACTIVE status") {
        val request = ProvisionUserRequest(
            username = "jdoe",
            name = "John Doe",
            role = UserRole.FINANCE,
            status = UserStatus.ACTIVE,
        )

        every { users.existsByUsername("jdoe") } returns false
        every { users.create(any(), any(), any(), any()) } returns createdProfile.copy(role = UserRole.FINANCE)

        When("provisioning") {
            Then("the user is created with the specified status") {
                val result = useCase(request)
                result.user.status shouldBe UserStatus.ACTIVE
            }
        }
    }

    Given("a duplicate username") {
        val request = ProvisionUserRequest(
            username = "jdoe",
            name = "Another John",
            role = UserRole.SECRETARY,
        )

        every { users.existsByUsername("jdoe") } returns true

        When("provisioning") {
            Then("a Conflict is thrown") {
                shouldThrow<DomainError.Conflict> { useCase(request) }
            }
        }
    }

    Given("a blank name") {
        val request = ProvisionUserRequest(
            username = "jdoe",
            name = "  ",
            role = UserRole.SECRETARY,
        )

        When("provisioning") {
            Then("a Validation error is thrown") {
                shouldThrow<DomainError.Validation> { useCase(request) }
            }
        }
    }
})
