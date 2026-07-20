package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.adapter.controller.*
import com.puregoldbe.ibms.adapter.db.buildDataSource
import com.puregoldbe.ibms.adapter.db.connectExposed
import com.puregoldbe.ibms.adapter.db.migrate
import com.puregoldbe.ibms.adapter.gateway.ExposedTransactionRunner
import com.puregoldbe.ibms.adapter.gateway.LocalDiskStorage
import com.puregoldbe.ibms.adapter.gateway.SimulatedOcrExtractor
import com.puregoldbe.ibms.adapter.gateway.SystemClock
import com.puregoldbe.ibms.adapter.repository.*
import com.puregoldbe.ibms.adapter.security.AUTH_SESSION
import com.puregoldbe.ibms.adapter.security.BcryptPasswordHasher
import com.puregoldbe.ibms.adapter.security.JwtService
import com.puregoldbe.ibms.adapter.security.LocalHmacPresign
import com.puregoldbe.ibms.adapter.security.SecureRandomSecrets
import com.puregoldbe.ibms.adapter.security.configureAuthentication
import com.puregoldbe.ibms.application.usecase.*
import com.puregoldbe.ibms.infrastructure.config.AppConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.cors.routing.*
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
    if (cfg.jwt.secret == "dev-secret-change-me") {
        log.warn("[security] JWT_SECRET is the built-in default — set a strong secret before production.")
    }

    // --- Database (Hikari + Flyway + Exposed) ---
    val dataSource = buildDataSource(cfg.db)
    migrate(dataSource)
    val db = connectExposed(dataSource)

    // --- Adapters (ports -> implementations) ---
    val tx = ExposedTransactionRunner(db)
    val clock = SystemClock()
    val storage = LocalDiskStorage(cfg.storageLocalDir)
    val jwtService = JwtService(cfg.jwt, cfg.auth.passwordChallengeTtlMinutes.minutes)
    val passwordHasher = BcryptPasswordHasher(cfg.auth.bcryptCost)
    val secrets = SecureRandomSecrets()
    val sessionPolicy = cfg.auth.sessionPolicy()
    val presign = LocalHmacPresign(cfg.jwt.secret, cfg.appUrl, clock)
    val ocrGateway = SimulatedOcrExtractor()

    val users = ExposedUserRepository()
    val sessions = ExposedSessionRepository()
    val providers = ExposedProviderRepository()
    val sequences = ExposedInvoiceSequenceRepository()
    val attachments = ExposedAttachmentRepository()
    val stores = ExposedStoreRepository()
    val accounts = ExposedAccountRepository()
    val topsheets = ExposedTopSheetRepository()
    val transfers = ExposedTransferRepository()
    val idempotency = ExposedIdempotencyKeyRepository()
    val activities = ExposedActivityRepository()
    val ocrTemplates = ExposedOcrTemplateRepository()
    val ocrBatches = ExposedOcrBatchRepository()

    // --- Use cases ---
    // Every path that ends in "signed in" mints its tokens through one issuer, so
    // login, first-login password change and refresh cannot drift apart.
    val sessionIssuer = SessionIssuer(sessions, secrets, jwtService, sessionPolicy)
    val login = LoginUseCase(users, passwordHasher, jwtService, sessionIssuer, sessionPolicy, clock, tx)
    val completeFirstLogin = CompleteFirstLoginUseCase(users, sessions, passwordHasher, sessionIssuer, clock, tx)
    val changeOwnPassword = ChangeOwnPasswordUseCase(users, sessions, passwordHasher, sessionIssuer, clock, tx)
    val refreshSession = RefreshSessionUseCase(users, sessions, secrets, sessionIssuer, clock, tx)
    val logout = LogoutUseCase(sessions, clock, tx)
    val logoutEverywhere = LogoutEverywhereUseCase(sessions, clock, tx)
    val getCurrentUser = GetCurrentUserUseCase(users, tx)
    val listUsers = ListUsersUseCase(users, tx)
    val provisionUser = ProvisionUserUseCase(users, passwordHasher, secrets, sessionPolicy, clock, tx)
    val resetUserPassword =
        ResetUserPasswordUseCase(users, sessions, passwordHasher, secrets, sessionPolicy, clock, tx)
    val updateUserRole = UpdateUserRoleUseCase(users, tx)
    val listProviders = ListProvidersUseCase(providers, tx)
    val createProvider = CreateProviderUseCase(providers, sequences, tx)
    val updateProvider = UpdateProviderUseCase(providers, tx)
    val deactivateProvider = DeactivateProviderUseCase(providers, clock, tx)
    val listStores = ListStoresUseCase(stores, tx)
    val getStore = GetStoreUseCase(stores, tx)
    val createStore = CreateStoreUseCase(stores, attachments, activities, tx)
    val updateStore = UpdateStoreUseCase(stores, attachments, tx)
    val closeStore = CloseStoreUseCase(stores, attachments, accounts, clock, tx)
    val getFloating = GetFloatingAccountsUseCase(accounts, tx)
    val listAccounts = ListAccountsUseCase(accounts, tx)
    val getAccount = GetAccountUseCase(accounts, tx)
    val createAccount = CreateAccountUseCase(accounts, providers, stores, activities, tx)
    val updateAccount = UpdateAccountUseCase(accounts, providers, stores, tx)
    val transferAccount = TransferAccountUseCase(accounts, stores, transfers, attachments, idempotency, activities, clock, tx)
    val listTransfers = ListTransfersUseCase(transfers, tx)
    val deactivateAccount = DeactivateAccountUseCase(accounts, attachments, activities, clock, tx)
    val presignUpload = PresignUploadUseCase(attachments, presign, tx)
    val presignDownload = PresignDownloadUseCase(attachments, presign, tx)
    val storeBlob = StoreBlobUseCase(attachments, storage, presign, tx)
    val readBlob = ReadBlobUseCase(attachments, storage, presign, tx)
    val previewCompilation = PreviewCompilationUseCase(accounts, stores, topsheets, tx)
    val compileTopSheet = CompileTopSheetUseCase(accounts, stores, providers, topsheets, sequences, idempotency, activities, tx)
    val listTopSheets = ListTopSheetsUseCase(topsheets, tx)
    val getTopSheet = GetTopSheetUseCase(topsheets, tx)
    val getTopSheetDetails = GetTopSheetDetailsUseCase(topsheets, tx)
    val approveTopSheet = ApproveTopSheetUseCase(topsheets, activities, clock, tx)
    val payTopSheet = PayTopSheetUseCase(topsheets, idempotency, clock, tx)
    val exportTopSheet = ExportTopSheetExcelUseCase(topsheets, tx)
    val expireGrace = ExpireGracePeriodAccountsUseCase(accounts, clock, tx)
    val listActivities = ListActivitiesUseCase(activities, tx)
    val triggerOcr = TriggerOcrExtractionUseCase(ocrBatches, ocrGateway, tx)
    val listOcrBatches = ListOcrBatchesUseCase(ocrBatches, tx)
    val getOcrBatchRows = GetOcrBatchRowsUseCase(ocrBatches, tx)
    val listOcrTemplates = ListOcrTemplatesUseCase(ocrTemplates, tx)
    val createOcrTemplate = CreateOcrTemplateUseCase(ocrTemplates, tx)
    val updateOcrTemplate = UpdateOcrTemplateUseCase(ocrTemplates, tx)

    // --- Cross-cutting plugins ---
    configureStatusPages()
    configureAuthentication(jwtService)
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Patch)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        if (cfg.corsAllowedHosts.isEmpty()) {
            anyHost()
        } else {
            cfg.corsAllowedHosts.forEach { allowHost(it, schemes = listOf("http", "https")) }
        }
    }

    // --- First-run credential for the seeded sysadmin (no-op once one exists) ---
    installBootstrapAdminCredentials(cfg.auth, users, passwordHasher, secrets, sessionPolicy, clock, tx)

    // --- Routes (served at root to match the API_CONTRACT paths) ---
    routing {
        publicAuthRoutes(login, completeFirstLogin, refreshSession)
        // Public, token-gated blob transfer — the presigned URL is the credential.
        attachmentBlobRoutes(storeBlob, readBlob)
        authenticate(AUTH_SESSION) {
            securedAuthRoutes(getCurrentUser, changeOwnPassword, logout, logoutEverywhere)
            userRoutes(getCurrentUser, listUsers, provisionUser, resetUserPassword, updateUserRole)
            providerRoutes(listProviders, createProvider, updateProvider, deactivateProvider)
            storeRoutes(listStores, getStore, createStore, updateStore, closeStore, getFloating)
            accountRoutes(listAccounts, getAccount, createAccount, updateAccount, transferAccount, deactivateAccount)
            transferRoutes(listTransfers, transferAccount)
            activityRoutes(listActivities)
            ocrRoutes(triggerOcr, listOcrBatches, getOcrBatchRows, listOcrTemplates, createOcrTemplate, updateOcrTemplate)
            topSheetRoutes(
                previewCompilation, compileTopSheet, listTopSheets, getTopSheet,
                getTopSheetDetails, approveTopSheet, payTopSheet,
            )
            exportRoutes(exportTopSheet)
            attachmentRoutes(presignUpload, presignDownload)
            jobRoutes(expireGrace)
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
}
