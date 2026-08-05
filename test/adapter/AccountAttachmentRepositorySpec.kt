package com.puregoldbe.ibms.adapter

import com.puregoldbe.ibms.adapter.repository.ExposedAccountRepository
import com.puregoldbe.ibms.adapter.repository.ExposedAttachmentRepository
import com.puregoldbe.ibms.adapter.repository.ExposedProviderRepository
import com.puregoldbe.ibms.adapter.repository.ExposedStoreRepository
import com.puregoldbe.ibms.adapter.repository.ExposedUserRepository
import com.puregoldbe.ibms.domain.model.AccountUpsertRequest
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.model.ProvisionUserRequest
import com.puregoldbe.ibms.domain.model.StoreType
import com.puregoldbe.ibms.domain.model.StoreUpsertRequest
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.support.PostgresTestDb
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.jdbc.transactions.transaction

/**
 * The V23 link table against real Postgres. The load-bearing claim is that `linked_at`
 * defaults to the TRANSACTION timestamp, so the 1..3 proofs of one activity share it
 * exactly — that is what lets a client group them, with no proof_group_id column. The
 * other is that `subscriptionProofIds` is purpose-filtered, which is the shipped defect
 * this migration fixes.
 */
class AccountAttachmentRepositorySpec : BehaviorSpec({

    val db = PostgresTestDb.database
    val users = ExposedUserRepository()
    val attachments = ExposedAttachmentRepository()
    val stores = ExposedStoreRepository()
    val providers = ExposedProviderRepository()
    val accounts = ExposedAccountRepository()

    fun tag() = System.nanoTime()

    Given("an account with subscription proofs and a later deactivation") {
        When("linking each activity's proofs") {
            Then("purpose, slot, actor and the shared linked_at all round-trip") {
                transaction(db) {
                    val actor = users.create(
                        input = ProvisionUserRequest(
                            username = "link.${tag()}", name = "Linker", role = UserRole.SECRETARY,
                        ),
                        passwordHash = "\$2a\$04\$notarealhashbutthecolumnonlyholdstext000000000000000",
                        tempPasswordExpiresAt = Instant.fromEpochSeconds(1_800_000_000),
                        at = Instant.fromEpochSeconds(1_700_000_000),
                    )

                    fun proof(purpose: AttachmentPurpose, name: String) = attachments.create(
                        purpose = purpose, entityType = null, entityId = null,
                        storageKey = "${purpose.name.lowercase()}/${tag()}-$name",
                        contentType = "application/pdf", sizeBytes = 1234L, uploadedBy = actor.id,
                        fileName = name,
                    )

                    val installProof = proof(AttachmentPurpose.INSTALLATION_PROOF, "inst.pdf")
                    val store = stores.create(
                        StoreUpsertRequest(
                            storeType = StoreType.PUREGOLD,
                            branchCode = "AA-${tag()}",
                            name = "Store",
                            proofOfInstallationId = installProof.id,
                        ),
                        createdBy = actor.id,
                    )
                    val provider = providers.create("Prov-${tag()}", paymentScheduleDay = 15)

                    val subs = listOf("s1.pdf", "s2.pdf", "s3.pdf")
                        .map { proof(AttachmentPurpose.SUBSCRIPTION_PROOF, it) }
                    val account = accounts.create(
                        AccountUpsertRequest(
                            accountNumber = "AA-${tag()}",
                            providerId = provider.id,
                            storeId = store.id,
                            rate = "1000",
                            installationDate = LocalDate(2026, 1, 1),
                            subscriptionProofIds = subs.map { it.id },
                        ),
                        createdBy = actor.id,
                    )

                    // fileName is persisted now, not merely baked into storage_key.
                    attachments.findById(subs[0].id).shouldNotBeNull().fileName shouldBe "s1.pdf"

                    val subLinks = accounts.listProofs(account.id, AttachmentPurpose.SUBSCRIPTION_PROOF)
                    subLinks.map { it.attachmentId } shouldBe subs.map { it.id }
                    subLinks.map { it.sortOrder } shouldBe listOf(0, 1, 2)
                    subLinks.map { it.linkedBy }.toSet() shouldBe setOf(actor.id)
                    subLinks.map { it.transferId }.toSet() shouldBe setOf(null)
                    // One activity -> one timestamp, byte for byte.
                    subLinks.map { it.linkedAt }.toSet().size shouldBe 1

                    val deacts = listOf("d1.pdf", "d2.pdf")
                        .map { proof(AttachmentPurpose.DEACTIVATION_PROOF, it) }
                    accounts.linkProofs(
                        account.id, deacts.map { it.id },
                        AttachmentPurpose.DEACTIVATION_PROOF, actor.id,
                    )

                    val deactLinks = accounts.listProofs(account.id, AttachmentPurpose.DEACTIVATION_PROOF)
                    deactLinks.map { it.attachmentId } shouldBe deacts.map { it.id }
                    deactLinks.map { it.sortOrder } shouldBe listOf(0, 1)

                    accounts.listProofs(account.id).size shouldBe 5

                    // THE regression guard: the deactivation proofs must not leak into the
                    // account's subscription list, which is what shipped before V23.
                    accounts.findById(account.id).shouldNotBeNull()
                        .subscriptionProofIds shouldBe subs.map { it.id }
                }
            }
        }

        When("linking the same proof twice") {
            Then("the second link is ignored rather than failing") {
                transaction(db) {
                    val actor = users.create(
                        input = ProvisionUserRequest(
                            username = "dupe.${tag()}", name = "Dupe", role = UserRole.SECRETARY,
                        ),
                        passwordHash = "\$2a\$04\$notarealhashbutthecolumnonlyholdstext000000000000000",
                        tempPasswordExpiresAt = Instant.fromEpochSeconds(1_800_000_000),
                        at = Instant.fromEpochSeconds(1_700_000_000),
                    )
                    val installProof = attachments.create(
                        purpose = AttachmentPurpose.INSTALLATION_PROOF, entityType = null, entityId = null,
                        storageKey = "installation_proof/${tag()}", contentType = "application/pdf",
                        sizeBytes = 1L, uploadedBy = actor.id,
                    )
                    val store = stores.create(
                        StoreUpsertRequest(
                            storeType = StoreType.PUREGOLD, branchCode = "AB-${tag()}", name = "Store",
                            proofOfInstallationId = installProof.id,
                        ),
                        createdBy = actor.id,
                    )
                    val provider = providers.create("Prov-${tag()}", paymentScheduleDay = 15)
                    val sub = attachments.create(
                        purpose = AttachmentPurpose.SUBSCRIPTION_PROOF, entityType = null, entityId = null,
                        storageKey = "subscription_proof/${tag()}", contentType = "application/pdf",
                        sizeBytes = 1L, uploadedBy = actor.id,
                    )
                    val account = accounts.create(
                        AccountUpsertRequest(
                            accountNumber = "AB-${tag()}", providerId = provider.id, storeId = store.id,
                            rate = "1000", installationDate = LocalDate(2026, 1, 1),
                            subscriptionProofIds = listOf(sub.id),
                        ),
                        createdBy = actor.id,
                    )

                    accounts.linkProofs(
                        account.id, listOf(sub.id), AttachmentPurpose.SUBSCRIPTION_PROOF, actor.id,
                    )
                    accounts.listProofs(account.id).size shouldBe 1
                }
            }
        }
    }
})
