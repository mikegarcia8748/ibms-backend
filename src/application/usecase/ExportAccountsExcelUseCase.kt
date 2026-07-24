package com.puregoldbe.ibms.application.usecase

import com.puregoldbe.ibms.domain.error.DomainError
import com.puregoldbe.ibms.domain.model.AccountExportRow
import com.puregoldbe.ibms.domain.model.AccountStatus
import com.puregoldbe.ibms.domain.port.AccountRepository
import com.puregoldbe.ibms.domain.port.ProviderRepository
import com.puregoldbe.ibms.domain.port.TransactionRunner
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.ByteArrayOutputStream
import java.math.BigDecimal

/**
 * Server-side Account Excel export (Apache POI). Generates a spreadsheet of
 * accounts filtered by ISP provider and/or account status, with a title,
 * metadata block, column headers, one row per account, and a GRAND TOTAL
 * of all MRC values.
 */
class ExportAccountsExcelUseCase(
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val tx: TransactionRunner,
) {
    data class Export(val fileName: String, val bytes: ByteArray)

    suspend operator fun invoke(providerId: String?, status: AccountStatus?): Export {
        val (rows, providerName) = tx.inTransaction {
            val name = if (providerId != null) {
                providers.findById(providerId)?.name
                    ?: throw DomainError.NotFound("provider $providerId not found")
            } else {
                null
            }
            accounts.listForExport(providerId, status) to name
        }
        return Export(
            fileName = buildFileName(providerName, status),
            bytes = build(rows, providerName, status),
        )
    }

    private fun buildFileName(providerName: String?, status: AccountStatus?): String {
        val prov = providerName?.replace(" ", "_") ?: "all"
        val st = status?.name?.lowercase() ?: "all"
        return "Accounts_${prov}_$st.xlsx"
    }

    private fun build(rows: List<AccountExportRow>, providerName: String?, status: AccountStatus?): ByteArray =
        XSSFWorkbook().use { wb ->
            val sheet = wb.createSheet("Accounts")
            val bold = wb.createCellStyle().apply { setFont(wb.createFont().apply { bold = true }) }
            var r = 0

            sheet.createRow(r++).createCell(0).setCellValue("PUREGOLD PRICE CLUB, INC.")
            r++
            sheet.createRow(r++).createCell(0).setCellValue("Account Export")
            r++

            fun meta(label: String, value: String) {
                val row = sheet.createRow(r++)
                row.createCell(0).apply { setCellValue(label); cellStyle = bold }
                row.createCell(1).setCellValue(value)
            }
            meta("Provider:", providerName ?: "All")
            meta("Status:", status?.name?.lowercase() ?: "All")
            meta("Total Accounts:", rows.size.toString())
            r++

            val headers = listOf(
                "NO.", "STORE CO", "STORE NAME", "PROVIDER", "ACCT#", "CID#",
                "PLAN NAME", "SERVICE TYPE", "SPEED", "MRC",
                "INSTALLATION DATE", "CONTRACT START", "CONTRACT END", "STATUS",
            )
            sheet.createRow(r++).apply {
                headers.forEachIndexed { i, h -> createCell(i).apply { setCellValue(h); cellStyle = bold } }
            }

            rows.forEachIndexed { i, row ->
                sheet.createRow(r++).apply {
                    createCell(0).setCellValue((i + 1).toDouble())
                    createCell(1).setCellValue(row.branchCode)
                    createCell(2).setCellValue(row.storeName)
                    createCell(3).setCellValue(row.providerName)
                    createCell(4).setCellValue(row.accountNumber)
                    createCell(5).setCellValue(row.circuitId ?: "")
                    createCell(6).setCellValue(row.planName ?: "")
                    createCell(7).setCellValue(row.serviceType ?: "")
                    createCell(8).setCellValue(row.speed ?: "")
                    createCell(9).setCellValue(row.rate)
                    createCell(10).setCellValue(row.installationDate.toString())
                    createCell(11).setCellValue(row.contractStartDate?.toString() ?: "")
                    createCell(12).setCellValue(row.contractEndDate?.toString() ?: "")
                    createCell(13).setCellValue(row.status.name.lowercase())
                }
            }

            val total = rows.fold(BigDecimal.ZERO) { acc, row -> acc + BigDecimal(row.rate) }
            sheet.createRow(r++).apply {
                createCell(0).apply { setCellValue("GRAND TOTAL"); cellStyle = bold }
                createCell(9).apply { setCellValue(total.toPlainString()); cellStyle = bold }
            }

            ByteArrayOutputStream().use { out ->
                wb.write(out)
                out.toByteArray()
            }
        }
}
