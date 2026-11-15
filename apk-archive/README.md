# Clean Filing APK inventory

| Build | File | Shared Cabinet connection | Status |
| --- | --- | --- | --- |
| 1.0.0, code 1 | [CleanFiling.apk](../CleanFiling.apk) | Original Apps Script API with files in Google Drive | Original tracked APK |
| 1.1.0, code 2 | No copy in this inventory | Google sign-in rewrite, with cabinet configuration left blank | Superseded on the Pixel |
| 1.1.1, code 3 | [CleanFiling-1.1.1.apk](CleanFiling-1.1.1.apk) | Authenticated Apps Script API with files in Google Drive | Previous installation on the Pixel |
| 1.1.2, code 4 | [CleanFiling-1.1.2-rollback-35c62be.apk](CleanFiling-1.1.2-rollback-35c62be.apk) | Original Apps Script API with files in Google Drive | Built from `35c62bed87d2d5acee332f66ea4c3a9e62ebf327` with only the version bumped. Installed in place on the Pixel on November 15, 2026. File and two-account testing pending. |

Archived APKs are local files. Their signatures must match the installed app for an in-place update.

The rollback includes Trash and Restore, sync status and retry, and Drive pagination. The live deployment serves the original Shared Cabinet API, verified through its ping endpoint. The user confirmed Trash and Shared Cabinet sync work on the Pixel.

## SHA-256

- 1.0.0 — `3DF47B0E4C7ABE7D7EFF552067D66C609BAF48551C020C0B56995335E20F2071`
- 1.1.1 — `2199075B5C96C89A549C14F3436F882389EC68137CD8E0DDD5A5CEF6615745B4`
- 1.1.2 rollback — `B4E19ED97C1A517F77E18A42C3EC35EF8FFF18CB5515D987196136EE98A67C17`
