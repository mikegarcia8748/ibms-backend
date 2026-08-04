package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ProvisionUserUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.UserProfile
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.model.UserStatus
import com.puregoldbe.ibms.domain.model.NotificationEvent
import com.puregoldbe.ibms.domain.port.Clock
import com.puregoldbe.ibms.domain.port.NotificationRoleDefaultsRepository
import com.puregoldbe.ibms.domain.port.NotificationSubscriptionRepository
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
import io.mockk.verify
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

/**
 * Business rule: sysadmin provisions users; status defaults to ACTIVE, the optional
 * email is normalised, and the new account's notification subscriptions are seeded
 * from the defaults for its role.
 */
class ProvisionUserUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val users = mockk<UserRepository>(relaxed = false)
    val subscriptions = mockk<NotificationSubscriptionRepository>(relaxed = true)
    val roleDefaults = mockk<NotificationRoleDefaultsRepository>()
    val hasher = mockk<PasswordHasher>()
    val secrets = mockk<SecretGenerator>()
    val clock = mockk<Clock>()
    val policy = SessionPolicy(
        refreshTtl = 720.hours,
        temporaryPasswordTtl = 72.hours,
        maxFailedLogins = 5,
        lockoutDuration = 15.hours,
    )
    val useCase = ProvisionUserUseCase(
        users, subscriptions, roleDefaults, hasher, secrets, policy, clock, ImmediateTransactionRunner(),
    )

    val now = Instant.fromEpochSeconds(1_700_000_000)

    beforeTest {
        every { clock.now() } returns now
        every { secrets.temporaryPassword() } returns "Temp-Passw0rd!"
        every { hasher.hash(any()) } returns "\$2a\$12\$hashedvalue"
        every { roleDefaults.forRole(any()) } returns emptySet()
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

    Given("a request whose email needs normalising") {
        val request = ProvisionUserRequest(
            username = "jdoe",
            name = "John Doe",
            email = "  John.Doe@Example.COM  ",
            role = UserRole.SECRETARY,
        )

        every { users.existsByUsername("jdoe") } returns false
        val inputSlot = slot<ProvisionUserRequest>()
        every { users.create(capture(inputSlot), any(), any(), any()) } answers {
            createdProfile.copy(email = inputSlot.captured.email)
        }

        When("provisioning") {
            Then("the address is trimmed and lowercased before it reaches the repository") {
                useCase(request)
                inputSlot.captured.email shouldBe "john.doe@example.com"
            }
        }
    }

    Given("a request with a malformed email") {
        val request = ProvisionUserRequest(
            username = "jdoe",
            name = "John Doe",
            email = "not-an-address",
            role = UserRole.SECRETARY,
        )

        every { users.existsByUsername("jdoe") } returns false

        When("provisioning") {
            Then("a Validation error is thrown and no user is created") {
                shouldThrow<DomainError.Validation> { useCase(request) }
                verify(exactly = 0) { users.create(any(), any(), any(), any()) }
            }
        }
    }

    Given("defaults configured for the requested role") {
        val request = ProvisionUserRequest(username = "jdoe", name = "John Doe", role = UserRole.FINANCE)
        val defaults = setOf(NotificationEvent.TOPSHEET_COMPILED, NotificationEvent.TOPSHEET_RELEASED)

        every { users.existsByUsername("jdoe") } returns false
        every { users.create(any(), any(), any(), any()) } returns createdProfile.copy(role = UserRole.FINANCE)

        When("provisioning") {
            Then("the new user is seeded with that role's default subscriptions") {
                // Stubbed here, not in the Given: beforeTest re-registers the catch-all
                // forRole(any()) stub after the Given body has already run.
                every { roleDefaults.forRole(UserRole.FINANCE) } returns defaults
                val result = useCase(request)
                verify(exactly = 1) { subscriptions.setForUser(result.user.id, defaults) }
            }
        }
    }

    Given("no defaults for the requested role") {
        val request = ProvisionUserRequest(username = "jdoe", name = "John Doe", role = UserRole.MANAGER)

        every { users.existsByUsername("jdoe") } returns false
        every { users.create(any(), any(), any(), any()) } returns createdProfile.copy(role = UserRole.MANAGER)
        every { roleDefaults.forRole(UserRole.MANAGER) } returns emptySet()

        When("provisioning") {
            Then("no subscription write happens at all") {
                useCase(request)
                verify(exactly = 0) { subscriptions.setForUser(any(), any()) }
            }
        }
    }
})
