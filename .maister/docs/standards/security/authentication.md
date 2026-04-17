## Authentication

### Auth on All API Endpoints
All Web API endpoints must be authenticated before production. `ApiAuthFilter` validates Bearer/JWT tokens on every request. No endpoint should be accessible without authentication in production mode.

### Webhook HMAC-SHA256 Auth
Webhook endpoints require `X-Hub-Signature-256` header validation with constant-time comparison. `WebhookAuthFilter` implements this pattern to prevent timing attacks.

```java
// Constant-time comparison for HMAC
MessageDigest.isEqual(expectedMac, actualMac);
```
