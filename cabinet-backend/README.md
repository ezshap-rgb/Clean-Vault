# Shared Cabinet backend

This service is the first step toward one Clean Filing APK that supports separate cabinets for different owners. It runs independently from the existing Apps Script deployment.

## Run locally

Use Node.js 24. In this directory, run `npm ci` and `npm test`. The test uses a mock sign-in verifier, in-memory Firestore substitute, and sample file contents.

For a run against Google Cloud, set `GOOGLE_WEB_CLIENT_ID` to the Web OAuth client ID in the same project as the Android OAuth client. Set `CABINET_BUCKET` to the name of a private Cloud Storage bucket and provide Application Default Credentials for the Firestore project and bucket. Start the service with `npm start`.

## Deploy a test service

Use a Firestore database in Native mode and a private Cloud Storage bucket with public access prevention. Attach a dedicated service account to Cloud Run with Firestore access and object read and write access scoped to that bucket. Enable HTTPS access to Cloud Run for end users. The service checks a Google ID token on every cabinet request. Configure `GOOGLE_WEB_CLIENT_ID` and `CABINET_BUCKET` as Cloud Run environment variables.

Use sample files for the first live test. This service has no Android or browser integration, existing-file migration, or billing yet. Lists currently return at most 100 entries, and file uploads are limited to 16 MiB.

## API

Cabinet requests use `Authorization: Bearer <Google ID token>`. The health check is public.

| Method and path | Request |
| --- | --- |
| `GET /health` | Health check |
| `POST /v1/cabinets` | JSON with `name` |
| `GET /v1/cabinets` | List cabinets for the signed-in account |
| `POST /v1/cabinets/:id/invites` | Owner sends JSON with `email`. The response includes a one-time code valid for seven days. Share the code with the invited person. |
| `POST /v1/cabinets/:id/join` | Invited Gmail or Google Workspace account sends JSON with `code` |
| `GET /v1/cabinets/:id/members` | Owner lists active members |
| `DELETE /v1/cabinets/:id/members/:memberId` | Owner revokes membership and invalidates any pending invite for that member |
| `GET /v1/cabinets/:id/files` | Member lists files |
| `POST /v1/cabinets/:id/files?name=sample.pdf` | Member sends raw file bytes |
| `GET /v1/cabinets/:id/files/:fileId` | Member downloads bytes |
