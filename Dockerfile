# syntax=docker/dockerfile:1

# ---- Build stage: package the executable fat jar via the Gradle wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
# Warm the Gradle dependency cache on its own layer so source edits don't re-download.
COPY gradlew ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --version
COPY settings.gradle.kts build.gradle.kts ./
COPY src src
COPY resources resources
RUN ./gradlew buildFatJar --no-daemon

# ---- Run stage: slim JRE with just the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/ibms-backend-all.jar app.jar
EXPOSE 8080
# All configuration comes from environment variables — see .env.example
# (DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET, BCRYPT_COST, BOOTSTRAP_ADMIN_PASSWORD,
#  STORAGE_LOCAL_DIR, ...).
# Flyway migrations run automatically at startup.
ENTRYPOINT ["java", "-jar", "app.jar"]
