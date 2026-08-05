package com.puregoldbe.ibms.domain

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.Attachment
import com.puregoldbe.ibms.domain.model.AttachmentPurpose
import com.puregoldbe.ibms.domain.service.PdfProofPolicy
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant

/** Pure-policy unit spec: the PDF magic check and the uploaded-proof guard. */
class PdfProofPolicySpec : StringSpec({

    fun proof(contentType: String?, sizeBytes: Long?) = Attachment(
        id = "att-1",
        purpose = AttachmentPurpose.SUBSCRIPTION_PROOF,
        storageKey = "subscription_proof/att-1",
        contentType = contentType,
        sizeBytes = sizeBytes,
        createdAt = Instant.fromEpochSeconds(0),
    )

    "isPdfBytes is true for content starting with the %PDF magic" {
        PdfProofPolicy.isPdfBytes("%PDF-1.4\nrest".toByteArray()) shouldBe true
    }

    "isPdfBytes is false for non-PDF content" {
        PdfProofPolicy.isPdfBytes("not a pdf at all".toByteArray()) shouldBe false
    }

    "isPdfBytes is false for input shorter than the magic" {
        PdfProofPolicy.isPdfBytes("%PD".toByteArray()) shouldBe false
    }

    "requireUploadedPdf accepts a fully-uploaded PDF" {
        // Does not throw.
        PdfProofPolicy.requireUploadedPdf(proof("application/pdf", 512L), "proofId")
    }

    "requireUploadedPdf accepts application/pdf case-insensitively" {
        PdfProofPolicy.requireUploadedPdf(proof("APPLICATION/PDF", 512L), "proofId")
    }

    "requireUploadedPdf rejects a missing attachment" {
        val e = shouldThrow<DomainError.Validation> { PdfProofPolicy.requireUploadedPdf(null, "proofId") }
        e.message shouldBe "a valid proofId is required"
    }

    "requireUploadedPdf rejects a presigned-but-not-uploaded attachment" {
        val e = shouldThrow<DomainError.Validation> {
            PdfProofPolicy.requireUploadedPdf(proof("application/pdf", null), "proofId")
        }
        e.message shouldBe "proofId has not been uploaded yet"
    }

    "requireUploadedPdf rejects a non-PDF content type" {
        val e = shouldThrow<DomainError.Validation> {
            PdfProofPolicy.requireUploadedPdf(proof("image/png", 512L), "proofId")
        }
        e.message shouldBe "proofId must be a PDF file"
    }

    // --- mergeProofIds: collapsing the deprecated scalar and the array ---

    "mergeProofIds falls back to the scalar when the array is empty" {
        PdfProofPolicy.mergeProofIds("a", emptyList()) shouldBe listOf("a")
    }

    "mergeProofIds prefers the array when both are present" {
        PdfProofPolicy.mergeProofIds("a", listOf("b", "c")) shouldBe listOf("b", "c")
    }

    "mergeProofIds is empty when neither is supplied" {
        PdfProofPolicy.mergeProofIds(null, emptyList()) shouldBe emptyList()
    }

    "mergeProofIds trims and drops blanks" {
        PdfProofPolicy.mergeProofIds(null, listOf(" a ", "", "  ", "b")) shouldBe listOf("a", "b")
    }

    // --- requireProofSet: the whole 1..3 rule for one activity ---

    fun uploaded(id: String, purpose: AttachmentPurpose = AttachmentPurpose.DEACTIVATION_PROOF) = Attachment(
        id = id,
        purpose = purpose,
        storageKey = "${purpose.name.lowercase()}/$id",
        contentType = "application/pdf",
        sizeBytes = 512L,
        createdAt = Instant.fromEpochSeconds(0),
    )

    fun requireSet(ids: List<String>, lookup: (String) -> Attachment? = { uploaded(it) }) =
        PdfProofPolicy.requireProofSet(ids, AttachmentPurpose.DEACTIVATION_PROOF, "proofIds", lookup = lookup)

    "requireProofSet accepts one proof" {
        requireSet(listOf("a")).map { it.id } shouldBe listOf("a")
    }

    "requireProofSet accepts the maximum of three, in request order" {
        requireSet(listOf("c", "a", "b")).map { it.id } shouldBe listOf("c", "a", "b")
    }

    "requireProofSet rejects a fourth proof" {
        val e = shouldThrow<DomainError.Validation> { requireSet(listOf("a", "b", "c", "d")) }
        e.message shouldBe "at most 3 files may be attached to proofIds"
    }

    "requireProofSet rejects an empty set with the default message" {
        val e = shouldThrow<DomainError.Validation> { requireSet(emptyList()) }
        e.message shouldBe "at least one proofIds is required"
    }

    "requireProofSet reports a caller-supplied message for an empty set" {
        val e = shouldThrow<DomainError.Validation> {
            PdfProofPolicy.requireProofSet(
                emptyList(),
                AttachmentPurpose.SUBSCRIPTION_PROOF,
                "subscriptionProofIds",
                missingMessage = "a subscription proof (PDF) is required",
            ) { uploaded(it) }
        }
        e.message shouldBe "a subscription proof (PDF) is required"
    }

    "requireProofSet rejects duplicate ids" {
        val e = shouldThrow<DomainError.Validation> { requireSet(listOf("a", "a")) }
        e.message shouldBe "proofIds contains duplicate attachment ids"
    }

    "requireProofSet rejects an unknown id — every id is checked, not just the first" {
        val e = shouldThrow<DomainError.Validation> {
            requireSet(listOf("a", "missing")) { id -> if (id == "missing") null else uploaded(id) }
        }
        e.message shouldBe "a valid proofIds is required"
    }

    "requireProofSet rejects a proof whose purpose belongs to another activity" {
        val e = shouldThrow<DomainError.Validation> {
            requireSet(listOf("a")) { uploaded(it, AttachmentPurpose.SUBSCRIPTION_PROOF) }
        }
        e.message shouldBe "proofIds must reference a deactivation_proof attachment"
    }
})
