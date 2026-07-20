package com.puregoldbe.ibms.domain

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.service.PasswordPolicy
import com.puregoldbe.ibms.domain.service.UsernamePolicy
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The rules a user-chosen password and an admin-chosen username must satisfy.
 * Pure domain logic, so no database or HTTP stack is involved.
 */
class CredentialPolicySpec : BehaviorSpec({

    Given("a password being set by a user") {
        When("it satisfies every rule") {
            Then("it is accepted") {
                shouldNotThrowAny { PasswordPolicy.validate("Sup3rSecret!Pass", "a.cruz") }
            }
        }

        When("it is shorter than the minimum") {
            Then("it is rejected with the length requirement named") {
                val error = shouldThrow<DomainError.Validation> { PasswordPolicy.validate("Sh0rtPass", "a.cruz") }
                error.message shouldContain "at least ${PasswordPolicy.MIN_LENGTH} characters"
                error.code shouldBe "weak_password"
            }
        }

        When("it exceeds the bcrypt input limit") {
            Then("it is rejected rather than silently truncated") {
                shouldThrow<DomainError.Validation> {
                    PasswordPolicy.validate("A1" + "x".repeat(PasswordPolicy.MAX_LENGTH), "a.cruz")
                }
            }
        }

        When("a required character class is missing") {
            Then("each omission is reported") {
                shouldThrow<DomainError.Validation> { PasswordPolicy.validate("nouppercase99", "a.cruz") }
                    .message shouldContain "uppercase"
                shouldThrow<DomainError.Validation> { PasswordPolicy.validate("NOLOWERCASE99", "a.cruz") }
                    .message shouldContain "lowercase"
                shouldThrow<DomainError.Validation> { PasswordPolicy.validate("NoDigitsHereAtAll", "a.cruz") }
                    .message shouldContain "digit"
                shouldThrow<DomainError.Validation> { PasswordPolicy.validate("Has Spaces In9It", "a.cruz") }
                    .message shouldContain "spaces"
            }
        }

        When("it embeds the account's own username") {
            Then("it is rejected — guessing the username would give away the password") {
                shouldThrow<DomainError.Validation> { PasswordPolicy.validate("A.Cruz-Passw0rd", "a.cruz") }
                    .message shouldContain "username"
            }
        }
    }

    Given("a username chosen by a sysadmin") {
        When("it is well-formed") {
            Then("it is normalised to lowercase so case cannot distinguish two accounts") {
                UsernamePolicy.normalize("  A.Cruz  ") shouldBe "a.cruz"
                UsernamePolicy.normalize("juan_dela-cruz2") shouldBe "juan_dela-cruz2"
            }
        }

        When("it is malformed") {
            Then("it is rejected") {
                listOf(
                    "ab",                    // shorter than the minimum
                    "a".repeat(33),          // longer than the maximum
                    "has space",             // whitespace
                    "user@example.com",      // '@' is not in the alphabet
                    "",
                ).forEach { candidate ->
                    shouldThrow<DomainError.Validation> { UsernamePolicy.normalize(candidate) }
                        .code shouldBe "invalid_username"
                }
            }
        }
    }
})
