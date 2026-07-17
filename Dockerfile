# syntax=docker/dockerfile:1

# ---- Build stage: package the executable fat jar via the Amper (kotlin) wrapper ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY . .
RUN chmod +x kotlin && ./kotlin package

# ---- Run stage: slim JRE with just the jar ----
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/tasks/_ibms-backend_executableJarJvm/ibms-backend-jvm-executable.jar app.jar
EXPOSE 8080
# All configuration comes from environment variables — see .env.example
# (DB_URL, DB_USER, DB_PASSWORD, JWT_SECRET, GOOGLE_OAUTH_CLIENT_ID, STORAGE_LOCAL_DIR, ...).
# Flyway migrations run automatically at startup.
ENTRYPOINT ["java", "-jar", "app.jar"]
