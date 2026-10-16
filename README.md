# RaithaMarga Backend

Java 21 / Spring Boot REST API for the [RaithaMarga web app](https://raitha-marga.vercel.app/). The MVP follows the supplied implementation guide: Firebase Authentication, Cloud Firestore, and Render (Docker). It does not implement weigh-proof photos, file uploads, payment settlement, or a separate password/login system.

## What is implemented

- Firebase ID-token verification on every API route except health and the public contact form.
- Farmer, buyer, and manually provisioned admin roles, with authorization bound to the verified Firebase UID.
- Produce listings and buyer requirements, with server-owned farmer/buyer IDs.
- Deterministic crop, unit, district, quantity, price-tolerance (10%), and grade matching with reasons.
- Verified-buyer gating for publishing requirements and opening deals. Buyer verification is a manual MVP operation described below.
- Transactional quantity reservation when a deal is opened, and inventory updates when a deal is completed or cancelled.
- Deal status checks: `INTERESTED -> CONFIRMED -> IN_PROGRESS -> COMPLETED`, with `CANCELLED` or `FAILED` exits before completion.
- Public support form submissions stored in the Firestore `contactMessages` collection. Email/WhatsApp delivery is not configured.
- Admin-only marketplace overview, verification decisions, account status controls, read-only listings/deals, and transactional audit logs.
- Request validation, CORS, consistent JSON errors, health check, Docker image, and focused unit tests.

## Requirements

- JDK 21
- Maven 3.9+
- A Firebase project with Email/Password Authentication enabled, Cloud Firestore created, and a service-account key for the backend
- Docker only if building/running the container locally

Firebase Authentication is the login provider. The frontend signs users in and sends the resulting ID token as `Authorization: Bearer <Firebase-ID-token>`. The API never receives or stores user passwords.

## Configure Firebase locally

1. In Firebase Console, enable **Authentication → Sign-in method → Email/Password** and create a **Cloud Firestore** database.
2. Create a service-account key under **Project settings → Service accounts**. Store it outside this repository; never commit it.
3. Copy `.env.example` to `.env` and set `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, and `FIREBASE_PRIVATE_KEY`. Keep the key's line breaks as `\n` in the environment variable. Alternatively, use Google Application Default Credentials and set `FIREBASE_PROJECT_ID`.
4. Set `CORS_ALLOWED_ORIGINS` to a comma-separated list of exact origins, without trailing slashes. The live site and local Vite origin are allowed by default.

Spring reads the same variables from the process environment; it does not automatically load `.env`. In VS Code, configure these as launch environment variables, or set them in PowerShell before starting the application:

```powershell
$env:FIREBASE_PROJECT_ID = "your-project-id"
$env:FIREBASE_CLIENT_EMAIL = "firebase-adminsdk-xxxxx@your-project-id.iam.gserviceaccount.com"
$env:FIREBASE_PRIVATE_KEY = "-----BEGIN PRIVATE KEY-----`n...`n-----END PRIVATE KEY-----`n"
$env:CORS_ALLOWED_ORIGINS = "https://raitha-marga.vercel.app,http://localhost:5173"
```

## Run and test

```powershell
mvn clean test
mvn spring-boot:run
```

The API listens on `http://localhost:8080` (or the `PORT` environment variable). Health check:

```text
GET http://localhost:8080/api/health
200 {"status":"ok"}
```

Protected routes require a valid Firebase ID token. Example:

```powershell
$headers = @{ Authorization = "Bearer $firebaseIdToken" }
Invoke-RestMethod -Headers $headers http://localhost:8080/api/me
```

## API

All request and response bodies are JSON. Except where marked **public**, send `Authorization: Bearer <Firebase-ID-token>`.

| Method | Path | Access | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/health` | Public | Liveness check for Render |
| `POST` | `/api/contact` | Public | Validate and save the landing-page support form |
| `GET` | `/api/me` | Signed in | Create/read the user's profile and role |
| `POST` | `/api/farmers/profile` | Signed in | Create/update the caller's farmer profile |
| `POST` | `/api/buyers/profile` | Signed in | Create/update the caller's buyer profile |
| `POST` | `/api/listings` | Farmer | Create a produce listing |
| `GET` | `/api/listings?crop=ragi&district=Mysuru` | Signed in | Browse available listings; filters are optional |
| `GET` | `/api/listings/{id}` | Signed in | Read one active listing |
| `POST` | `/api/requirements` | Verified buyer | Publish buyer demand |
| `GET` | `/api/requirements?crop=ragi&district=Mysuru` | Signed in | Browse requirements (buyers see their own; farmers see verified buyers') |
| `GET` | `/api/matches` | Farmer or buyer | Read the caller's current matches and reasons |
| `POST` | `/api/deals` | Verified buyer | Open a deal and reserve listing quantity |
| `GET` | `/api/deals` | Signed in | List deals involving the caller |
| `GET` | `/api/deals/{id}` | Deal participant | Read a deal |
| `PATCH` | `/api/deals/{id}/status` | Deal participant | Advance/cancel/fail a deal through valid transitions |
| `GET` | `/api/admin/overview` | Admin | Read marketplace counts and deal status totals |
| `GET` | `/api/admin/verifications?role=BUYER&status=PENDING` | Admin | List profile verification records (status defaults to `PENDING`) |
| `PATCH` | `/api/admin/verifications/{role}/{uid}` | Admin | Set profile status to `PENDING`, `VERIFIED`, or `REJECTED`; an optional note is limited to 500 characters |
| `GET` | `/api/admin/users?role=FARMER&status=ACTIVE` | Admin | List safe user account fields with optional filters |
| `PATCH` | `/api/admin/users/{uid}/status` | Admin | Set an account to `ACTIVE` or `DISABLED`; admins cannot disable themselves or another admin |
| `GET` | `/api/admin/listings` | Admin | Read the latest 200 listings |
| `GET` | `/api/admin/deals` | Admin | Read the latest 200 deals |
| `GET` | `/api/admin/audit-logs` | Admin | Read the latest 100 account and verification changes |
| `POST` | `/api/admin/farmers` | Admin | Add a farmer on behalf of the team (name + phone, no email/login) and optionally list their first produce. Same phone number updates the existing farmer |
| `GET` | `/api/admin/farmers` | Admin | List team-added farmers with active listing counts |
| `POST` | `/api/admin/farmers/{id}/remove` | Admin | Soft-remove a team-added farmer and take their listings off the marketplace (blocked while they have open deals) |
| `GET` | `/api/public/availability` | Public | Total available quantity and lowest price per crop/unit, for the home page. No farmer details |

Example profile and listing bodies:

```json
POST /api/farmers/profile
{
  "fullName": "Example Farmer",
  "village": "Example Village",
  "taluk": "Example Taluk",
  "district": "Mysuru",
  "state": "Karnataka",
  "landAcres": 1.5
}
```

```json
POST /api/listings
{
  "crop": "Ragi",
  "quantity": 500,
  "unit": "kg",
  "grade": "A",
  "price": 2200,
  "district": "Mysuru",
  "taluk": "Mysuru",
  "village": "Example Village",
  "state": "Karnataka",
  "availabilityDate": "2026-12-15"
}
```

Buyer profile fields are `fullName`, `businessName`, and `district`. Requirement fields are `crop`, `quantity`, `unit`, optional `quality`, `location` (the buyer's district), and `targetPrice`. Deal creation takes `listingId`, `quantity`, and optional `price` (defaults to the listing price). Deal status updates take `{"status":"CONFIRMED"}` (or another valid next status).

Matching requires the same normalized crop, unit, district, sufficient remaining quantity, listing price no more than 10% above the buyer's target, and a grade that meets the requested quality. Scores are deterministic and reflect quantity surplus, price, and grade. Recognized grade rankings are A/premium/1, B/standard/2, and C/basic/3; unrecognized grades must match exactly. Matching does not combine multiple farmers' lots.

### Team-added farmers (WhatsApp)

Farmers who contact the team on WhatsApp do not need an email or a Firebase account. `POST /api/admin/farmers` creates `farmers/managed-<10-digit phone>` (so the phone number is unique) with `managed: true`, `verificationStatus: VERIFIED` and no `users` document. Their listings carry `farmerId` = that id plus `managed: true` and `farmerName`; phone numbers are never included in listing responses. Since these farmers cannot log in, an admin may move their deals through the normal status flow via `PATCH /api/deals/{id}/status`.

### Admin account provisioning and verification

Farmer and buyer profiles start as `PENDING`; deals and published requirements are limited to `VERIFIED` buyers. Create the administrator's Firebase Authentication account and corresponding `users/{firebaseUid}` document manually. Set its `role` to `ADMIN` and `status` to `ACTIVE`; public registration cannot assign the admin role. Admin decisions are checked against this Firestore user document by the backend, and verification/account status changes create `auditLogs` records in the same transaction.

## Firestore collections

The API uses `users/{firebaseUid}`, `farmers/{firebaseUid}`, `buyers/{firebaseUid}`, `listings/{id}`, `requirements/{id}`, `matches/{id}`, `deals/{id}`, `contactMessages/{id}`, and `auditLogs/{id}`. Enable restrictive Firestore rules so browser clients cannot read/write marketplace collections directly; the Admin SDK backend is the trusted data path. Admin screens scan and sort existing MVP collections in memory; no additional composite indexes are required. Add indexed queries/pagination before using this with a large dataset.

## Deploy to Render

1. Create an empty GitHub repository. If this project folder is not already a Git repository, run the following from the backend folder in PowerShell (replace the remote URL):

   ```powershell
   Set-Location C:\Users\91636\Desktop\RaithaMarga-backend
   git init -b main
   git add .gitignore .env.example Dockerfile pom.xml README.md LICENSE src
   git commit -m "Build RaithaMarga backend"
   git remote add origin https://github.com/<YOUR-ACCOUNT>/<YOUR-REPOSITORY>.git
   git push -u origin main
   ```

   The explicit `git add` list avoids uploading the supplied PDF or any local secret files. Do not stage `.env` or a Firebase service-account JSON file.
2. In Render, choose **New → Web Service**, connect the repository, and select **Docker** as the runtime.
3. Set the health-check path to `/api/health`.
4. Add Render environment variables/secrets:
   - `FIREBASE_PROJECT_ID`
   - `FIREBASE_CLIENT_EMAIL`
   - `FIREBASE_PRIVATE_KEY` (paste the full private key; keep its newline characters)
   - `CORS_ALLOWED_ORIGINS=https://raitha-marga.vercel.app,http://localhost:5173`
   - Render supplies `PORT`; the app listens on it.
5. Deploy and open `https://<your-service>.onrender.com/api/health`. Expect `{"status":"ok"}`.

The included multi-stage Dockerfile compiles with Maven and runs the Spring Boot JAR on Java 21. Render's free service may sleep when idle, so its first request can be slow; free instances are for demos/testing, not production availability.

## Connect the Vercel frontend

1. In the Vercel project for `raitha-marga.vercel.app`, add `VITE_API_BASE_URL=https://<your-service>.onrender.com` to the required deployment environments.
2. Ensure the frontend sends its Firebase ID token in the `Authorization` header on protected requests.
3. Ensure the login/profile/dashboard screens call the endpoints above. The deployed landing page's contact form should `POST` to `${VITE_API_BASE_URL}/api/contact`; role-specific CTAs need the corresponding frontend routes.
4. Redeploy the frontend, then test one farmer profile/listing and one manually verified buyer requirement/match/deal end-to-end.
5. If you use a Vercel preview URL or custom domain, add its exact origin to Render's `CORS_ALLOWED_ORIGINS` and redeploy the backend.

The backend implementation does not modify the separately deployed React/Vercel project. The landing-page routes and API wiring must exist in that frontend repository for the full user journey to work.

## Production checklist

- Store Firebase secrets only in Render environment settings and rotate any key that is exposed.
- Use production Firestore rules and least-privilege project access.
- Restrict CORS to the production frontend origin(s).
- Configure monitoring, backups, request rate limits, and pagination before launch.
- Handle support-message triage: `/api/contact` stores submissions but does not send email or WhatsApp notifications.
