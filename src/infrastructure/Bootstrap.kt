package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.adapter.controller.*
import com.puregoldbe.ibms.adapter.db.buildDataSource
import com.puregoldbe.ibms.adapter.db.connectExposed
import com.puregoldbe.ibms.adapter.db.migrate
import com.puregoldbe.ibms.adapter.gateway.ExposedTransactionRunner
import com.puregoldbe.ibms.adapter.gateway.GoogleTokenVerifierAdapter
import com.puregoldbe.ibms.adapter.gateway.LocalDiskStorage
import com.puregoldbe.ibms.adapter.gateway.SystemClock
import com.puregoldbe.ibms.adapter.repository.*
import com.puregoldbe.ibms.adapter.security.JwtService
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

/**
 * Composition root: loads config, migrates + connects the DB, constructs the
 * adapters and use cases (manual constructor DI), and wires the HTTP routes under
 * /api/v1. This is the only place the concrete implementations are named.
 */
fun Application.module() {
    val cfg = AppConfig.fromEnv()

    if (cfg.jwt.secret == "dev-secret-change-me") {
        log.warn("[security] JWT_SECRET is the built-in default — set a strong secret before production.")
    }
    if (cfg.devAuthEnabled) {
        log.warn("[security] DEV_AUTH_ENABLED is on — /auth/dev-login mints tokens without Google. Never enable in production.")
    }

    // --- Database (Hikari + Flyway + Exposed) ---
    val dataSource = buildDataSource(cfg.db)
    migrate(dataSource)
    val db = connectExposed(dataSource)

    // --- Adapters (ports -> implementations) ---
    val tx = ExposedTransactionRunner(db)
    val clock = SystemClock()
    val storage = LocalDiskStorage(cfg.storageLocalDir)
    val tokenVerifier = GoogleTokenVerifierAdapter(cfg.googleOauthClientId)
    val jwtService = JwtService(cfg.jwt)

    val users = ExposedUserRepository()
    val providers = ExposedProviderRepository()
    val sequences = ExposedInvoiceSequenceRepository()
    val attachments = ExposedAttachmentRepository()
    val stores = ExposedStoreRepository()
    val accounts = ExposedAccountRepository()
    val topsheets = ExposedTopSheetRepository()
    val transfers = ExposedTransferRepository()

    // --- Use cases ---
    val authGoogle = AuthenticateWithGoogleUseCase(tokenVerifier, users, tx)
    val authDev = AuthenticateDevUseCase(users, tx)
    val getCurrentUser = GetCurrentUserUseCase(users, tx)
    val listUsers = ListUsersUseCase(users, tx)
    val updateUserRole = UpdateUserRoleUseCase(users, tx)
    val listProviders = ListProvidersUseCase(providers, tx)
    val createProvider = CreateProviderUseCase(providers, sequences, tx)
    val deactivateProvider = DeactivateProviderUseCase(providers, clock, tx)
    val listStores = ListStoresUseCase(stores, tx)
    val getStore = GetStoreUseCase(stores, tx)
    val createStore = CreateStoreUseCase(stores, attachments, tx)
    val closeStore = CloseStoreUseCase(stores, attachments, accounts, clock, tx)
    val getFloating = GetFloatingAccountsUseCase(accounts, tx)
    val listAccounts = ListAccountsUseCase(accounts, tx)
    val getAccount = GetAccountUseCase(accounts, tx)
    val createAccount = CreateAccountUseCase(accounts, providers, stores, tx)
    val transferAccount = TransferAccountUseCase(accounts, stores, transfers, attachments, clock, tx)
    val deactivateAccount = DeactivateAccountUseCase(accounts, attachments, clock, tx)
    val uploadAttachment = UploadAttachmentUseCase(attachments, storage, tx)
    val downloadAttachment = DownloadAttachmentUseCase(attachments, storage, tx)
    val previewCompilation = PreviewCompilationUseCase(accounts, stores, topsheets, tx)
    val compileTopSheet = CompileTopSheetUseCase(accounts, stores, providers, topsheets, sequences, tx)
    val listTopSheets = ListTopSheetsUseCase(topsheets, tx)
    val getTopSheet = GetTopSheetUseCase(topsheets, tx)
    val getTopSheetDetails = GetTopSheetDetailsUseCase(topsheets, tx)
    val approveTopSheet = ApproveTopSheetUseCase(topsheets, clock, tx)
    val payTopSheet = PayTopSheetUseCase(topsheets, clock, tx)
    val exportTopSheet = ExportTopSheetExcelUseCase(topsheets, tx)
    val expireGrace = ExpireGracePeriodAccountsUseCase(accounts, clock, tx)

    // --- Cross-cutting plugins ---
    configureStatusPages()
    configureAuthentication(jwtService)
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
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

    // --- Routes ---
    routing {
        route("/api/v1") {
            publicAuthRoutes(authGoogle, authDev, jwtService, cfg.devAuthEnabled)
            authenticate("auth-jwt") {
                securedAuthRoutes(getCurrentUser, jwtService)
                userRoutes(listUsers, updateUserRole)
                providerRoutes(listProviders, createProvider, deactivateProvider)
                storeRoutes(listStores, getStore, createStore, closeStore, getFloating)
                accountRoutes(listAccounts, getAccount, createAccount, transferAccount, deactivateAccount)
                topSheetRoutes(
                    previewCompilation, compileTopSheet, listTopSheets, getTopSheet,
                    getTopSheetDetails, approveTopSheet, payTopSheet, exportTopSheet,
                )
                attachmentRoutes(uploadAttachment, downloadAttachment)
                jobRoutes(expireGrace)
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
}
