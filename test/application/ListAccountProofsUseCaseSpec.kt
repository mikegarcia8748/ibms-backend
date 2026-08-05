package com.puregoldbe.ibms.application

import com.puregoldbe.ibms.application.usecase.ListAccountProofsUseCase
import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Account
import com.puregoldbe.ibms.domain.model.AccountProofLink
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.AttachmentRepository
import com.puregoldbe.ibms.domain.port.PresignOp
import com.puregoldbe.ibms.domain.port.PresignPort
import com.puregoldbe.ibms.support.ImmediateTransactionRunner
import com.puregoldbe.ibms.support.uploadedPdfAttachment
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.IsolationMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate

private fun link(
    attachmentId: String,
    purpose: AttachmentPurpose,
    sortOrder: Int,
    linkedAt: String,
) = AccountProofLink(
    accountId = "acc-1",
    attachmentId = attachmentId,
    purpose = purpose,
    sortOrder = sortOrder,
    linkedAt = Instant.parse(linkedAt),
    linkedBy = "actor-1",
)

class ListAccountProofsUseCaseSpec : BehaviorSpec({
    isolationMode = IsolationMode.InstancePerLeaf

    val accounts = mockk<AccountRepository>(relaxed = true)
    val attachments = mockk<AttachmentRepository>(relaxed = true)
    val presign = mockk<PresignPort>()
    val useCase = ListAccountProofsUseCase(accounts, attachments, presign, ImmediateTransactionRunner())

    every { presign.presignedUrl(any(), PresignOp.DOWNLOAD) } answers { "https://blob/${firstArg<String>()}" }

    Given("an account with a deactivation activity and an older subscription activity") {
        every { accounts.findById("acc-1") } returns Account(
            id = "acc-1", accountNumber = "ACC-001", providerId = "p1", storeId = "s1",
            rate = "1000.00", installationDate = LocalDate(2025, 1, 1),
            createdAt = Instant.fromEpochSeconds(0),
        )
        // The repository returns them newest activity first, slot order within.
        val links = listOf(
            link("deact-1", AttachmentPurpose.DEACTIVATION_PROOF, 0, "2026-08-01T00:00:00Z"),
            link("deact-2", AttachmentPurpose.DEACTIVATION_PROOF, 1, "2026-08-01T00:00:00Z"),
            link("sub-1", AttachmentPurpose.SUBSCRIPTION_PROOF, 0, "2026-01-01T00:00:00Z"),
        )
        every { accounts.listProofs("acc-1", null) } returns links
        every { accounts.listProofs("acc-1", AttachmentPurpose.SUBSCRIPTION_PROOF) } returns links.takeLast(1)
        every { attachments.findAllById(any()) } answers {
            firstArg<List<String>>().map { uploadedPdfAttachment(it).copy(fileName = "$it.pdf") }
        }

        When("listing without a filter") {
            val proofs = useCase("acc-1")

            Then("every proof comes back in repository order") {
                proofs.map { it.attachmentId } shouldBe listOf("deact-1", "deact-2", "sub-1")
            }
            Then("each carries its file metadata and a download URL") {
                proofs.first().fileName shouldBe "deact-1.pdf"
                proofs.first().contentType shouldBe "application/pdf"
                proofs.first().downloadUrl shouldBe "https://blob/deact-1"
            }
            Then("proofs of one activity share a linkedAt") {
                proofs.take(2).map { it.linkedAt }.toSet().size shouldBe 1
            }
        }

        When("filtering by purpose") {
            Then("only that activity's proofs come back") {
                useCase("acc-1", AttachmentPurpose.SUBSCRIPTION_PROOF)
                    .map { it.attachmentId } shouldBe listOf("sub-1")
            }
        }
    }

    Given("an unknown account") {
        every { accounts.findById("missing") } returns null

        When("listing") {
            Then("throws NotFound") {
                shouldThrow<DomainError.NotFound> { useCase("missing") }
            }
        }
    }
})
