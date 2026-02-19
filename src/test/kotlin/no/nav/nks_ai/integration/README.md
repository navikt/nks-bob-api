# Integration Tests

This directory contains integration tests that use [Testcontainers](https://testcontainers.com/) to test the full application stack from HTTP API through service layer to database with real PostgreSQL instances running in Docker containers.

## Overview

Integration tests verify the entire request-response flow of the application. These tests start a full Ktor application with test authentication, make real HTTP requests, and verify responses against a real PostgreSQL database. This ensures the complete stack (HTTP → Service → Repository → Database) works correctly together.

## Prerequisites

- Docker must be installed and running on your machine
- Sufficient disk space for Docker images (~100MB for PostgreSQL)
- Network access to pull Docker images (first run only)

## Running Integration Tests

### Run all integration tests:
```bash
./gradlew test --tests "no.nav.nks_ai.integration.*"
```

### Run a specific integration test class:
```bash
./gradlew test --tests "no.nav.nks_ai.integration.UserConfigIntegrationTest"
```

### Run a specific test method:
```bash
./gradlew test --tests "no.nav.nks_ai.integration.UserConfigIntegrationTest.GET config should create config with defaults when it does not exist"
```

## How It Works

### Full-Stack API Tests (Primary)

1. **Testcontainers** automatically pulls and starts a PostgreSQL Docker container
2. **Flyway migrations** are applied to the test database
3. **Each test** gets a clean database state (all data truncated between tests)
4. **Full Ktor application** is started with all plugins and routes configured
5. **Test authentication** bypasses JWT validation using bearer tokens
6. **HTTP client** makes real requests to the test application
7. **Complete flow** is tested: HTTP → Service → Repository → Database
8. **Container is reused** across test classes for performance (singleton pattern)

### Repository Tests (Legacy)

Some tests still use direct repository testing for message operations. These will eventually be migrated to full-stack API tests.

## Colima Compatibility

This project is configured to work with Colima (macOS Docker alternative). The configuration is set in `build.gradle.kts` via environment variables:

```kotlin
tasks.test {
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
    environment("TESTCONTAINERS_CHECKS_DISABLE", "true")
    environment("TESTCONTAINERS_REUSE_ENABLE", "true")
}
```

These settings disable Testcontainers' Ryuk container (which has socket mounting issues with Colima) and enable container reuse for better performance.

## Writing Integration Tests

### Full-Stack API Test Example

```kotlin
class MyApiIntegrationTest : ApiIntegrationTestBase() {

    @Test
    fun `POST should create and return resource`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        val response = client.post("/api/v1/myresources") {
            withTestAuth("Z123456")
            contentType(ContentType.Application.Json)
            setBody(CreateMyResource(name = "test"))
        }

        assertEquals(HttpStatusCode.Created, response.status)
        val created = response.body<MyResource>()
        assertEquals("test", created.name)
    }

    @Test
    fun `GET should retrieve resource by ID`() = testApplication {
        application { testModule() }
        val client = createJsonClient()

        // First create a resource
        val createResponse = client.post("/api/v1/myresources") {
            withTestAuth("Z123456")
            contentType(ContentType.Application.Json)
            setBody(CreateMyResource(name = "test"))
        }
        val created = createResponse.body<MyResource>()

        // Then retrieve it
        val getResponse = client.get("/api/v1/myresources/${created.id}") {
            withTestAuth("Z123456")
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.OK, getResponse.status)
        val retrieved = getResponse.body<MyResource>()
        assertEquals(created.id, retrieved.id)
        assertEquals("test", retrieved.name)
    }
}
```

### Repository Test Example (Legacy)

```kotlin
class MyRepoIntegrationTest : IntegrationTestBase() {

    @Test
    fun `should save and retrieve entity`() = runBlocking {
        val entity = MyEntity(name = "test")
        val saved = MyRepo.save(entity).getOrElse { fail("Expected success") }
        val retrieved = MyRepo.findById(saved.id).getOrElse { fail("Expected success") }
        assertEquals("test", retrieved.name)
    }
}
```

### Key Points

**For API Tests (`ApiIntegrationTestBase`)**:
- **Use `testApplication`**: Ktor testing DSL for full application
- **Call `testModule()`**: Sets up routes, auth, and services
- **Create JSON client**: Use `createJsonClient()` for content negotiation
- **Add authentication**: Use `withTestAuth("navIdent")` or `withTestAdminAuth()`
- **Set content type**: Always add `contentType(ContentType.Application.Json)` before `setBody()`
- **Test complete flow**: HTTP request → routing → service → repository → database
- **Database is clean**: Each test starts with a clean database

**For Repository Tests (`IntegrationTestBase`)**:
- **Use `runBlocking`**: Wrap test bodies for suspend functions
- **Direct repo access**: Call repository methods directly
- **Real database**: Tests use actual PostgreSQL

### Helper Methods

`ApiIntegrationTestBase` provides:
- `createJsonClient()` - HTTP client with JSON serialization
- `withTestAuth(navIdent: String)` - Add test bearer token
- `withTestAdminAuth()` - Add admin bearer token
- `testModule()` - Configure full application for testing

`IntegrationTestBase` provides:
- `getDatabaseUrl()` - Get JDBC URL for debugging
- `getDataSource()` - Get HikariCP DataSource for custom queries
- `setupDatabase()` - Ensure database is initialized

## Performance Considerations

- **First run**: Slower due to Docker image download (~30s)
- **Subsequent runs**: Fast container startup (~2-3s)
- **Container reuse**: Singleton container shared across test classes
- **Data cleanup**: Truncating tables is faster than recreating database

## Troubleshooting

### Docker not running
```
Error: Could not find a valid Docker environment
```
**Solution**: Start Docker Desktop or Docker daemon

### Port already in use
```
Error: Address already in use
```
**Solution**: Testcontainers automatically finds available ports. Check for other PostgreSQL instances.

### Out of disk space
```
Error: No space left on device
```
**Solution**: Clean up Docker images: `docker system prune -a`

### Tests timing out
```
Test did not complete within the timeout
```
**Solution**: Increase test timeout or check if Docker is resource-constrained

## Best Practices

### For API Tests

1. **Test complete user flows**: Test the full HTTP request/response cycle
2. **Use realistic data**: Test with data that matches production scenarios
3. **Test authentication**: Verify access control works correctly
4. **Test error responses**: Verify 400, 403, 404, 500 error handling
5. **Always add `contentType`**: Before `setBody()` to avoid serialization errors
6. **Create test data as needed**: Use API calls or helper functions to set up state
7. **Verify database state**: Important operations should be verified in DB if needed
8. **Test authorization**: Use different navIdents to verify data isolation

### For Repository Tests

1. **Test real scenarios**: Integration tests should test realistic data flows
2. **Keep tests focused**: Each test should verify one specific behavior
3. **Clean test data**: Don't rely on data from other tests
4. **Use transactions**: Wrap complex setups in transactions when possible
5. **Test edge cases**: Verify constraint violations, null handling, etc.

## Comparison with Unit Tests

| Aspect | Unit Tests | API Integration Tests |
|--------|------------|----------------------|
| Speed | Fast (ms) | Slower (seconds) |
| Scope | Single function/class | Full HTTP request flow |
| Database | Mocked/In-memory | Real PostgreSQL |
| Dependencies | Mocked | Real (routes, services, repos) |
| Authentication | Mocked | Test bearer tokens |
| Coverage | Business logic | End-to-end functionality |
| Confidence | Isolated correctness | Complete system works |
| CI/CD | Always run | May run separately |

## CI/CD Considerations

Integration tests can be:
- Run on every commit (if fast enough)
- Run on PR only (to save time)
- Run separately in nightly builds

Configure in your CI pipeline based on test execution time.

## Current Test Classes

### API Integration Tests (Full-Stack)

All tests extend `ApiIntegrationTestBase` and test the complete HTTP → Service → Repository → Database flow:

- **`FeedbackIntegrationTest`** (8 tests) - Feedback CRUD operations
  - Creating feedback on messages
  - Retrieving feedback by ID and message ID
  - Updating feedback (admin)
  - Deleting feedback (admin)
  - Filtering feedbacks by status

- **`NotificationIntegrationTest`** (11 tests) - Notification system
  - Creating notifications (admin)
  - Retrieving all notifications
  - Updating notification status
  - Deleting notifications (admin)
  - User-specific notification endpoints

- **`UserConfigIntegrationTest`** (6 tests) - User configuration
  - GET creates config with defaults
  - PUT updates all fields
  - PATCH updates specific fields
  - User config isolation per navIdent

### Repository Tests (Legacy)

- **`MessageIntegrationTest`** (13 tests) - Direct repository testing for messages
  - Will eventually be migrated to full-stack API tests

**Total: 38 tests, 100% passing**
