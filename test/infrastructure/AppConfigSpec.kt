package com.puregoldbe.ibms.infrastructure

import com.puregoldbe.ibms.infrastructure.config.AppConfig
import com.puregoldbe.ibms.infrastructure.config.AppEnv
import com.puregoldbe.ibms.infrastructure.config.ConfigException
import com.puregoldbe.ibms.infrastructure.config.CorsOrigin
import com.puregoldbe.ibms.infrastructure.config.EmailDelivery
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/**
 * Unit coverage for environment reading and validation — the layer that used to have
 * none, and where every insecure fallback default lived.
 *
 * Runs with an injected `getenv` rather than the real process environment: Kotest has
 * no `withEnvironment` here, specs share one JVM, and mutating real env vars would be
 * flaky. No container, no Ktor.
 */
class AppConfigSpec : BehaviorSpec({

    fun env(vararg pairs: Pair<String, String>): (String) -> String? = pairs.toMap()::get

    /** A prod environment with every required key satisfied. Vary one key per case. */
    val validProd = mapOf(
        "APP_ENV" to "prod",
        "JWT_SECRET" to "S6uMPUuvHmZaJ0Cn5rFqL9kXbTdWyE2gA4hVnQ8sZ1oB7pR3",
        "DB_PASSWORD" to "a-real-database-password",
        "CORS_ALLOWED_HOSTS" to "client.example.com",
        "APP_URL" to "https://ibms.example.com",
        "WEB_CLIENT_URL" to "https://client.example.com",
        "EMAIL_DELIVERY" to "log",
        "BOOTSTRAP_ADMIN_USERNAME" to "mikepg",
        "BOOTSTRAP_ADMIN_PASSWORD" to "One-Time-Adm1n-Pw",
    )

    fun prod(vararg overrides: Pair<String, String>): (String) -> String? =
        (validProd + overrides).let { m -> { k: String -> m[k] } }

    fun dev(vararg overrides: Pair<String, String>): (String) -> String? =
        (mapOf("APP_ENV" to "dev") + overrides).let { m -> { k: String -> m[k] } }

    Given("an environment with nothing set") {
        When("reading config") {
            Then("it refuses to boot, because APP_ENV defaults to prod") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv { null } }
                ex.message!! shouldContain "APP_ENV=prod"
            }
        }
    }

    Given("APP_ENV=prod and no other variable set") {
        When("reading config") {
            Then("every missing key is reported in a single failure, not one per boot") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(env("APP_ENV" to "prod")) }
                val message = ex.message!!
                listOf(
                    "JWT_SECRET",
                    "DB_PASSWORD",
                    "CORS_ALLOWED_HOSTS",
                    "APP_URL",
                    "WEB_CLIENT_URL",
                    "EMAIL_DELIVERY",
                    "BOOTSTRAP_ADMIN_USERNAME",
                ).forEach { key -> message shouldContain key }
            }
        }
    }

    Given("a prod environment that satisfies every rule") {
        When("reading config") {
            Then("it boots") {
                val cfg = shouldNotThrowAny { AppConfig.fromEnv(prod()) }
                cfg.appEnv shouldBe AppEnv.PROD
                cfg.emailDelivery shouldBe EmailDelivery.LOG
                cfg.smtp shouldBe null
            }
        }
    }

    Given("the weak secrets this repo has actually shipped as placeholders") {
        listOf(
            "dev-secret-change-me",
            "change-me-in-prod-use-a-long-random-string",
            "local-docker-testing-secret-not-for-any-shared-host",
            "test-secret",
        ).forEach { weak ->
            When("JWT_SECRET is \"$weak\" in prod") {
                Then("it is rejected") {
                    val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(prod("JWT_SECRET" to weak)) }
                    ex.message!! shouldContain "JWT_SECRET"
                }
            }
        }

        When("JWT_SECRET is long but random in prod") {
            Then("it is accepted") {
                shouldNotThrowAny { AppConfig.fromEnv(prod()) }
            }
        }
    }

    Given("BOOTSTRAP_ADMIN_PASSWORD unset in dev") {
        When("reading config") {
            Then("it is null, so BootstrapAdmin generates one and logs it once") {
                // The old code defaulted this to a literal, making the generate branch
                // in BootstrapAdmin unreachable and installing Password@123 on a sysadmin.
                AppConfig.fromEnv(dev()).auth.bootstrapAdminPassword shouldBe null
            }
        }
    }

    Given("BOOTSTRAP_ADMIN_PASSWORD unset in prod") {
        When("autogeneration was not asked for") {
            Then("it is a problem rather than a silent default") {
                val bare = validProd - "BOOTSTRAP_ADMIN_PASSWORD"
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv { bare[it] } }
                ex.message!! shouldContain "BOOTSTRAP_ADMIN_PASSWORD"
            }
        }
        When("autogeneration is explicitly requested") {
            Then("it boots with a null password") {
                val m = validProd - "BOOTSTRAP_ADMIN_PASSWORD" +
                    ("BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD" to "true")
                AppConfig.fromEnv { m[it] }.auth.bootstrapAdminPassword shouldBe null
            }
        }
        When("a password is given *and* autogeneration is requested") {
            Then("the contradiction is reported") {
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(prod("BOOTSTRAP_ADMIN_AUTOGENERATE_PASSWORD" to "true"))
                }
                ex.message!! shouldContain "pick one"
            }
        }
    }

    Given("a blank value") {
        When("DB_USER is whitespace in dev") {
            Then("it is treated as unset, matching FOO= in a .env file") {
                AppConfig.fromEnv(dev("DB_USER" to "   ")).db.user shouldBe "ibms"
            }
        }
    }

    Given("out-of-range and unparseable numbers") {
        When("BCRYPT_COST is not a number") {
            Then("the message names the key and the range, not NumberFormatException") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(dev("BCRYPT_COST" to "abc")) }
                ex.message!! shouldContain "BCRYPT_COST"
                ex.message!! shouldContain "4..31"
            }
        }
        When("BCRYPT_COST is below the bcrypt minimum") {
            Then("it is rejected") {
                shouldThrow<ConfigException> { AppConfig.fromEnv(dev("BCRYPT_COST" to "1")) }
            }
        }
        When("BCRYPT_COST is a dev-only cost in prod") {
            Then("it is rejected — hardened environments floor at 10") {
                shouldThrow<ConfigException> { AppConfig.fromEnv(prod("BCRYPT_COST" to "4")) }
            }
        }
        When("JWT_EXPIRES_MINUTES is a year") {
            Then("it is rejected — an access token cannot be revoked") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(dev("JWT_EXPIRES_MINUTES" to "525600")) }
                ex.message!! shouldContain "JWT_EXPIRES_MINUTES"
            }
        }
        When("DB_POOL_SIZE is absurd") {
            Then("it is rejected") {
                shouldThrow<ConfigException> { AppConfig.fromEnv(dev("DB_POOL_SIZE" to "5000")) }
            }
        }
    }

    Given("CORS configuration") {
        When("CORS_ALLOWED_HOSTS is empty in prod") {
            Then("it is rejected rather than falling open to any-host") {
                val m = validProd - "CORS_ALLOWED_HOSTS"
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv { m[it] } }
                ex.message!! shouldContain "CORS_ALLOWED_HOSTS"
            }
        }
        When("CORS_ALLOWED_HOSTS is empty in dev") {
            Then("any-host is allowed, as a local convenience") {
                AppConfig.fromEnv(dev()).corsAllowedHosts shouldBe emptyList()
            }
        }
        // Ktor's allowHost() throws on a host containing "://", so a full origin here
        // used to kill the app during plugin install — after config validation passed.
        When("an entry is written as a full origin") {
            Then("the scheme is split off, and it narrows the entry to that scheme") {
                val cfg = AppConfig.fromEnv(prod("CORS_ALLOWED_HOSTS" to "https://client.example.com"))
                cfg.corsOrigins() shouldBe listOf(CorsOrigin("client.example.com", listOf("https")))
            }
        }
        When("an entry is a bare host") {
            Then("either scheme is allowed") {
                val cfg = AppConfig.fromEnv(prod("CORS_ALLOWED_HOSTS" to "client.example.com:8081"))
                cfg.corsOrigins() shouldBe listOf(CorsOrigin("client.example.com:8081", listOf("http", "https")))
            }
        }
        When("an entry carries a trailing slash or path") {
            Then("only the authority survives — allowHost matches on that alone") {
                val cfg = AppConfig.fromEnv(prod("CORS_ALLOWED_HOSTS" to "https://client.example.com/, other.example.com"))
                cfg.corsOrigins().map { it.host } shouldBe listOf("client.example.com", "other.example.com")
            }
        }
        When("an entry normalises to no host at all") {
            Then("it is reported at boot instead of installing a rule that matches nothing") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(prod("CORS_ALLOWED_HOSTS" to "https://")) }
                ex.message!! shouldContain "CORS_ALLOWED_HOSTS"
            }
        }
    }

    Given("APP_URL in prod") {
        When("it points at localhost") {
            Then("it is rejected — presigned links are built from it") {
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(prod("APP_URL" to "http://localhost:8080"))
                }
                ex.message!! shouldContain "APP_URL"
            }
        }
        When("it is plaintext http") {
            Then("it is rejected") {
                shouldThrow<ConfigException> { AppConfig.fromEnv(prod("APP_URL" to "http://ibms.example.com")) }
            }
        }
    }

    Given("WEB_CLIENT_URL in prod") {
        When("it points at localhost") {
            Then("it is rejected — notification links are built from it") {
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(prod("WEB_CLIENT_URL" to "http://localhost:8081"))
                }
                ex.message!! shouldContain "WEB_CLIENT_URL"
            }
        }
        When("it is plaintext http") {
            Then("it is rejected") {
                shouldThrow<ConfigException> { AppConfig.fromEnv(prod("WEB_CLIENT_URL" to "http://client.example.com")) }
            }
        }
        When("it is unset in dev") {
            Then("it falls back to the local web client, not the API's own port") {
                val cfg = AppConfig.fromEnv(dev())
                cfg.webClientUrl shouldBe "http://localhost:8081"
                cfg.webClientUrl shouldNotBe cfg.appUrl
            }
        }
    }

    Given("CORS_ALLOWED_HOSTS written with a scheme") {
        When("reading config") {
            // Ktor's allowHost rejects a scheme outright, so left unchecked this surfaces
            // as an IllegalArgumentException from inside plugin installation instead.
            Then("it is named as a config problem rather than crashing the boot later") {
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(prod("CORS_ALLOWED_HOSTS" to "https://client.example.com")).requireCoherent()
                }
                ex.message!! shouldContain "CORS_ALLOWED_HOSTS"
                ex.message!! shouldContain "bare host"
            }
        }
    }

    Given("the web client's origin against the CORS allow-list") {
        When("the client's host is admitted") {
            Then("there is nothing to warn about") {
                AppConfig.fromEnv(prod()).webClientCorsWarning() shouldBe null
            }
        }
        When("the CORS list is empty") {
            Then("there is nothing to warn about — dev admits any origin") {
                AppConfig.fromEnv(dev()).webClientCorsWarning() shouldBe null
            }
        }
        When("the default port is written on one side only") {
            Then("they still compare equal") {
                AppConfig.fromEnv(prod("CORS_ALLOWED_HOSTS" to "client.example.com:443"))
                    .webClientCorsWarning() shouldBe null
            }
        }
        When("the client's host is not admitted") {
            Then("it warns, because the landed page could not call this API") {
                val warning = AppConfig.fromEnv(prod("CORS_ALLOWED_HOSTS" to "other.example.com"))
                    .webClientCorsWarning()
                warning!! shouldContain "client.example.com"
                warning shouldContain "CORS_ALLOWED_HOSTS"
            }
        }
    }

    Given("email delivery") {
        When("EMAIL_DELIVERY is unset in prod") {
            Then("there is no default — dropping every notification must be deliberate") {
                val m = validProd - "EMAIL_DELIVERY"
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv { m[it] } }
                ex.message!! shouldContain "EMAIL_DELIVERY"
            }
        }
        When("EMAIL_DELIVERY=smtp but no SMTP_HOST is given") {
            Then("it is rejected") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(prod("EMAIL_DELIVERY" to "smtp")) }
                ex.message!! shouldContain "SMTP_HOST"
            }
        }
        When("EMAIL_DELIVERY=log but a relay is configured") {
            Then("the contradiction is reported instead of silently ignoring the relay") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(prod("SMTP_HOST" to "relay.internal")) }
                ex.message!! shouldContain "SMTP_HOST"
            }
        }
        When("EMAIL_DELIVERY=smtp with a host but no from-address") {
            Then("the missing from-address is collected alongside other problems") {
                // Previously an error() here short-circuited the whole aggregate report.
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(
                        prod(
                            "EMAIL_DELIVERY" to "smtp",
                            "SMTP_HOST" to "relay.internal",
                            "BCRYPT_COST" to "abc",
                        ),
                    )
                }
                ex.problems shouldHaveSize 2
                ex.message!! shouldContain "MAIL_FROM_EMAIL"
                ex.message!! shouldContain "BCRYPT_COST"
            }
        }
        When("SMTP_STARTTLS and SMTP_SSL are both true") {
            Then("the mutually exclusive pair is rejected") {
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(
                        prod(
                            "EMAIL_DELIVERY" to "smtp",
                            "SMTP_HOST" to "relay.internal",
                            "MAIL_FROM_EMAIL" to "ibms@example.com",
                            "SMTP_STARTTLS" to "true",
                            "SMTP_SSL" to "true",
                        ),
                    )
                }
                ex.message!! shouldContain "mutually exclusive"
            }
        }
        When("SMTP_STARTTLS is a typo") {
            Then("it is rejected rather than silently reading as false and downgrading to plaintext") {
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(
                        prod(
                            "EMAIL_DELIVERY" to "smtp",
                            "SMTP_HOST" to "relay.internal",
                            "MAIL_FROM_EMAIL" to "ibms@example.com",
                            "SMTP_STARTTLS" to "yes",
                        ),
                    )
                }
                ex.message!! shouldContain "SMTP_STARTTLS"
            }
        }
        When("a full SMTP configuration is given") {
            Then("it boots with the relay wired up") {
                val cfg = AppConfig.fromEnv(
                    prod(
                        "EMAIL_DELIVERY" to "smtp",
                        "SMTP_HOST" to "relay.internal",
                        "MAIL_FROM_EMAIL" to "ibms@example.com",
                    ),
                )
                cfg.emailDelivery shouldBe EmailDelivery.SMTP
                cfg.smtp!!.host shouldBe "relay.internal"
                cfg.smtp!!.port shouldBe 587
                cfg.smtp!!.startTls shouldBe true
            }
        }
    }

    Given("an unrecognised APP_ENV") {
        When("reading config") {
            Then("the message names the legal values") {
                val ex = shouldThrow<ConfigException> { AppConfig.fromEnv(env("APP_ENV" to "production")) }
                ex.message!! shouldContain "dev, staging, prod"
            }
        }
    }

    Given("the presign signing key") {
        When("PRESIGN_SECRET is unset") {
            Then("it is derived from the JWT secret, never equal to it") {
                val cfg = AppConfig.fromEnv(prod())
                cfg.presignSecret shouldNotBe cfg.jwt.secret
            }
            Then("it is stable for a fixed JWT secret, so restarts don't break live URLs") {
                AppConfig.fromEnv(prod()).presignSecret shouldBe AppConfig.fromEnv(prod()).presignSecret
            }
        }
        When("PRESIGN_SECRET is set explicitly") {
            Then("it is used, decoupling presign rotation from JWT rotation") {
                val cfg = AppConfig.fromEnv(prod("PRESIGN_SECRET" to "an-independent-presign-key"))
                cfg.presignSecret shouldBe "an-independent-presign-key"
            }
        }
    }

    Given("a hand-built config that never went through fromEnv") {
        When("it is a hardened environment with no CORS hosts") {
            Then("requireCoherent still refuses it") {
                val ex = shouldThrow<ConfigException> {
                    AppConfig.fromEnv(prod()).copy(corsAllowedHosts = emptyList()).requireCoherent()
                }
                ex.message!! shouldContain "CORS_ALLOWED_HOSTS"
            }
        }
        When("the presign key equals the JWT secret") {
            Then("requireCoherent refuses it") {
                val cfg = AppConfig.fromEnv(prod())
                val ex = shouldThrow<ConfigException> {
                    cfg.copy(presignSecret = cfg.jwt.secret).requireCoherent()
                }
                ex.message!! shouldContain "PRESIGN_SECRET"
            }
        }
        // The mistake this catches is the natural one: an operator who reads "the public
        // base URL" twice and sets both keys to the same value, pointing every email
        // button at a JSON route that answers a browser with 401.
        When("the web client URL equals the API's own URL") {
            Then("requireCoherent refuses it, trailing slash notwithstanding") {
                val cfg = AppConfig.fromEnv(prod())
                val ex = shouldThrow<ConfigException> {
                    cfg.copy(webClientUrl = cfg.appUrl + "/").requireCoherent()
                }
                ex.message!! shouldContain "WEB_CLIENT_URL"
            }
        }
        When("it is coherent") {
            Then("requireCoherent passes") {
                shouldNotThrowAny { AppConfig.fromEnv(prod()).requireCoherent() }
            }
        }
    }
})
