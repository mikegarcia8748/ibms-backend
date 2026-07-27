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
})
