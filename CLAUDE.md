# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

NKS Bob API is a Kotlin + Ktor backend serving the NKS Bob frontend, a language processing assistant helping NKS advisors answer user questions. The application streams responses from a knowledge base service (KBS) to users through WebSocket and SSE endpoints.

**Main entry point**: `src/main/kotlin/no/nav/nks_ai/Application.kt` - Configures Ktor server and routes

## Build and Development Commands

### Local Development Setup
```bash
# Start database and supporting services
docker-compose up

# Run the server (default port: 8080)
./gradlew run
```

### Testing and Building
```bash
# Run all tests
./gradlew test

# Run only integration tests (uses Testcontainers with real PostgreSQL)
./gradlew test --tests "*IntegrationTest"

# Build the project
./gradlew build

# Build shadow JAR (combined project + dependencies)
./gradlew shadowJar

# Run all checks (includes tests)
./gradlew check

# Clean build artifacts
./gradlew clean
```

### Running Single Tests
```bash
# Run a specific test class
./gradlew test --tests "ClassName"

# Run tests matching a pattern
./gradlew test --tests "*PatternName*"

# Run a specific integration test
./gradlew test --tests "FeedbackIntegrationTest"
```

### Integration Tests

Integration tests use [Testcontainers](https://testcontainers.com/) to test the full stack from HTTP API through service layer to database with a real PostgreSQL instance in Docker.

**Prerequisites**: Docker must be running

**Test Structure**:
- `ApiIntegrationTestBase` - Base class for full-stack API tests with test authentication
- `IntegrationTestBase` - Base class for repository-level tests (legacy)
- Tests are in `src/test/kotlin/no/nav/nks_ai/integration/`

**Key test classes**:
- `FeedbackIntegrationTest` - Feedback API (8 tests)
- `MessageIntegrationTest` - Message operations (13 tests)
- `NotificationIntegrationTest` - Notification API (11 tests)
- `UserConfigIntegrationTest` - User config API (6 tests)

**How it works**:
1. Testcontainers starts a PostgreSQL container (reused across tests)
2. Flyway migrations are applied
3. Each test gets a clean database state
4. Full Ktor application with test authentication is set up
5. Tests make real HTTP requests and verify responses

See `src/test/kotlin/no/nav/nks_ai/integration/README.md` for details.

## Architecture Overview

### Core Module Structure (`src/main/kotlin/no/nav/nks_ai/`)

The codebase follows a domain-driven structure:

- **`app/`** - Infrastructure and configuration
  - `Config.kt` - Application configuration using Config4k (loads from `application.conf`)
  - `plugins/` - Ktor plugins (Security, Databases, Serialization, Monitoring, OpenApi)
  - `bq/` - BigQuery client for analytics
  - `Metrics.kt` - Prometheus metrics
  - `TeamLogger.kt` - Structured logging with TEAM_LOGS marker

- **`auth/`** - Authentication
  - `EntraClient.kt` - Microsoft Entra ID (formerly Azure AD) token management

- **`core/`** - Business domain logic
  - `conversation/` - Conversation management
    - `streaming/` - WebSocket and SSE streaming implementations
  - `message/` - Message CRUD operations
  - `user/` - User configuration
  - `feedback/` - User feedback system
  - `notification/` - Notification system
  - `admin/` - Admin-only operations
  - Service classes follow pattern: `*Service.kt` for business logic, `Db.kt` for data access

- **`kbs/`** - Knowledge Base Service integration
  - `Client.kt` - SSE streaming client to external KBS with retry logic

### Key Architectural Patterns

**Authentication Flow**:
- JWT-based auth with Microsoft Entra ID via JWK provider
- Two auth contexts: default (regular users) and "AdminUser" (requires admin group membership)
- Admin group checked via JWT claims: `Config.jwt.adminGroup`
- Check: `src/main/kotlin/no/nav/nks_ai/app/plugins/Security.kt`

**Message Streaming Flow**:
1. User sends question → `SendMessageService.askQuestion()` or `sendMessageStream()`
2. Service fetches conversation history from DB
3. Creates empty answer message in DB
4. Streams request to KBS via SSE (`KbsClient.sendQuestionStream()`)
5. Processes chunks: status updates, content deltas, errors
6. Emits `ConversationEvent` updates via WebSocket/SSE to client
7. Periodically persists latest message state (every 3 seconds with conflate)
8. Final message persisted when stream completes

**Database Pattern**:
- Uses Exposed ORM with Postgres
- Flyway migrations in `src/main/resources/db/migration/`
- HikariCP connection pooling (15 max, 3 min idle)
- Transactions managed in `*Repo` objects (not shown but pattern is `Db.kt` contains models)
- Configuration: `Databases.kt` initializes DB on startup

**Error Handling**:
- Arrow Core `Either<ApplicationError, T>` for typed errors
- KBS validation errors trigger automatic retry (up to 3 times)
- Errors emitted as `ConversationEvent.ErrorsUpdated` to clients
- Check: `src/main/kotlin/no/nav/nks_ai/app/Error.kt`

**Background Jobs**:
- `ConversationDeletionJob` - Deletes empty conversations older than 30 days
- `UploadStarredMessagesJob` - Uploads starred messages to BigQuery

### Configuration

Configuration loaded via Config4k from `src/main/resources/application.conf`. Key config objects in `Config.kt`:
- `KbsConfig` - External KBS service URL and OAuth scope
- `JwtConfig` - JWT authentication settings
- `DbConfig` - Database connection settings
- `NaisConfig` - NAIS platform settings (for production deployment)
- `BigQueryConfig` - BigQuery dataset/table configuration

## Important Implementation Notes

### When Working with Streaming
- The `SendMessageService` handles two streaming patterns: WebSocket (`askQuestion`) and direct flow (`sendMessageStream`)
- Both use `conflate()` to avoid overwhelming clients with rapid updates
- Always handle both success (`KbsChatResponse`) and error (`KbsErrorResponse`) from KBS
- Status updates (`StatusUpdateResponse`) are separate events

### When Modifying Database Schema
- Create a new Flyway migration file in `src/main/resources/db/migration/`
- Follow naming: `VXX__description.sql` (e.g., `V23__feedback_add_domain.sql`)
- Update corresponding Exposed table definitions in `Db.kt` files

### When Adding New Routes
- Routes are defined in `Api.kt` files within each domain module
- Add route functions to `Application.module()` in `Application.kt`
- Regular user routes go inside `authenticate { }` block
- Admin routes go inside `authenticate("AdminUser") { }` block
- All application routes are under `/api/v1/` prefix

### Team Logging

Team logs are logs that should be visible to the team in production, tagged with the `TEAM_LOGS` marker for easy filtering. They are primarily used for access logging and important business events.

**Setup**:
```kotlin
private val logger = KotlinLogging.logger { }
private val teamLogger = teamLogger(logger)
```

**Access Logging Pattern**:
All admin and sensitive endpoints should log access using this structured format:
```kotlin
teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=<ACTION> resource=<type>/<id>" }
```

**Standard Actions**:
- `READ` - Reading individual resources
- `LIST` - Querying/listing multiple resources
- `CREATE` - Creating new resources
- `UPDATE` - Full update (PUT)
- `PATCH` - Partial update
- `DELETE` - Deleting resources

**Resource Naming**:
- Use singular resource type with ID: `conversation/abc-123`, `feedback/def-456`
- Use path-like format for sub-resources: `conversation/abc-123/messages`
- Use plural for collections: `feedbacks`

**Examples**:
```kotlin
// In routes wrapped with either block
val navIdent = call.navIdent().bind()
teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=READ resource=conversation/${conversationId.value}" }

// In routes without either block (using getNavIdent)
val navIdent = call.getNavIdent()
    ?: return@post call.respondError(ApplicationError.MissingNavIdent())
teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=CREATE resource=notification" }

// For listing endpoints
teamLogger.info { "[ACCESS] user=${navIdent.plaintext.value} action=LIST resource=feedbacks" }
```

**When to Add Access Logs**:
- All admin endpoints (conversation/message access by ID)
- CRUD operations on feedbacks
- CRUD operations on notifications
- Any endpoint accessing user data by ID

### Local Development with Authentication
The `docker-compose.yaml` includes:
- `mock-oauth2-server` - Mock OAuth2 server on port 8899
- `wonderwall` - Authentication proxy on port 3000 (proxies to app on 8080)
- `postgres` - Database on port 5432
- `mockServer` - Mock external services on port 1080
- `bigQuery` - BigQuery emulator on ports 9050/9060

For authenticated requests in local dev, go through Wonderwall at `http://localhost:3000`.

## Deployment

This application is deployed to NAIS (NAV's Kubernetes platform):
- NAIS configs: `.nais/dev-gcp.yaml` and `.nais/prod-gcp.yaml`
- Health endpoints at `/internal` (configured in `Nais.kt`)
- Prometheus metrics at `/metrics`
- OpenAPI documentation at `/swagger-ui`
