package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.adapter.controller.*
import com.puregoldbe.ibms.adapter.db.buildDataSource
import com.puregoldbe.ibms.adapter.db.connectExposed
import com.puregoldbe.ibms.adapter.db.migrate
import com.puregoldbe.ibms.adapter.gateway.ExposedTransactionRunner
import com.puregoldbe.ibms.adapter.gateway.LocalDiskStorage
import com.puregoldbe.ibms.adapter.gateway.SimulatedEmailGateway
import com.puregoldbe.ibms.adapter.gateway.SmtpEmailGateway
import com.puregoldbe.ibms.adapter.gateway.SimulatedOcrExtractor
import com.puregoldbe.ibms.adapter.gateway.SimulatedRfpGateway
import com.puregoldbe.ibms.adapter.gateway.SystemClock
import com.puregoldbe.ibms.adapter.repository.*
import com.puregoldbe.ibms.adapter.security.AUTH_SESSION
import com.puregoldbe.ibms.adapter.security.BcryptPasswordHasher
import com.puregoldbe.ibms.adapter.security.JwtService
import com.puregoldbe.ibms.adapter.security.LocalHmacPresign
import com.puregoldbe.ibms.adapter.security.SecureRandomSecrets
import com.puregoldbe.ibms.adapter.security.authorize
import com.puregoldbe.ibms.adapter.security.configureAuthentication
import com.puregoldbe.ibms.application.usecase.*
import com.puregoldbe.ibms.configureMonitoring
import com.puregoldbe.ibms.domain.model.UserRole
import com.puregoldbe.ibms.domain.port.EmailPort
import com.puregoldbe.ibms.infrastructure.config.AppConfig
import com.puregoldbe.ibms.infrastructure.config.EmailDelivery
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * Composition root: loads config, migrates + connects the DB, constructs the
 * adapters and use cases (manual constructor DI), and wires the HTTP routes at the
 * root (matching the API_CONTRACT paths). The only place concrete impls are named.
 */
fun Application.module() = moduleWith(AppConfig.fromEnv())

/**
 * Config-injectable entry point so integration tests can point the app at a
 * Testcontainers DB. Kept a distinct name from [module] so Ktor's application.yaml
 * module loader resolves `BootstrapKt.module` unambiguously (an overload named
 * `module` would make it try to inject the AppConfig parameter and fail to boot).
 */
fun Application.moduleWith(cfg: AppConfig) {
    // Secret strength and the fail-closed rules are enforced in AppConfig.fromEnv();
    // this re-check catches a hand-built config that never went through it.
    cfg.requireCoherent()
    log.info(
        "[config] APP_ENV={} db={} email={} cors={} appUrl={} topsheetRfpFlow={}",
        cfg.appEnv.name.lowercase(),
        cfg.db.url,
        cfg.emailDelivery.name.lowercase(),
        if (cfg.corsAllowedHosts.isEmpty()) "any-host" else cfg.corsAllowedHosts.joinToString(","),
        cfg.appUrl,
        if (cfg.topsheet.rfpFlowEnabled) "enabled" else "disabled (lifecycle ends at compiled)",
    )
    if (cfg.topsheet.rfpFlowEnabled) {
        log.warn(
            "[topsheet] TOPSHEET_RFP_FLOW_ENABLED=true, but the only RfpGateway is SimulatedRfpGateway — " +
                "generate-rfp will mint fabricated RFP numbers and release-to-finance always succeeds.",
        )
    }

    // --- Database (Hikari + Flyway + Exposed) ---
    val dataSource = buildDataSource(cfg.db)
    migrate(dataSource)
    val db = connectExposed(dataSource)
    // Release the connection pool on shutdown. In production this is graceful-shutdown
    // hygiene; in the integration suite it matters more — each `testApplication` boots a
    // fresh app instance, and without this the pools leak and eventually exhaust Postgres
    // ("FATAL: sorry, too many clients already").
    monitor.subscribe(ApplicationStopped) { (dataSource as? AutoCloseable)?.close() }

    // --- Adapters (ports -> implementations) ---
    val tx = ExposedTransactionRunner(db)
    val clock = SystemClock()
    val storage = LocalDiskStorage(cfg.storageLocalDir)
    val jwtService = JwtService(cfg.jwt, cfg.auth.passwordChallengeTtlMinutes.minutes)
    val passwordHasher = BcryptPasswordHasher(cfg.auth.bcryptCost)
    val secrets = SecureRandomSecrets()
    val sessionPolicy = cfg.auth.sessionPolicy()
    val presign = LocalHmacPresign(cfg.presignSecret, cfg.appUrl, clock)
    // OCR extraction and the external RFP system have no HTTP adapter yet; their
    // config keys return alongside the adapters that consume them.
    val ocrGateway = SimulatedOcrExtractor()
    val rfpGateway = SimulatedRfpGateway()
    // Outbound email. Stated by EMAIL_DELIVERY rather than inferred from whether a
    // relay happens to be configured — a prod deploy that merely forgot SMTP_HOST
    // used to drop every notification silently.
    val emailGateway: EmailPort = when (cfg.emailDelivery) {
        // Non-null by requireCoherent(): EMAIL_DELIVERY=smtp implies a relay.
        EmailDelivery.SMTP -> SmtpEmailGateway(cfg.smtp!!)
        EmailDelivery.LOG -> SimulatedEmailGateway().also {
            log.warn("[email] EMAIL_DELIVERY=log — notifications are logged, not sent.")
        }
    }

    val users = ExposedUserRepository()
    val sessions = ExposedSessionRepository()
    val providers = ExposedProviderRepository()
    val sequences = ExposedInvoiceSequenceRepository()
    val batchSequences = ExposedBatchSequenceRepository()
    val attachments = ExposedAttachmentRepository()
    val stores = ExposedStoreRepository()
    val accounts = ExposedAccountRepository()
    val topsheets = ExposedTopSheetRepository()
    val transfers = ExposedTransferRepository()
    val idempotency = ExposedIdempotencyKeyRepository()
    val activities = ExposedActivityRepository()
    val ocrTemplates = ExposedOcrTemplateRepository()
    val ocrBatches = ExposedOcrBatchRepository()
    val changeRequests = ExposedAccountChangeRequestRepository()
    val emailLog = ExposedEmailLogRepository()
    val notificationSubs = ExposedNotificationSubscriptionRepository()
    val notificationRoleDefaults = ExposedNotificationRoleDefaultsRepository()

    // --- Use cases ---
    // Every path that ends in "signed in" mints its tokens through one issuer, so
    // login, first-login password change and refresh cannot drift apart.
    val sessionIssuer = SessionIssuer(sessions, secrets, jwtService, sessionPolicy)
    // Event-driven email: use cases enqueue into email_log inside their transaction;
    // the background dispatcher below drains it through emailGateway.
    val notifications = NotificationService(notificationSubs, emailLog, cfg.appUrl, cfg.smtp?.fromEmail)
    val login = LoginUseCase(users, passwordHasher, jwtService, sessionIssuer, sessionPolicy, clock, tx)
    val completeFirstLogin = CompleteFirstLoginUseCase(users, sessions, passwordHasher, sessionIssuer, clock, tx)
    val changeOwnPassword = ChangeOwnPasswordUseCase(users, sessions, passwordHasher, sessionIssuer, clock, tx)
    val refreshSession = RefreshSessionUseCase(users, sessions, secrets, sessionIssuer, clock, tx)
    val logout = LogoutUseCase(sessions, clock, tx)
    val logoutEverywhere = LogoutEverywhereUseCase(sessions, clock, tx)
    val getCurrentUser = GetCurrentUserUseCase(users, tx)
    val listUsers = ListUsersUseCase(users, tx)
    val provisionUser = ProvisionUserUseCase(users, notificationSubs, notificationRoleDefaults, passwordHasher, secrets, sessionPolicy, clock, tx)
    val resetUserPassword =
        ResetUserPasswordUseCase(users, sessions, passwordHasher, secrets, sessionPolicy, clock, tx)
    val updateUserRole = UpdateUserRoleUseCase(users, tx)
    val updateUserStatus = UpdateUserStatusUseCase(users, tx)
    val listProviders = ListProvidersUseCase(providers, tx)
    val createProvider = CreateProviderUseCase(providers, sequences, batchSequences, tx)
    val updateProvider = UpdateProviderUseCase(providers, tx)
    val deactivateProvider = DeactivateProviderUseCase(providers, clock, tx)
    val listStores = ListStoresUseCase(stores, tx)
    val getStore = GetStoreUseCase(stores, tx)
    val createStore = CreateStoreUseCase(stores, attachments, activities, notifications, tx)
    val updateStore = UpdateStoreUseCase(stores, attachments, tx)
    val closeStore = CloseStoreUseCase(stores, attachments, accounts, clock, tx)
    val getFloating = GetFloatingAccountsUseCase(accounts, tx)
    val listAccounts = ListAccountsUseCase(accounts, tx)
    val getAccount = GetAccountUseCase(accounts, tx)
    val createAccount = CreateAccountUseCase(accounts, providers, stores, activities, attachments, notifications, tx)
    val createISPAccount = CreateISPAccountUseCase(createAccount, providers, attachments, clock, tx)
    val updateAccount = UpdateAccountUseCase(accounts, providers, stores, notifications, tx)
    val transferAccount = TransferAccountUseCase(accounts, stores, transfers, attachments, idempotency, activities, notifications, clock, tx)
    val listTransfers = ListTransfersUseCase(transfers, tx)
    val deactivateAccount = DeactivateAccountUseCase(accounts, attachments, idempotency, activities, notifications, clock, tx)
    val cancelDeactivation = CancelDeactivationUseCase(accounts, activities, tx)
    val bulkImport = BulkImportAccountsUseCase(providers, sequences, batchSequences, stores, accounts, attachments, activities, tx)
    val presignUpload = PresignUploadUseCase(attachments, presign, tx)
    val presignDownload = PresignDownloadUseCase(attachments, presign, tx)
    val listAccountProofs = ListAccountProofsUseCase(accounts, attachments, presign, tx)
    val listTransferProofs = ListTransferProofsUseCase(transfers, accounts, attachments, presign, tx)
    val storeBlob = StoreBlobUseCase(attachments, storage, presign, tx)
    val readBlob = ReadBlobUseCase(attachments, storage, presign, tx)
    val previewCompilation = PreviewCompilationUseCase(accounts, stores, topsheets, clock, tx)
    val listTopSheets = ListTopSheetsUseCase(topsheets, tx)
    val getTopSheet = GetTopSheetUseCase(topsheets, tx)
    val getTopSheetDetails = GetTopSheetDetailsUseCase(topsheets, tx)
    val payTopSheet = PayTopSheetUseCase(topsheets, idempotency, clock, tx)
    val createDraftTopSheet = CreateDraftTopSheetUseCase(accounts, stores, providers, topsheets, idempotency, activities, clock, tx)
    val updateDraftLine = UpdateDraftLineUseCase(topsheets, tx)
    val generateRfp = GenerateRfpUseCase(topsheets, rfpGateway, idempotency, activities, tx)
    val releaseToFinance = ReleaseToFinanceUseCase(topsheets, rfpGateway, activities, notifications, clock, tx)
    val removeDraftLine = RemoveDraftLineUseCase(topsheets, activities, tx)
    val confirmTopSheet = ConfirmTopSheetUseCase(accounts, stores, topsheets, sequences, batchSequences, idempotency, activities, notifications, clock, tx)
    val cancelTopSheet = CancelTopSheetUseCase(topsheets, activities, tx)
    val exportTopSheet = ExportTopSheetExcelUseCase(topsheets, tx)
    val exportAccounts = ExportAccountsExcelUseCase(accounts, providers, tx)
    val exportChequePdf = GenerateChequePaymentPdfUseCase(topsheets, tx)
    val exportChequeCsv = ExportChequePaymentCsvUseCase(topsheets, tx)
    val expireGrace = ExpireGracePeriodAccountsUseCase(accounts, notifications, clock, tx)
    val listActivities = ListActivitiesUseCase(activities, tx)
    val triggerOcr = TriggerOcrExtractionUseCase(ocrBatches, ocrGateway, tx)
    val listOcrBatches = ListOcrBatchesUseCase(ocrBatches, tx)
    val getOcrBatchRows = GetOcrBatchRowsUseCase(ocrBatches, tx)
    val listOcrTemplates = ListOcrTemplatesUseCase(ocrTemplates, tx)
    val createOcrTemplate = CreateOcrTemplateUseCase(ocrTemplates, tx)
    val updateOcrTemplate = UpdateOcrTemplateUseCase(ocrTemplates, tx)
    val submitChangeRequest = SubmitAccountChangeRequestUseCase(changeRequests, accounts, providers, attachments, activities, clock, tx)
    val approveChangeRequest = ApproveAccountChangeRequestUseCase(changeRequests, accounts, providers, activities, notifications, clock, tx)
    val rejectChangeRequest = RejectAccountChangeRequestUseCase(changeRequests, activities, clock, tx)
    val cancelChangeRequest = CancelAccountChangeRequestUseCase(changeRequests, activities, clock, tx)
    val getChangeRequestWithDiff = GetAccountChangeRequestWithDiffUseCase(changeRequests, accounts, tx)
    val listChangeRequests = ListAccountChangeRequestsUseCase(changeRequests, tx)
    val dashboardSummary = GetDashboardSummaryUseCase(accounts, tx)
    val listDashboardAccounts = ListDashboardAccountsUseCase(accounts, tx)
    val listBillingHistory = ListBillingHistoryUseCase(topsheets, tx)
    val updateUserEmail = UpdateUserEmailUseCase(users, tx)
    val getUserNotificationSubscriptions = GetUserNotificationSubscriptionsUseCase(users, notificationSubs, tx)
    val updateUserNotificationSubscriptions = UpdateUserNotificationSubscriptionsUseCase(users, notificationSubs, tx)
    val countDeliverableSubscribers = CountDeliverableSubscribersUseCase(notificationSubs, tx)
    val listUserSubscriptions = ListUserNotificationSubscriptionsUseCase(notificationSubs, tx)
    val bulkUpdateSubscriptions = BulkUpdateNotificationSubscriptionsUseCase(users, notificationSubs, tx)
    val getNotificationRoleDefaults = GetNotificationRoleDefaultsUseCase(notificationRoleDefaults, tx)
    val updateNotificationRoleDefaults = UpdateNotificationRoleDefaultsUseCase(notificationRoleDefaults, tx)
    val dispatchEmails = DispatchQueuedEmailsUseCase(emailLog, emailGateway, clock, tx)

    // --- Cross-cutting plugins ---
    configureStatusPages()
    val metrics = configureMonitoring()
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("Idempotency-Key")
        if (cfg.corsAllowedHosts.isEmpty()) {
            // Redundant with fromEnv() by design: the fail-closed property should be
            // readable at the site that would otherwise fail open.
            check(!cfg.appEnv.isHardened) {
                "CORS_ALLOWED_HOSTS is empty — refusing to allow any host with APP_ENV=${cfg.appEnv.name.lowercase()}"
            }
            this@moduleWith.log.warn("[security] CORS is open to any host (APP_ENV=dev).")
            anyHost()
        } else {
            cfg.corsOrigins().forEach { allowHost(it.host, schemes = it.schemes) }
        }
    }
    configureAuthentication(jwtService)

    // --- First-run credential for the seeded sysadmin (no-op once one exists) ---
    installBootstrapAdminCredentials(cfg.auth, users, passwordHasher, secrets, sessionPolicy, clock, tx)

    // --- Routes (served at root to match the API_CONTRACT paths) ---
    routing {
        publicAuthRoutes(login, completeFirstLogin, refreshSession)
        // Public, token-gated blob transfer — the presigned URL is the credential.
        attachmentBlobRoutes(storeBlob, readBlob)
        authenticate(AUTH_SESSION) {
            securedAuthRoutes(getCurrentUser, changeOwnPassword, logout, logoutEverywhere)
            userRoutes(
                getCurrentUser, listUsers, provisionUser, resetUserPassword, updateUserRole, updateUserStatus,
                updateUserEmail, getUserNotificationSubscriptions, updateUserNotificationSubscriptions,
            )
            notificationAdminRoutes(
                countDeliverableSubscribers, listUserSubscriptions, bulkUpdateSubscriptions,
                getNotificationRoleDefaults, updateNotificationRoleDefaults,
            )
            providerRoutes(listProviders, createProvider, updateProvider, deactivateProvider)
            storeRoutes(listStores, getStore, createStore, updateStore, closeStore, getFloating)
            accountRoutes(listAccounts, getAccount, createAccount, updateAccount, transferAccount, deactivateAccount, cancelDeactivation, bulkImport, createISPAccount, listAccountProofs)
            accountChangeRequestRoutes(submitChangeRequest, approveChangeRequest, rejectChangeRequest, cancelChangeRequest, getChangeRequestWithDiff, listChangeRequests)
            transferRoutes(listTransfers, transferAccount, listTransferProofs)
            activityRoutes(listActivities)
            ocrRoutes(triggerOcr, listOcrBatches, getOcrBatchRows, listOcrTemplates, createOcrTemplate, updateOcrTemplate)
            topSheetRoutes(
                previewCompilation, createDraftTopSheet, updateDraftLine,
                generateRfp, releaseToFinance, removeDraftLine, confirmTopSheet, cancelTopSheet,
                listTopSheets, getTopSheet, getTopSheetDetails, payTopSheet,
                features = cfg.topsheet,
            )
            exportRoutes(exportTopSheet, exportAccounts, exportChequePdf, exportChequeCsv, features = cfg.topsheet)
            dashboardRoutes(dashboardSummary, listDashboardAccounts, listBillingHistory, listStores, exportAccounts)
            attachmentRoutes(presignUpload, presignDownload)
            jobRoutes(expireGrace)
            // Request timings and JVM internals — sysadmin only. Point Prometheus at
            // this with a service account rather than scraping it anonymously.
            get("/metrics-micrometer") {
                call.authorize(UserRole.SYSADMIN)
                call.respond(metrics.scrape())
            }
        }
    }

    // --- Scheduled job: expire termination-grace accounts daily ---
    launch {
        while (isActive) {
            runCatching {
                val n = expireGrace()
                if (n > 0) log.info("[grace-expiry] moved $n account(s) past their 30-day grace to inactive")
            }.onFailure { log.error("[grace-expiry] job failed", it) }
            delay(24.hours)
        }
    }

    // --- Background job: drain the email_log outbox and deliver notifications ---
    launch {
        while (isActive) {
            runCatching {
                val n = dispatchEmails()
                if (n > 0) log.info("[email-dispatch] processed $n queued email(s)")
            }.onFailure { log.error("[email-dispatch] job failed", it) }
            delay(1.minutes)
        }
    }
}
