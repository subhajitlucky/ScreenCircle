# ScreenCircle Production Hardening Design

**Date:** 2026-09-25  
**Status:** Approved  
**Implementation approach:** Incremental production hardening  
**Branch:** `feat/production-hardening`

## Objective

Turn ScreenCircle from a prototype into a reliable, privacy-conscious Android application while preserving existing users, groups, and historical usage data. Keep the current single-module Kotlin/XML product direction and Material 3 visual identity, but replace unsafe data flows, insecure Firebase access, lifecycle-fragile UI code, and incomplete build/test infrastructure.

## Product Decisions

- Preserve existing Firebase accounts, groups, and usage.
- Use Room as the durable local daily-usage ledger and retry queue.
- Group members may see only display name and screen-time usage, never email.
- New groups use revocable high-entropy invite codes; existing groups receive generated invite codes during migration.
- Missing Usage Access does not block Groups or Settings and is never represented as zero usage.
- Add full production testing: JVM, Firebase Emulator security rules, and Android lifecycle/UI tests where practical.
- Use an incremental expand–migrate–contract migration; never mutate production data automatically.

## Current Architecture Assessment

ScreenCircle is a single-module Android application with:

- Firebase Email/Password authentication.
- Firebase Realtime Database profiles, groups, and usage.
- `UsageStatsManager`/`UsageEvents` for device screen-interactive time.
- WorkManager for nominal six-hour synchronization.
- Room entities and `UsageRepository` that are not used by the active runtime.
- Manual Fragment replacement for Home, Groups, and Settings.
- Callbacks that capture Fragment bindings across asynchronous operations.
- No automated tests, no checked-in Gradle wrapper JAR, and no local `google-services.json`.

The active code has two conflicting usage paths, reports Firebase writes as successful before completion, leaks group listeners, can overwrite leaderboard state from old groups, and calculates each date without carrying active screen sessions across midnight.

## Architecture

### Screen-time pipeline

```text
UsageStatsManager
    -> ScreenTimeDataSource
    -> ScreenTimeCalculator (pure Kotlin)
    -> UsageRepository
    -> Room daily snapshot
    -> UsageSyncRepository
    -> canonical user usage + group usage mirrors
    -> leaderboard
```

### ScreenTimeCalculator

A pure Kotlin component receives normalized screen on/off events plus a date range and clock/zone inputs. It must:

- Carry active screen state across date boundaries.
- Split an interval at local midnight and allocate each segment to the correct date.
- Handle a screen already active at the start of a query.
- Ignore duplicate open events.
- Reject or safely ignore negative/out-of-order intervals.
- Produce deterministic results across timezone and daylight-saving changes.
- Return typed failures instead of converting exceptions to zero.

The Android-facing data source converts `UsageEvents.Event` into a platform-neutral event model so the calculator can be tested without Robolectric or a device.

### UsageRepository

`UsageRepository` becomes the single local usage authority:

1. Read UsageStatsManager events.
2. Calculate today and any recoverable historical dates.
3. Replace today's Room snapshot with the recalculated authoritative value.
4. Preserve historical snapshots if Android no longer returns their events.
5. Mark rows dirty after local updates.
6. Expose Flow-based local reads to the UI.

The current manually incremented Room repository and direct `ScreenTimeRepository` Firebase write are replaced rather than kept as competing sources.

### Room ledger

Room schema v2 stores one daily row per local date:

- `date`
- `totalSeconds`
- `lastLocalUpdateAt`
- `lastSuccessfulSyncAt`
- `syncState`: `PENDING`, `SYNCING`, or `SYNCED`
- `syncAttemptCount`
- optional sanitized failure category

Writes use Room transactions. There is no read-modify-write race because totals are replaced from system-derived calculations rather than manually incremented.

### UsageSyncRepository

A single sync component is used by Home refresh and WorkManager:

- Select dirty snapshots in bounded batches.
- Await canonical Firebase writes.
- Mirror each user's current/historical values into groups where the user is a member.
- Await all required writes before marking a row `SYNCED`.
- Leave rows dirty on transient failure and return a retryable worker result.
- Record permission-required or signed-out conditions separately from network failure.
- Respect a user-controlled cloud-sharing preference.

### UI state and events

ViewModels expose immutable screen state through `StateFlow`. One-time effects such as navigation, snackbars, and dialogs use a separate event channel. No asynchronous callback receives a Fragment binding.

Representative states:

```kotlin
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Content<T>(val value: T) : Loadable<T>
    data class Empty(val message: String) : Loadable<Nothing>
    data class PermissionRequired(val message: String) : Loadable<Nothing>
    data class Error(val message: String, val retryable: Boolean) : Loadable<Nothing>
}
```

Feature-specific state types may specialize this pattern but must distinguish loading, empty, permission-required, error, and valid zero content.

## Firebase Schema v2 and Security

### Schema

```text
users/{uid}/private/{email, createdAt}
users/{uid}/public/{displayName}
users/{uid}/usage/{yyyy-MM-dd}/{seconds, updatedAt}
users/{uid}/groups/{groupId}=true

groups/{groupId}/
  name
  owner
  inviteCodeHash
  createdAt
  members/{uid}/{name, joinedAt, joinedVia}
  usage/{uid}/{yyyy-MM-dd}/{seconds, sourceUpdatedAt}

inviteCodes/{sha256(code)}/
  groupId
  active
  createdAt
```

### Privacy rules

- A user can read/write only their own private profile, compatibility version heartbeat, and canonical usage.
- Anonymous users cannot read application data.
- A non-member cannot read a group, its member names, or its usage.
- A member can read only groups in which their UID is a member.
- A user may add only their own membership and only when `joinedVia` references an active invite whose `groupId` matches the destination group.
- A user cannot alter another member, owner, group name, or existing group usage mirror.
- Only the owner can rename a group, rotate/revoke an invite, transfer ownership, or delete a group.
- A member can write only their own group usage mirror, and the mirror must exactly match the already-committed canonical user usage value and source timestamp.
- Group usage mirrors contain no email or private profile data.
- Canonical daily totals are monotonically non-decreasing for normal client writes; Admin migration is the only normal-path mechanism allowed to correct historical data.
- The owner cannot leave until ownership is transferred or the group is deleted.

### Invite codes

- Generate at least 128 bits of entropy and encode as a user-friendly Base32 string.
- Never persist the plain code in Firebase. Show it once after generation and let the owner copy/share it immediately; a lost code is replaced by rotation.
- Use the lowercase SHA-256 hash as both `groups/{groupId}/inviteCodeHash` and the `inviteCodes` path.
- A joining user hashes the entered code, reads the active invite node, and includes that hash in the member record. The hash is the bearer secret and must not be exposed to non-members.
- Invite codes are revocable and reusable until rotated; no unsupported one-time-use claim is made.
- The UI provides rotate, copy, share, and revoke operations for owners.

### Atomic mutations

Create, join, leave, ownership transfer, and delete operations use one Firebase multipath update per logical operation so related membership, reverse-index, invite, owner, and cleanup records change atomically from the client's perspective. Security rules validate every path in the update. Usage sync is deliberately two-phase: commit and await canonical usage first, then commit mirrors that rules compare to that canonical value; any mirror failure leaves the local ledger dirty for an idempotent retry.

## Existing-Data Migration

Implement a Node.js migration utility using the Firebase Admin SDK. It must support:

```text
npm run firebase:migrate:dry-run
npm run firebase:migrate:apply -- --confirm
npm run firebase:migrate:verify
npm run firebase:migrate:rollback -- --confirm
```

### Migration stages

1. Preflight authentication, project selection, rule compatibility, and expected record counts.
2. Export a timestamped, versioned backup and generate SHA-256 checksums.
3. Resolve identities only by the existing Firebase UID path; never attach historical data by matching email. Quarantine ambiguous or malformed legacy records for manual review.
4. Move profile email from the legacy public profile to `users/{uid}/private/email`.
5. Preserve/create a sanitized public display name.
6. Generate invite codes for every existing group, write only hashes, and deliver plaintext codes through a checksum-protected operator export rather than Firebase.
7. Convert legacy boolean memberships into named member records while tolerating old clients that still write booleans during the compatibility window.
8. Mirror existing canonical usage into every relevant group.
9. Verify user, group, membership, invite, and usage counts and checksums; preserve unknown fields in the backup/archive instead of silently dropping them.
10. Produce a machine-readable report and a tested restore/rollback package.

The migration defaults to dry-run and refuses production apply without both an explicit confirmation flag and verified backup metadata.

### Expand–migrate–contract rollout

1. Deploy expand rules that permit validated additive v2 data and the known legacy shape; keep strict contract rules staged but inactive.
2. Publish a compatibility application release that dual-reads v2/legacy data, normalizes old boolean memberships, writes a compatibility-safe superset, and reports a private per-user version heartbeat.
3. Run migration dry-run and review every quarantined or ambiguous record.
4. Run the migration and verify counts/checksums while monitoring old-client writes.
5. Run an executable Admin readiness assertion that verifies migration status and that every recently active account reports a compatible version; wait for the agreed legacy-client observation window when the assertion fails.
6. Roll out the v2-only application gradually.
7. Monitor auth, canonical/mirror sync, group reads, worker failures, migration verification, and client-version adoption.
8. Switch to strict v2 contract rules only after migration verification and minimum-version gates pass.
9. Remove legacy paths only after the agreed observation window; retain tested rollback until then.

## UI and Accessibility

### Navigation and authentication

- Introduce a Navigation Component graph with Home, Groups, and Settings destinations.
- Preserve tab state and restore the correct selected bottom-navigation item.
- Add an auth guard before authenticated destinations render.
- Redirect session expiry to Login and clear account-scoped local state.

### Usage Access

- Home shows an actionable permission state.
- Groups and Settings remain usable.
- Refresh permission state when returning from system settings.
- Never query/synchronize/display a synthetic zero while permission is unavailable.

### Login

- Login button and progress indicator are mutually exclusive.
- All authentication actions are disabled during an active request.
- Login validates credentials; registration applies password policy and confirmation.
- Raw Firebase exception messages are replaced with stable user-facing messages.
- Password reset uses an enumeration-resistant response.

### Home

- Today and weekly data refresh together.
- Daily Average is calculated across all seven displayed calendar days.
- Valid zero, unavailable data, and loading are visually distinct.
- Weekly chart has a TalkBack-readable textual summary.
- Theme attributes replace hard-coded light-only chart colors.
- Decorative icons are excluded from redundant announcements.

### Groups

- Use an explicit accessible group selector button.
- Model NoGroup, Loading, EmptyMembers, Content, and Error states.
- Disable duplicate create/join submissions and show progress.
- Validate display names and invite/legacy IDs.
- Sort ties deterministically by display name then UID.
- Use 48dp copy controls and a dedicated vector icon.
- Owner controls include invite copy/share/rotate/revoke, transfer, and delete.
- Member leave requires confirmation and updates local state only after Firebase success.

### Settings and consent

- Show a first-use disclosure before Usage Access or cloud sharing.
- Explain screen-on measurement, Firebase storage, group visibility, retention, and deletion.
- Add a global `Share usage with groups` setting.
- Pausing sharing stops remote writes but preserves local history.
- Remove unsupported battery/data claims unless measured and documented.
- Exclude account-scoped preferences and the usage database from backup/device transfer.

### Accessibility baseline

- Minimum 48dp touch targets.
- Correct TalkBack roles, labels, headings, state announcements, and focus order.
- Dynamic Type-safe layouts and no meaning conveyed only by color.
- Complete light/dark theme resources.
- Respect reduced-motion preferences; disable nonessential chart animation when requested.
- Manual verification with TalkBack on at least one physical/emulated device before release.

## Build, Test, and Release

### Pure JVM tests

- Screen interval and midnight splitting.
- Sessions active at query start.
- Duplicate/out-of-order events.
- Timezone and daylight-saving boundaries.
- Typed error and valid-zero behavior.
- Seven-day average.
- Room sync transitions and transaction behavior.
- Structured saved-group serialization.
- Group name and invite validation.

### Firebase Emulator tests

Use multiple authenticated emulator users to verify anonymous denial, private profile isolation, non-member denial, member reads, valid/invalid invite joins, self-only membership writes, owner-only administration, canonical usage ownership, group mirrors, and atomic mutation rejection.

### Android tests

Cover auth guard/session expiry, permission states, Home loading/error/zero/content, create/join/leave during rotation/navigation, navigation restoration, TalkBack semantics, and touch-target constraints.

### Toolchain

- Restore a complete pinned Gradle wrapper.
- Use JDK 17.
- Upgrade to a mutually compatible stable AGP, Kotlin, KSP, AndroidX, Firebase, and Gradle matrix.
- Raise compile/target SDK to at least API 35 and to any higher level required by current Play policy.
- Export Room schemas and test migrations.
- Use a stable, mutually compatible Java time API path for minSdk 26.

### Firebase configuration and secrets

- Keep real `google-services.json` and signing material out of Git.
- Add a sanitized example configuration and validation script.
- Fail release builds with an actionable message when Firebase/signing configuration is absent or mismatched.
- Never print credentials in logs or CI output.

### Release hardening

- Enable R8 and resource shrinking.
- Validate minified release behavior.
- Disable Firebase Analytics by default; require an explicit product/privacy decision to enable it.
- Keep signing configuration outside the repository.
- Generate mapping artifacts and retain them with the release record.

### CI

GitHub Actions runs:

```text
lintDebug
testDebugUnitTest
Firebase Emulator security tests
assembleDebug
assembleRelease
```

Connected Android tests run in a separate emulator-enabled workflow when available.

## Documentation

Update:

- Architecture and data flow.
- Firebase setup and schema v2.
- Migration, verification, and rollback runbook.
- Privacy and data-sharing disclosure.
- Build, test, signing, and release instructions.
- Background-sync semantics and retry behavior.
- Known limitations, including UsageStatsManager retention and WorkManager scheduling.

## Rollout and Rollback

- All work occurs on `feat/production-hardening` in an isolated worktree.
- Each logical change receives tests and an atomic commit.
- No production Firebase mutation, rule deployment, signing, or store submission occurs automatically.
- The migration requires explicit dry-run review and confirmation.
- Before strict-rule enforcement, preserve a verified backup and compatibility release.
- Roll back by redeploying the previous rules/app release and restoring the export only if forward repair is impossible.

## Success Criteria

- Existing accounts, groups, and historical usage remain recoverable through a verified migration.
- No email is visible to another user or stored in group data.
- Non-members cannot read group data under production rules.
- Screen sessions crossing midnight are allocated to the correct local dates.
- Permission, empty, loading, error, and valid zero states are distinct.
- Failed Firebase writes are never reported as successful and are retryable.
- Group listeners are cancellable and cannot publish stale groups.
- Group create/join/leave operations are atomic and lifecycle-safe.
- Home, Groups, and Settings retain correct state through navigation and rotation.
- The project has automated unit, rules, and Android lifecycle coverage for critical paths.
- Debug and minified release builds succeed from a documented clean setup.
- CI runs the required verification suite on every change.

## Non-Goals

- Redesigning the product into a new visual identity.
- Replacing Firebase Authentication or Realtime Database.
- Adding per-app foreground tracking; ScreenCircle remains device screen-on time for this release.
- Building a web or cross-platform client.
- Automatically deploying rules, running production migration, signing, or publishing without explicit approval.
