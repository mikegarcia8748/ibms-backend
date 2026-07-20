package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.repository.ExposedAccountRepository
import com.puregoldbe.ibms.adapter.repository.ExposedAttachmentRepository
import com.puregoldbe.ibms.adapter.repository.ExposedInvoiceSequenceRepository
import com.puregoldbe.ibms.adapter.repository.ExposedProviderRepository
import com.puregoldbe.ibms.adapter.repository.ExposedStoreRepository
import com.puregoldbe.ibms.adapter.repository.ExposedUserRepository
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.StoreStatus
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.PostgresTestDb
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * Integration spec: proves the Exposed mappings + Flyway schema round-trip against
 * a real Postgres — enums, numeric(14,2) money, DATE, and the full FK chain
 * user -> attachment -> store -> provider -> account.
 */
class ExposedRepositorySpec : BehaviorSpec({

    val db = PostgresTestDb.database
    val users = ExposedUserRepository()
    val attachments = ExposedAttachmentRepository()
    val stores = ExposedStoreRepository()
    val providers = ExposedProviderRepository()
    val accounts = ExposedAccountRepository()
    val sequences = ExposedInvoiceSequenceRepository()

    Given("the migrated schema and the bootstrap admin seed") {
        When("querying the seeded sysadmin") {
            Then("V3's bootstrap admin exists and is a sysadmin") {
                transaction(db) {
                    val admin = users.findByUsername("mikepg").shouldNotBeNull()
                    admin.role shouldBe UserRole.SYSADMIN
                    // Not an exact count: specs share one container, and any spec
                    // that calls signIn(SYSADMIN) adds another sysadmin row.
                    users.countByRole(UserRole.SYSADMIN) shouldBeGreaterThanOrEqual 1
                }
            }
        }
    }

    Given("the full billing entity chain") {
        When("creating user -> attachment -> store -> provider -> account") {
            Then("every entity round-trips with correct enum/money/date mapping") {
                transaction(db) {
                    val secretary = users.create(
                        input = ProvisionUserRequest(
                            username = "sec.one",
                            name = "Sec One",
                            role = UserRole.SECRETARY,
                        ),
                        passwordHash = "\$2a\$04\$notarealhashbutthecolumnonlyholdstext000000000000000",
                        tempPasswordExpiresAt = Instant.fromEpochSeconds(1_800_000_000),
                        at = Instant.fromEpochSeconds(1_700_000_000),
                    )
                    secretary.role shouldBe UserRole.SECRETARY
                    // Provisioning always starts in the temporary-password state.
                    secretary.mustChangePassword shouldBe true

                    val proof = attachments.create(
                        purpose = AttachmentPurpose.INSTALLATION_PROOF,
                        entityType = null, entityId = null,
                        storageKey = "installation_proof/test.pdf",
                        contentType = "application/pdf", sizeBytes = 1234L, uploadedBy = secretary.id,
                    )
                    proof.purpose shouldBe AttachmentPurpose.INSTALLATION_PROOF

                    val store = stores.create(
                        StoreUpsertRequest(
                            storeType = StoreType.PUREGOLD,
                            branchCode = "PG-001",
                            name = "Puregold Makati",
                            proofOfInstallationId = proof.id,
                        ),
                        createdBy = secretary.id,
                    )
                    store.status shouldBe StoreStatus.ACTIVE
                    stores.existsByBranchCode("PG-001") shouldBe true

                    val provider = providers.create("Converge", paymentScheduleDay = 15)
                    sequences.seed(provider.id, "CONV-")
                    sequences.nextValue(provider.id) shouldBe 1
                    sequences.nextValue(provider.id) shouldBe 2

                    val account = accounts.create(
                        AccountUpsertRequest(
                            accountNumber = "0030301234567",
                            providerId = provider.id,
                            storeId = store.id,
                            rate = "1000",
                            installationDate = LocalDate(2026, 8, 20),
                            subscriptionProofIds = listOf(proof.id),
                        ),
                        createdBy = secretary.id,
                    )
                    account.rate shouldBe "1000.00"            // numeric(14,2) round-trip
                    account.installationDate shouldBe LocalDate(2026, 8, 20)
                    account.subscriptionProofIds shouldBe listOf(proof.id)
                    accounts.existsByProviderAndNumber(provider.id, "0030301234567") shouldBe true
                    accounts.list(storeId = store.id, providerId = null, status = null).size shouldBe 1
                }
            }
        }
    }
})
