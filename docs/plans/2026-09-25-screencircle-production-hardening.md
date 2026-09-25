# ScreenCircle Production Hardening Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Transform ScreenCircle into a privacy-safe, offline-recoverable, testable Android application while preserving existing Firebase users, groups, and usage data.

**Architecture:** Keep the single-module Android application, but introduce a pure Kotlin screen-time calculator, one Room-backed usage ledger, one awaited Firebase sync path, strict Firebase schema v2, lifecycle-safe `StateFlow` UI, and a Navigation Component graph. Use an expand–migrate–contract rollout for existing production data.

**Tech Stack:** Kotlin 2.0.21, Android Gradle Plugin 8.7.3, Gradle 8.9, JDK 17, Android SDK 35, Room 2.7.0, WorkManager 2.10.0, Firebase Auth/Realtime Database, Navigation 2.8.5, Kotlin Coroutines/Flow, JUnit 4, Robolectric, AndroidX Test, Firebase Emulator Suite, Node.js 24.

---

## Execution Context

- Worktree: `/home/subhajit/project/ScreenCircle-production-hardening`
- Branch: `feat/production-hardening`
- Base commit: `ec55fd0`
- Approved design: `docs/plans/2026-09-25-screencircle-production-hardening-design.md`
- Use Space Bunny (`openrouter/stealth/space-bunny-alpha`) for every implementation or review agent.
- Do not modify `main` directly.
- Do not access or mutate production Firebase unless the user gives a separate explicit instruction.
- Never commit `google-services.json`, signing keys, passwords, service-account JSON, or emulator data.
- Use TDD for business logic and bug fixes.
- Run `/reload` is not applicable inside repository worktrees; restart Pi or use explicit subagents as needed.
- Commit after every task when its verification passes.

## Target File Structure

New or substantially redesigned files will follow this structure while existing package names remain where practical:

```text
app/src/main/java/com/example/screencircle/
├── ScreenCircleApplication.kt
├── di/AppContainer.kt
├── domain/model/ScreenTimeModels.kt
├── domain/time/ScreenTimeCalculator.kt
├── data/screentime/UsageStatsDataSource.kt
├── data/screentime/AndroidUsageStatsDataSource.kt
├── data/local/AppDatabase.kt
├── data/local/DailyUsage.kt
├── data/local/UsageDao.kt
├── data/firebase/GroupRemoteDataSource.kt
├── data/firebase/FirebaseGroupRemoteDataSource.kt
├── data/preferences/AppPreferences.kt
├── data/preferences/GroupPreferencesCodec.kt
├── data/repository/UsageRepository.kt
├── data/repository/UsageSyncRepository.kt
├── data/repository/GroupRepository.kt
├── ui/common/Loadable.kt
├── ui/common/ViewModelEvent.kt
├── ui/consent/PrivacyConsentController.kt
├── ui/dashboard/*
├── ui/home/*
├── ui/login/LoginActivity.kt
├── ui/main/MainActivity.kt
├── ui/settings/SettingsViewModel.kt
├── ui/settings/SettingsFragment.kt
└── worker/UsageSyncWorker.kt

app/src/test/java/com/example/screencircle/
├── domain/time/ScreenTimeCalculatorTest.kt
├── data/preferences/GroupPreferencesCodecTest.kt
├── data/repository/UsageRepositoryTest.kt
├── data/repository/GroupRepositoryTest.kt
└── ui/*/*Test.kt

app/src/androidTest/java/com/example/screencircle/
├── data/local/AppDatabaseMigrationTest.kt
├── ui/MainNavigationTest.kt
└── ui/AccessibilityTest.kt

firebase/
├── firebase.expand.json
├── firebase.contract.json
├── database.rules.expand.json
├── database.rules.contract.json
├── package.json
├── package-lock.json
└── tests/database.rules.test.mjs

scripts/firebase/
├── migrate-v2.mjs
├── migration-lib.mjs
└── migration-lib.test.mjs

.github/workflows/
└── android-ci.yml
```

---

## Hardened Data and Acceptance Contracts

These contracts are implementation requirements, not optional suggestions.

### Firebase v2 exact schema

```text
users/{uid}/private/email: string
users/{uid}/private/createdAt: server timestamp
users/{uid}/public/displayName: non-empty string, 1..80 characters
users/{uid}/usage/{yyyy-MM-dd}/seconds: integer, 0..86400
users/{uid}/usage/{yyyy-MM-dd}/sourceUpdatedAt: non-negative integer
users/{uid}/usage/{yyyy-MM-dd}/updatedAt: Firebase server timestamp
users/{uid}/groups/{groupId}: true
users/{uid}/client/version: semantic application version/build code
users/{uid}/client/lastSeenAt: server timestamp

groups/{groupId}/name: non-empty string, 1..80 characters
groups/{groupId}/owner: uid
groups/{groupId}/inviteCodeHash: lowercase SHA-256 hex
groups/{groupId}/createdAt: server timestamp
groups/{groupId}/members/{uid}/name: non-empty string, 1..80 characters
groups/{groupId}/members/{uid}/joinedAt: server timestamp
groups/{groupId}/members/{uid}/joinedVia: active invite hash
groups/{groupId}/usage/{uid}/{yyyy-MM-dd}/seconds: integer, 0..86400
groups/{groupId}/usage/{uid}/{yyyy-MM-dd}/sourceUpdatedAt: non-negative integer

inviteCodes/{sha256(code)}/groupId: group ID
inviteCodes/{sha256(code)}/active: boolean
inviteCodes/{sha256(code)}/createdAt: server timestamp
inviteCodes/{sha256(code)}/rotatedAt: server timestamp when replaced
```

The plaintext invite code is never stored in Firebase. It is displayed once after generation/export and can be replaced by rotation. The SHA-256 hash is the bearer secret and is readable only as the specific invite node needed for authenticated redemption; group hashes are readable by group members.

### Compatibility shapes

The compatibility release must normalize both legacy and v2 values:

- Legacy `groups/{groupId}/members/{uid}=true` and v2 member objects are both accepted while expand rules are active.
- Legacy numeric usage and v2 usage objects are both accepted by the compatibility reader.
- New writes use v2 objects, but expand rules continue allowing the known legacy join shape until the minimum-client gate is met.
- Contract rules reject legacy writes and unknown fields after migration verification.

### Executable contract gate

The compatibility application writes `users/{uid}/client/version` and `users/{uid}/client/lastSeenAt`. `scripts/firebase/assert-contract-ready.mjs` uses Admin access to compare every recently active canonical account against a required compatibility version and also requires a successful migration verification manifest. It exits non-zero and reports aggregate/quarantined counts when any active account is missing or below the minimum.

`scripts/firebase/deploy-contract.sh` is the only supported contract deployment command. It must run the readiness assertion and reject deployment on any missing account, failed migration verification, wrong project, or stale backup. CI tests passing and failing fixtures. The rollout runbook records the active window and minimum version; prose alone is not a deployment gate.

### Invite redemption protocol

1. Owner generates a 128-bit-or-stronger code and SHA-256 hash on-device.
2. The client atomically creates the group, owner member record, reverse index, invite node, and group invite hash.
3. A joiner hashes the entered code and reads `inviteCodes/{hash}`.
4. The joiner verifies `active == true` and the returned `groupId` matches the intended destination.
5. One root update adds only `members/{auth.uid}` and `users/{auth.uid}/groups/{groupId}`.
6. Rules verify `joinedVia` equals an active invite hash whose `groupId` is the destination group.
7. Rotation atomically creates the new hash and removes the old hash. Old hashes then fail redemption.
8. Plaintext codes are shown once; Firebase never receives or stores them.

### Usage and mirror protocol

1. Room remains the offline source of truth and survives process death.
2. The client commits canonical usage for one owner/date and awaits completion.
3. Normal client writes cannot lower an existing canonical total; Admin migration is the controlled correction path.
4. The client then writes group mirrors in a second awaited operation.
5. Contract rules require each mirror's `seconds` and `sourceUpdatedAt` to equal the already-committed canonical values.
6. Any mirror failure leaves the Room row dirty. Retry rewrites the same canonical value and mirrors idempotently.
7. Dirty selection and state transitions are owner-scoped and compare-and-set guarded by a monotonically increasing Room `revision`, so two updates in the same millisecond cannot collide and a refresh during sync cannot mark newer data as synchronized.
8. Last-write conflicts use server `updatedAt` plus non-decreasing `sourceUpdatedAt`; tests cover duplicate, stale, and out-of-order writes.

### Consent, pause, and deletion semantics

- Sharing defaults to off until consent version 1 is accepted.
- Consent and sharing preferences are scoped by UID and never carry to another account.
- Withdrawal disables future uploads immediately but retains pending local rows; re-enabling may upload those retained rows, which the disclosure states explicitly.
- Pause blocks both foreground and WorkManager remote writes without deleting local history.
- “Delete local history” removes that UID's Room rows and cached group/profile preferences after confirmation.
- “Delete account” requires reauthentication, requires the user to transfer/delete any owned groups, removes group memberships/usage mirrors and canonical/private/public data, then deletes the Firebase Auth account. Each remote phase is idempotent and verified before auth deletion.
- Logout clears account-scoped preferences and stops the worker from uploading the previous UID's rows.

### Required acceptance journeys

- Legacy account: sign in, recover Firebase history without Usage Access, verify groups/names/invites/mirrors, restart offline, then sync pending data.
- Offline restart: process death with dirty rows retains them and the next allowed sync uploads only that UID.
- Permission: open Settings, grant, resume, refresh; revoke while running, return, and show PermissionRequired rather than zero.
- Navigation: no authenticated destination renders signed out; logout cannot be undone with Back; each tab restores after rotation/process recreation.
- Consent: decline/withdraw pauses remote writes, account switching isolates consent, and re-enable behavior matches the disclosure.
- Migration: dry-run, apply, interrupted apply/retry, verify, old-client compatibility window, contract enforcement, and rollback restore all pass against Emulator fixtures.
- Accessibility: API 26 and API 35, font scales 1.0/1.3/2.0, keyboard/D-pad, reduced motion, and TalkBack smoke tests pass; interactive targets are at least 48dp and normal-text contrast is at least 4.5:1.

---

### Task 1: Restore a Reproducible Android Build

**Files:**
- Create: `.tool-versions`
- Create: `scripts/bootstrap-local-toolchain.sh`
- Create: `gradle/libs.versions.toml`
- Create: `app/google-services.json.example`
- Modify: `.gitignore`
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `gradle.properties`
- Regenerate: `gradlew`
- Regenerate: `gradlew.bat`
- Create: `gradle/wrapper/gradle-wrapper.jar`

**Step 1: Record the failing baseline**

Run:

```bash
./gradlew --version
```

Expected before the fix: failure because Java is unavailable; after Java bootstrap, failure because `gradle/wrapper/gradle-wrapper.jar` is missing.

**Step 2: Add ignored local tool locations**

Extend `.gitignore` with:

```gitignore
# Local toolchains and secrets
.tools/
local.properties
google-services.json
keystore.properties
*.jks
*.keystore
firebase-debug.log
firebase-database*.json
```

**Step 3: Create a user-local bootstrap script**

`scripts/bootstrap-local-toolchain.sh` must:

1. Create `.tools/`.
2. Download a Temurin JDK 17 archive using the Adoptium API.
3. Download Gradle 8.9.
4. Download Android command-line tools.
5. Install `platforms;android-35`, `build-tools;35.0.0`, and `platform-tools`.
6. Write `local.properties` with the local SDK path.
7. Run Gradle's `wrapper --gradle-version 8.9` task to generate a complete wrapper.
8. Print `JAVA_HOME`, SDK, and wrapper paths without printing secrets.

The script must fail clearly if an archive checksum is unavailable and must use `curl --fail --location`.

**Step 4: Add a version catalog**

Create `gradle/libs.versions.toml` with pinned versions:

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
androidxCore = "1.15.0"
appcompat = "1.7.0"
material = "1.12.0"
constraintlayout = "2.2.0"
recyclerview = "1.3.2"
lifecycle = "2.8.7"
activity = "1.10.0"
fragment = "1.8.5"
navigation = "2.8.5"
room = "2.7.0"
work = "2.10.0"
coroutines = "1.9.0"
firebaseBom = "33.7.0"
mpandroidchart = "3.1.0"
junit = "4.13.2"
robolectric = "4.14.1"
androidxTestCore = "1.6.1"
androidxTestRunner = "1.6.2"
androidxTestRules = "1.6.1"
androidxTestExtJunit = "1.2.1"
espresso = "3.6.1"
```

**Step 5: Update Gradle configuration**

Apply the following stable matrix:

- AGP `8.7.3`
- Gradle `8.9`
- Kotlin `2.0.21`
- KSP `2.0.21-1.0.28`
- Java/JVM target 17
- `compileSdk = 35`
- `targetSdk = 35`
- `minSdk = 26`

Enable:

```kotlin
android.buildFeatures.buildConfig = true
android.buildFeatures.viewBinding = true
android.defaultConfig.testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

Keep MPAndroidChart and add RecyclerView explicitly to the catalog. Remove unused Glide and Firebase Analytics dependencies rather than carrying undisclosed collection. Add explicit Room, WorkManager, Navigation testing, coroutine-test, and coroutine-play-services dependencies.

**Step 6: Add a sanitized Firebase configuration example**

Create `app/google-services.json.example` with:

- package name `com.example.screencircle`
- a fake project ID
- a non-secret placeholder API key
- no production project references

Document copying it to ignored `app/google-services.json` for compile-only local verification.

**Step 7: Bootstrap and verify the build**

Run:

```bash
./scripts/bootstrap-local-toolchain.sh
cp app/google-services.json.example app/google-services.json
./gradlew --version
./gradlew help testDebugUnitTest assembleDebug
```

Expected: Gradle 8.9/JDK 17, BUILD SUCCESSFUL, and ignored secrets remain unstaged.

**Step 8: Commit**

```bash
git add .gitignore .tool-versions scripts/bootstrap-local-toolchain.sh gradle/libs.versions.toml gradlew gradlew.bat gradle/wrapper app/build.gradle.kts build.gradle.kts settings.gradle.kts gradle.properties app/google-services.json.example
git commit -m "build: restore reproducible Android toolchain"
```

---

### Task 2: Build the Pure Screen-Time Calculator

**Files:**
- Create: `app/src/main/java/com/example/screencircle/domain/model/ScreenTimeModels.kt`
- Create: `app/src/main/java/com/example/screencircle/domain/time/ScreenTimeCalculator.kt`
- Create: `app/src/test/java/com/example/screencircle/domain/time/ScreenTimeCalculatorTest.kt`

**Step 1: Write failing boundary tests**

Cover these exact behaviors:

```kotlin
@Test
fun `session crossing midnight is split between dates`()
@Test
fun `session already active at range start is included`()
@Test
fun `duplicate interactive events do not restart session`()
@Test
fun `negative interval is ignored and reported`()
@Test
fun `events are sorted before calculation`()
@Test
fun `zero duration is a valid result`()
@Test
fun `daylight saving transition uses local date boundaries`()
```

Use `ZoneId.of("Asia/Kolkata")` for most tests and `America/New_York` for DST.

**Step 2: Run tests and confirm RED**

```bash
./gradlew testDebugUnitTest --tests '*ScreenTimeCalculatorTest'
```

Expected: compilation/test failure because calculator/model types do not exist.

**Step 3: Define platform-neutral models**

Implement:

```kotlin
enum class ScreenInteractionState { INTERACTIVE, NON_INTERACTIVE }

data class ScreenTimeEvent(
    val timestampMillis: Long,
    val state: ScreenInteractionState,
)

data class ScreenTimeRange(
    val startInclusive: Long,
    val endExclusive: Long,
)

data class ScreenTimeAnomaly(
    val eventTimestampMillis: Long,
    val reason: String,
)

data class ScreenTimeCalculation(
    val totalsByDate: Map<LocalDate, Long>,
    val anomalies: List<ScreenTimeAnomaly>,
)
```

**Step 4: Implement the calculator**

Requirements:

- Sort events by timestamp while preserving duplicate events.
- Seed state from the latest event before the requested range when present.
- Clip active intervals to the requested range.
- Split intervals at each local-date boundary.
- Convert milliseconds to seconds after summing exact millisecond intervals.
- Never return negative totals.
- Return valid zero with no anomalies for a real no-usage period.

**Step 5: Run tests and confirm GREEN**

```bash
./gradlew testDebugUnitTest --tests '*ScreenTimeCalculatorTest'
```

Expected: all calculator tests pass.

**Step 6: Commit**

```bash
git add app/src/main/java/com/example/screencircle/domain app/src/test/java/com/example/screencircle/domain
git commit -m "fix: calculate screen time across date boundaries"
```

---

### Task 3: Add Typed Android Usage Data and Permission Results

**Files:**
- Create: `app/src/main/java/com/example/screencircle/data/screentime/UsageStatsDataSource.kt`
- Create: `app/src/main/java/com/example/screencircle/data/screentime/AndroidUsageStatsDataSource.kt`
- Create: `app/src/test/java/com/example/screencircle/data/screentime/AndroidUsageStatsDataSourceTest.kt`
- Create: test seams for injectable `AppOpsGateway` and `UsageEventGateway`
- Modify: `app/src/main/java/com/example/screencircle/data/repository/ScreenTimeRepository.kt`

**Step 1: Write failing tests**

Test:

- Granted AppOps with events returns `Success`.
- Denied AppOps returns `PermissionRequired` without querying usage events.
- Empty event stream returns a successful empty calculation, not permission failure.
- Query exception returns `Failure` and never zero-as-error.

**Step 2: Run RED**

```bash
./gradlew testDebugUnitTest --tests '*AndroidUsageStatsDataSourceTest'
```

**Step 3: Define typed results**

```kotlin
sealed interface ScreenTimeDataResult {
    data class Success(val events: List<ScreenTimeEvent>) : ScreenTimeDataResult
    data object PermissionRequired : ScreenTimeDataResult
    data class Failure(val message: String, val cause: Throwable? = null) : ScreenTimeDataResult
}
```

**Step 4: Implement `AndroidUsageStatsDataSource`**

- Check `AppOpsManager.OPSTR_GET_USAGE_STATS` through an injected `AppOpsGateway` so tests can prove denied access never invokes the event gateway.
- Query events from a documented 30-day lookback before the requested start so active state can be seeded for normal devices. If Android has no retained prior event, assume OFF and record that history may be unavailable rather than fabricating usage.
- Normalize `SCREEN_INTERACTIVE` and `SCREEN_NON_INTERACTIVE` events.
- Catch platform/query exceptions into `Failure`.
- Never infer permission from an empty event list.

**Step 5: Refactor `ScreenTimeRepository`**

Temporarily keep the public repository name but return:

```kotlin
sealed interface ScreenTimeResult {
    data class Success(val seconds: Long) : ScreenTimeResult
    data object PermissionRequired : ScreenTimeResult
    data class Failure(val message: String) : ScreenTimeResult
}
```

Remove the `hasUsageStatsPermission()` query heuristic.

**Step 6: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*AndroidUsageStatsDataSourceTest' --tests '*ScreenTimeCalculatorTest'
git add app/src/main/java/com/example/screencircle/data app/src/test/java/com/example/screencircle/data
git commit -m "refactor: model usage access and query failures explicitly"
```

---

### Task 4: Upgrade Room to an Account-Scoped Sync Ledger

**Files:**
- Modify: `app/src/main/java/com/example/screencircle/data/local/DailyUsage.kt`
- Modify: `app/src/main/java/com/example/screencircle/data/local/UsageDao.kt`
- Modify: `app/src/main/java/com/example/screencircle/data/local/AppDatabase.kt`
- Create: `app/src/main/java/com/example/screencircle/data/local/SyncState.kt`
- Create: `app/src/main/java/com/example/screencircle/data/local/Migrations.kt`
- Create: `app/src/androidTest/java/com/example/screencircle/data/local/AppDatabaseMigrationTest.kt`
- Create: `app/schemas/com.example.screencircle.data.local.AppDatabase/1.json` (reconstructed checked-in v1 fixture)
- Create: generated `app/schemas/com.example.screencircle.data.local.AppDatabase/2.json`

**Step 1: Define schema v2**

`DailyUsage` must use composite key `(ownerId, date)` and include:

```kotlin
val ownerId: String
val date: String
val totalSeconds: Long
val lastLocalUpdateAt: Long
val revision: Long
val lastSuccessfulSyncAt: Long?
val syncState: SyncState
val syncAttemptCount: Int
val lastSyncError: String?
```

Use a Room `TypeConverter` for `SyncState`.

**Step 2: Export schemas**

Configure the Room KSP schema directory and commit generated version-2 JSON.

**Step 3: Write migration 1→2**

Because legacy Room rows have no owner UID and Room was not in the active runtime, create the new account-scoped table and quarantine legacy rows in a migration report rather than attaching them by email or guessing an owner. The migration may drop the ambiguous table only after exporting its rows. Firebase is the canonical recovery source; a missing Firebase date produces an actionable recovery warning, never a silent zero.

**Step 4: Add migration instrumentation tests**

- Opening the checked-in version-1 schema then migrating to version 2 succeeds.
- New table schema matches exported version-2 JSON.
- Legacy rows are preserved in the quarantine export and never appear under an account unless written after migration.
- Migration behavior does not depend on Firebase Authentication state.

**Step 5: Update DAO**

Add owner-scoped Flow queries and compare-and-set transactional operations. Dirty selection must never return another account's rows:

```kotlin
fun observeRange(ownerId: String, from: String, to: String): Flow<List<DailyUsage>>
fun observeDate(ownerId: String, date: String): Flow<DailyUsage?>
suspend fun getDate(ownerId: String, date: String): DailyUsage?
suspend fun upsert(usage: DailyUsage)
suspend fun markSyncing(ownerId: String, date: String, expectedRevision: Long, attemptedAt: Long): Boolean
suspend fun markSynced(ownerId: String, date: String, expectedRevision: Long, syncedAt: Long): Boolean
suspend fun markPending(ownerId: String, date: String, expectedRevision: Long, error: String?)
suspend fun getDirty(ownerId: String, limit: Int): List<DailyUsage>
```

**Step 6: Run tests**

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.screencircle.data.local.AppDatabaseMigrationTest
```

If no emulator is available, record that connected tests are pending rather than claiming they passed.

**Step 7: Commit**

```bash
git add app/src/main/java/com/example/screencircle/data/local app/src/androidTest app/schemas
git commit -m "feat: add account-scoped usage sync ledger"
```

---

### Task 5: Replace Active Usage Persistence with One Repository

**Files:**
- Rewrite: `app/src/main/java/com/example/screencircle/data/repository/UsageRepository.kt`
- Remove after migration: `app/src/main/java/com/example/screencircle/data/repository/ScreenTimeRepository.kt`
- Modify: `app/src/main/java/com/example/screencircle/ui/home/HomeViewModel.kt`
- Create: `app/src/test/java/com/example/screencircle/data/repository/UsageRepositoryTest.kt`
- Create: `app/src/androidTest/java/com/example/screencircle/data/repository/UsageRepositoryPersistenceTest.kt`
- Create: `app/src/main/java/com/example/screencircle/di/AppContainer.kt`
- Create: `app/src/main/java/com/example/screencircle/ui/common/ScreenCircleViewModelFactory.kt`
- Modify: `app/src/main/java/com/example/screencircle/ScreenCircleApplication.kt`
- Modify: `app/src/main/java/com/example/screencircle/ui/dashboard/GroupViewModel.kt`
- Modify: `app/src/main/java/com/example/screencircle/worker/DailySyncWorker.kt`

**Step 1: Write failing repository tests**

Use fakes for database, data source, calculator, user provider, and clock. Add a device test that writes pending rows, closes/reopens the database to simulate process restart, verifies they remain owner-scoped, then allows sync to upload only that owner. Cover:

- Today is recalculated and upserted as authoritative.
- Missing permission does not write zero.
- Historical Firebase-recovered rows remain readable when OS events are unavailable.
- Cloud recovery accepts both legacy numeric usage and v2 usage objects without owner ambiguity.
- One account cannot observe another account's rows.
- Calculation failure preserves the previous valid local value.
- Sync state is PENDING after a local update.
- Seven-day refresh preserves valid historical snapshots when OS events are no longer available.
- A failed refresh leaves the previous value intact.
- Recreating the repository with the same database retains pending rows and account scope.

**Step 2: Run RED**

```bash
./gradlew testDebugUnitTest --tests '*UsageRepositoryTest'
```

**Step 3: Implement one `UsageRepository`**

Public API:

```kotlin
fun observeToday(ownerId: String): Flow<DailyUsage?>
fun observeRange(ownerId: String, from: LocalDate, to: LocalDate): Flow<List<DailyUsage>>
suspend fun refresh(ownerId: String, dates: Iterable<LocalDate>): RefreshResult
suspend fun recoverFromCloud(ownerId: String, dates: Iterable<LocalDate>) // accepts legacy numeric and v2 object usage
```

Use `Clock` and `ZoneId` injection. Do not catch calculation errors and return zero. Implement bounded seven-day refresh in this repository: recalculate dates when events are available, preserve newer valid snapshots when Android history is unavailable, and mark each changed row PENDING.

**Step 4: Add manual dependency composition**

`AppContainer` constructs database, repositories, Firebase references, WorkManager scheduler, and preferences once. `ScreenCircleApplication` exposes the container. Add a `ViewModelProvider.Factory` for Home and Group ViewModels, and make WorkManager obtain the same container from the application context. No core repository may be constructed directly inside a ViewModel or Worker.

**Step 5: Update HomeViewModel temporarily**

Adapt Home and Group ViewModels to factories backed by the application container, and update the Worker to use that same container. Do not call Firebase directly from a ViewModel. Settings' legacy direct Firebase access is removed later in its dedicated task, but no repository may acquire a second database or Firebase tree.

**Step 6: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*UsageRepositoryTest' --tests '*ScreenTimeCalculatorTest'
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.screencircle.data.repository.UsageRepositoryPersistenceTest
git add app/src/main/java/com/example/screencircle/data app/src/main/java/com/example/screencircle/di app/src/main/java/com/example/screencircle/ScreenCircleApplication.kt app/src/main/java/com/example/screencircle/ui/common app/src/main/java/com/example/screencircle/ui/home/HomeViewModel.kt app/src/main/java/com/example/screencircle/ui/dashboard/GroupViewModel.kt app/src/main/java/com/example/screencircle/worker/DailySyncWorker.kt app/src/test app/src/androidTest
git commit -m "refactor: unify local and remote usage persistence"
```

---

### Task 6: Make Preferences Structured, Account-Scoped, and Consent-Aware

**Files:**
- Create: `app/src/main/java/com/example/screencircle/data/preferences/AppPreferences.kt`
- Create: `app/src/main/java/com/example/screencircle/data/preferences/GroupPreferencesCodec.kt`
- Modify: `app/src/main/java/com/example/screencircle/data/local/PreferencesManager.kt`
- Create: `app/src/test/java/com/example/screencircle/data/preferences/GroupPreferencesCodecTest.kt`

**Step 1: Write failing codec tests**

Cover group names containing `:`, `;`, Unicode, quotes, and long values. Verify round trips and malformed-input recovery.

**Step 2: Implement JSON structured storage**

Store saved groups as a JSON object/array, never `id:name;...`. Add account-scoped keys:

- current group
- saved groups
- privacy consent version
- cloud sharing enabled
- owner-managed invite metadata cache if needed

**Step 3: Add sharing preference**

Default cloud sharing to false until the user completes the disclosure and enables it.

**Step 4: Add privacy consent version**

Use a constant such as `PRIVACY_CONSENT_VERSION = 1`. Do not store consent globally across accounts; scope it by UID.

**Step 5: Deprecate and remove delimiter code**

Update all consumers to use the new API, then remove the old serializer so it cannot be reused accidentally.

**Step 6: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*GroupPreferencesCodecTest'
git add app/src/main/java/com/example/screencircle/data app/src/test/java/com/example/screencircle/data/preferences
git commit -m "fix: store account preferences without delimiter corruption"
```

---

### Task 7: Implement Firebase Schema v2 Group Repository

**Files:**
- Rewrite: `app/src/main/java/com/example/screencircle/data/repository/GroupRepository.kt`
- Create: `app/src/main/java/com/example/screencircle/data/firebase/GroupRemoteDataSource.kt`
- Create: `app/src/main/java/com/example/screencircle/data/firebase/FirebaseGroupRemoteDataSource.kt`
- Create: `app/src/main/java/com/example/screencircle/domain/group/InviteCodeGenerator.kt`
- Create: `app/src/test/java/com/example/screencircle/domain/group/InviteCodeGeneratorTest.kt`
- Create: `app/src/test/java/com/example/screencircle/data/repository/GroupRepositoryTest.kt`
- Create: `app/src/main/java/com/example/screencircle/domain/group/GroupModels.kt`

**Step 1: Write invite tests**

Verify:

- codes use approved unambiguous alphabet
- generated codes have at least 128 bits of entropy
- SHA-256 hashes are stable/lowercase
- code/hash are different representations

Add `GroupRepositoryTest` with a fake `GroupRemoteDataSource` covering awaited create/join/leave/rotate/revoke/transfer/delete calls, exact multipath payloads, owner checks, error mapping, and cancellation of the observed member/usage flows.

**Step 2: Define domain models**

Include:

```kotlin
data class GroupSummary(...)
data class GroupMemberSnapshot(userId: String, displayName: String, todayUsageSeconds: Long)
data class GroupDashboardSnapshot(group: GroupSummary, members: List<GroupMemberSnapshot>)
sealed interface GroupOperationResult { Success, NotFound, InvalidInvite, PermissionDenied, Conflict, Failure }
```

**Step 3: Implement cancellable observation**

Expose:

```kotlin
fun observeGroup(groupId: String, date: LocalDate): Flow<GroupDashboardSnapshot>
```

Use `callbackFlow` for members and usage, combine them, and remove listeners in `awaitClose`. During the expand window, normalize legacy boolean member values and v2 member objects, and accept legacy numeric usage or v2 usage objects. No listener reference may remain in a ViewModel.

**Step 4: Implement atomic operations**

Add suspending functions for:

- `createGroup`
- `joinWithInvite`
- `leaveGroup`
- `rotateInvite`
- `revokeInvite`
- `transferOwnership`
- `deleteGroup`

Use one root-level `updateChildren` operation per logical group mutation and await every Task. The exact payload must include member, reverse index, owner/invite changes, and cleanup paths affected by that operation.

**Step 5: Enforce local ownership checks**

Owner-only operations verify `group.owner == currentUid` before writing, while security rules remain the final authority.

**Step 6: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*InviteCodeGeneratorTest' --tests '*GroupRepositoryTest'
git add app/src/main/java/com/example/screencircle/domain/group app/src/main/java/com/example/screencircle/data/firebase app/src/main/java/com/example/screencircle/data/repository/GroupRepository.kt app/src/test
git commit -m "feat: add secure cancellable group operations"
```

---

### Task 8: Add Versioned Expand/Contract Firebase Rules and Emulator Tests

**Files:**
- Create: `firebase/firebase.expand.json`
- Create: `firebase/firebase.contract.json`
- Create: `firebase/database.rules.expand.json`
- Create: `firebase/database.rules.contract.json`
- Create: `firebase/package.json`
- Create: `firebase/package-lock.json`
- Create: `firebase/tests/database.rules.test.mjs`
- Create: `firebase/tests/legacy-compatibility.test.mjs`

**Step 1: Add Node test dependencies**

Use:

- `firebase`
- `@firebase/rules-unit-testing`
- `firebase-tools`
- Node's built-in test runner

Add scripts:

```json
{
  "test:rules:shared": "node --test tests/database.rules.test.mjs",
  "test:rules:expand": "npm run test:rules:shared && node --test tests/legacy-compatibility.test.mjs",
  "test:rules:ci": "firebase emulators:exec --config firebase.expand.json --project demo-screencircle --only database \"npm run test:rules:expand\"",
  "test:rules:contract": "firebase emulators:exec --config firebase.contract.json --project demo-screencircle --only database \"npm run test:rules:shared\"",
  "emulators": "firebase emulators:start --config firebase.expand.json --project demo-screencircle --only database"
}
```

**Step 2: Write failing rule tests first**

Create authenticated contexts for owner, member, non-member, second member, and anonymous user. Assert:

- anonymous reads fail
- private profiles are owner-only
- only the owner can read/write their private compatibility heartbeat, and it cannot contain profile or usage data
- canonical usage is owner-only
- non-members cannot read groups
- members can read member names and group usage
- users can join only themselves with matching active invite hash
- users cannot add other members
- only owners can rename, rotate, transfer, revoke, or delete
- group usage mirrors are member-writable only for the member's own UID
- mirror seconds/source timestamp must match already-committed canonical usage
- direct reverse-index, owner, member-record, invite-field, and subtree deletion/replacement writes are rejected
- malformed, null, negative, overflow, future, and unknown fields are rejected by contract rules
- revoked/rotated invite hashes fail and legacy boolean members remain accepted only by expand rules

**Step 3: Run tests and confirm RED**

```bash
cd firebase
npm ci
npx firebase-tools emulators:exec --config firebase.expand.json --project demo-screencircle --only database "npm run test:rules:expand"
```

Expected: rule assertions fail before rules are complete.

**Step 4: Implement rules**

Expand rules permit the known legacy shape plus validated additive v2 writes. Contract rules reject legacy shapes and unknown fields, enforce membership/owner checks, validate group IDs/hashes and all field ranges, require mirror/canonical equality, and never expose private profile fields through group data. Both rule sets run the shared security suite; compatibility-only assertions run against expand rules.

**Step 5: Run tests and confirm GREEN**

Run `npm run test:rules:ci` and `npm run test:rules:contract`. Expected: all shared security tests pass; legacy compatibility tests pass only under expand rules.

**Step 6: Commit**

```bash
git add firebase
git commit -m "feat: enforce private group Firebase security rules"
```

---

### Task 9: Build the Existing-Data Migration Utility

**Files:**
- Create: `scripts/firebase/migration-lib.mjs`
- Create: `scripts/firebase/migration-lib.test.mjs`
- Create: `scripts/firebase/migrate-v2.mjs`
- Create: `scripts/firebase/assert-contract-ready.mjs`
- Create: `scripts/firebase/deploy-contract.sh`
- Create: `firebase/tests/fixtures/legacy-v1.json`
- Create: `firebase/tests/assert-contract-ready.test.mjs`
- Create: `docs/firebase-migration-runbook.md`
- Modify: `firebase/package.json`

**Step 1: Write transformation tests**

Use synthetic v1 fixtures. Verify:

- emails move to private profile
- member booleans become named records
- usage mirrors are generated for every group membership
- invite code/hash are generated
- migration is idempotent
- already-v2 data is unchanged
- malformed records are quarantined and never silently deleted
- identity resolution uses only trusted UID paths, never email matching
- mixed v1/v2 input is normalized deterministically
- unknown fields are preserved in the archive
- interrupted apply can resume from its manifest
- rollback restores the exact versioned fixture

**Step 2: Implement pure migration transforms**

Keep data transformation independent from Firebase Admin for unit testing. Resolve ownership only from the UID path in each legacy record; never attach data by email. Preserve unknown fields in the archive and quarantine malformed paths for explicit review.

**Step 3: Implement Admin adapter**

Add commands:

```bash
npm run firebase:migrate:dry-run
npm run firebase:migrate:apply -- --confirm
npm run firebase:migrate:verify
npm run firebase:migrate:rollback -- --confirm
npm run firebase:assert-contract-ready -- --min-version=2.0.0 --active-days=30
```

Add:

```json
{
  "firebase:migrate:dry-run": "node ../scripts/firebase/migrate-v2.mjs dry-run",
  "firebase:migrate:apply": "node ../scripts/firebase/migrate-v2.mjs apply",
  "firebase:migrate:verify": "node ../scripts/firebase/migrate-v2.mjs verify",
  "firebase:migrate:rollback": "node ../scripts/firebase/migrate-v2.mjs rollback",
  "firebase:assert-contract-ready": "node ../scripts/firebase/assert-contract-ready.mjs",
  "test:migration": "firebase emulators:exec --config firebase.expand.json --project demo-screencircle --only database \"node --test ../scripts/firebase/migration-lib.test.mjs ../scripts/firebase/assert-contract-ready.test.mjs\""
}
```

Apply mode must require:

- explicit `--confirm`
- valid Admin credentials
- a completed backup manifest
- expected project ID
- successful preflight validation

**Step 4: Add rollback export support**

Export legacy and migrated records with a schema version, manifest version, batch IDs, and checksums before apply. Implement an idempotent resumable batch process plus a deterministic rollback command that restores the versioned export and verifies it. Plaintext migrated invite codes go only into the checksum-protected operator export, never Firebase.

**Step 5: Test against Firebase Emulator**

Run RED first:

```bash
npm run test:migration
```

Seed the checked-in v1 fixture, run dry-run/apply/interrupted-retry/verify/rollback, and compare expected counts/checksums. Run apply twice to prove idempotence and include mixed-schema, ambiguous-identity, malformed-record, and unknown-field fixtures. Test the contract-readiness assertion with passing, missing-heartbeat, old-version, wrong-project, stale-manifest, and migration-failure fixtures. Expected after implementation: `npm run test:migration` passes under a clean Emulator instance.

**Step 6: Document and commit**

The runbook must state that production commands are intentionally manual and require a separately approved execution window.

```bash
git add scripts/firebase docs/firebase-migration-runbook.md firebase/package.json firebase/package-lock.json
git commit -m "feat: add verified Firebase schema migration"
```

---

### Task 10: Await Firebase Usage Sync and Group Mirrors

**Files:**
- Create: `app/src/main/java/com/example/screencircle/data/repository/UsageSyncRepository.kt`
- Create: `app/src/test/java/com/example/screencircle/data/repository/UsageSyncRepositoryTest.kt`
- Create: `app/src/test/java/com/example/screencircle/worker/UsageSyncWorkerTest.kt`
- Create: `app/src/test/java/com/example/screencircle/ScreenCircleApplicationTest.kt`
- Modify: `app/src/main/java/com/example/screencircle/ScreenCircleApplication.kt`
- Rewrite: `app/src/main/java/com/example/screencircle/worker/DailySyncWorker.kt`

**Step 1: Write failing sync tests**

Cover:

- sharing disabled means no Firebase write
- signed-out state is not marked synced
- canonical and all group mirror writes complete before SYNCED
- owner-scoped dirty selection never selects another account's rows
- a local update during sync, including two updates with the same wall-clock millisecond, prevents the older revision from being marked SYNCED
- duplicate, stale, and out-of-order updates follow the non-decreasing contract
- canonical success followed by mirror failure remains dirty and retries idempotently
- transient failure returns retryable and leaves PENDING
- permission-required is not treated as network retry
- a group the user has left no longer receives a mirror
- a single dirty row does not upload unrelated clean rows

**Step 2: Implement sync repository**

Read bounded dirty rows for the active owner, commit and await canonical usage, then commit mirrors in a second awaited operation that rules compare to canonical usage. Use the Room `revision` as the expected compare-and-set token so a newer refresh, including one in the same millisecond, cannot be marked synced. Any partial remote success remains dirty and safe to retry. Bound group fanout and log counts without PII.

**Step 3: Refactor WorkManager**

- Use `UsageSyncWorker` name and documentation.
- Return `Result.retry()` only for transient failures.
- Return success for a deliberate pause/no-user state while recording diagnostics.
- Use `ExistingPeriodicWorkPolicy.UPDATE` so changed constraints/interval reach existing installs.
- Keep connected and battery-not-low constraints.
- Test the actual Worker with `TestListenableWorkerBuilder`: paused/signed-out success, permission-required no-network-retry, transient retry, process restart, and no duplicate concurrent uploads.
- Test scheduling with WorkManager Testing: an existing unique job receives the new six-hour interval/constraints through `UPDATE`.

**Step 4: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*UsageSyncRepositoryTest' --tests '*UsageSyncWorkerTest' --tests '*ScreenCircleApplicationTest'
git add app/src/main/java/com/example/screencircle/data/repository app/src/main/java/com/example/screencircle/worker app/src/main/java/com/example/screencircle/ScreenCircleApplication.kt app/src/test
git commit -m "fix: await usage sync and retry failed writes"
```

---

### Task 11: Add Privacy Consent and Authentication Guards

**Files:**
- Create: `app/src/main/java/com/example/screencircle/ui/consent/PrivacyConsentController.kt`
- Create: `app/src/test/java/com/example/screencircle/ui/consent/PrivacyConsentControllerTest.kt`
- Modify: `app/src/main/java/com/example/screencircle/ui/main/MainActivity.kt`
- Modify: `app/src/main/java/com/example/screencircle/ui/login/LoginActivity.kt`
- Modify: `app/src/main/res/layout/activity_login.xml`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Write consent/auth tests**

Cover current consent version, account scope, sharing disabled before consent, expired consent requiring disclosure again, explicit withdrawal pausing remote writes, retained PENDING rows, re-enable behavior, existing users with no stored consent defaulting to paused, and logout/login preventing consent from carrying to another UID.

**Step 2: Implement first-use disclosure**

Explain:

- device screen-on measurement
- Usage Access scope
- local Room storage
- Firebase canonical storage
- group member visibility
- pause/delete controls
- no per-app foreground tracking

Require an explicit continue choice, but do not block access to Settings.

**Step 3: Add auth guards and compatibility heartbeat**

`MainActivity` must verify `FirebaseAuth.currentUser` before showing destinations and redirect expired sessions to Login, clearing the task appropriately. The compatibility release writes its semantic version/build code and a server timestamp to the current UID's private `client` node on authenticated startup; heartbeat failure is non-fatal but observable.

**Step 4: Repair Login UI**

- Button/progress mutual exclusion.
- Disable login, register, and reset while loading.
- Separate login and registration validation.
- Generic Firebase error strings.
- Accessible loading status.

**Step 5: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*PrivacyConsentControllerTest'
git add app/src/main/java/com/example/screencircle/ui app/src/main/res app/src/test
git commit -m "feat: add privacy consent and authentication guards"
```

---

### Task 12: Replace Manual Fragment Swaps with Navigation Component

**Files:**
- Create: `app/src/main/res/navigation/nav_graph.xml`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/example/screencircle/ui/main/MainActivity.kt`
- Create: `app/src/androidTest/java/com/example/screencircle/ui/MainNavigationTest.kt`

**Step 1: Write failing navigation tests**

Assert:

- Home is initial authenticated destination
- switching tabs preserves each destination's scroll/state
- selected bottom-nav item restores after rotation
- session expiry finishes the authenticated activity, opens Login with `CLEAR_TASK|NEW_TASK`, and prevents Back from returning to an authenticated destination
- signed-out access cannot instantiate Groups or Settings
- process recreation on each tab restores the correct destination and selected item

**Step 2: Add navigation graph**

Add Home, Groups, and Settings destinations. Use `FragmentContainerView` with `NavHostFragment` and `setupWithNavController`.

**Step 3: Remove manual `replace()` logic**

Delete `loadFragment()` and explicit new Fragment construction. Keep Login as a separate Activity; session expiry performs the activity/task transition rather than pretending Login is a Navigation destination.

**Step 4: Verify and commit**

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.screencircle.ui.MainNavigationTest
git add app/src/main/java/com/example/screencircle/ui/main app/src/main/res app/src/androidTest
git commit -m "refactor: adopt state-preserving app navigation"
```

---

### Task 13: Rebuild Home as an Explicit State Machine

**Files:**
- Create: `app/src/main/java/com/example/screencircle/ui/common/Loadable.kt`
- Rewrite: `app/src/main/java/com/example/screencircle/ui/home/HomeViewModel.kt`
- Rewrite: `app/src/main/java/com/example/screencircle/ui/home/HomeFragment.kt`
- Modify: `app/src/main/res/layout/fragment_home.xml`
- Create: `app/src/test/java/com/example/screencircle/ui/home/HomeViewModelTest.kt`
- Create: `app/src/androidTest/java/com/example/screencircle/ui/PermissionFlowTest.kt`

**Step 1: Write failing state tests**

Cover Loading, PermissionRequired, Error, valid Zero, and Content. Verify:

- permission never maps to zero
- errors retain retry capability
- today and weekly refresh together
- average divides by seven
- retry does not duplicate requests

Add a device/UI test for the full permission journey: open system Usage Access settings, return granted and refresh, then revoke while running, return, and display PermissionRequired while Groups/Settings remain reachable.

**Step 2: Implement state and events**

Use `StateFlow<HomeUiState>` and a separate event flow for snackbars. Observe with `repeatOnLifecycle`.

**Step 3: Redesign layout states**

Add:

- loading indicator
- permission-required Material card and action button
- error/retry view
- content group
- accessible seven-day chart summary
- theme-aware chart colors

**Step 4: Add accessibility semantics**

Chart gets a concise content description. Timer image is decorative. Values expose accessibility text rather than reading punctuation poorly.

**Step 5: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*HomeViewModelTest'
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.screencircle.ui.PermissionFlowTest
./gradlew lintDebug
git add app/src/main/java/com/example/screencircle/ui app/src/main/res app/src/test app/src/androidTest
git commit -m "feat: add resilient accessible home states"
```

---

### Task 14: Rebuild Group Dashboard and Owner Controls

**Files:**
- Rewrite: `app/src/main/java/com/example/screencircle/ui/dashboard/GroupViewModel.kt`
- Rewrite: `app/src/main/java/com/example/screencircle/ui/dashboard/GroupDashboardFragment.kt`
- Modify: `app/src/main/java/com/example/screencircle/ui/dashboard/GroupAdapter.kt`
- Modify: `app/src/main/res/layout/fragment_dashboard.xml`
- Create: `app/src/main/res/layout/dialog_create_group.xml`
- Create: `app/src/main/res/layout/dialog_join_group.xml`
- Create: `app/src/test/java/com/example/screencircle/ui/dashboard/GroupViewModelTest.kt`
- Modify: `app/src/main/res/values/strings.xml`

**Step 1: Write failing ViewModel tests**

Cover NoGroup, Loading, Empty, Content, Error, stale-result suppression, tie sorting, and duplicate-submission prevention.

**Step 2: Implement state and event flows**

Collect the repository Flow with `stateIn`. Use request generations or Flow cancellation so old groups cannot publish. Emit one-time success/error effects.

**Step 3: Implement user flows**

- Create group with generated invite.
- Join using invite code.
- Switch saved groups.
- Copy/share invite.
- Leave with confirmation.
- Owner: rotate/revoke invite, transfer ownership, delete group.
- Prevent owner leave until transfer/delete.

**Step 4: Redesign UI**

Use an explicit group selector button, 48dp copy controls, progress states, deterministic ranks, accessible empty/error states, and vector copy icon.

**Step 5: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*GroupViewModelTest'
./gradlew lintDebug
git add app/src/main/java/com/example/screencircle/ui/dashboard app/src/main/res app/src/test
git commit -m "feat: harden group dashboard and owner workflows"
```

---

### Task 15: Rebuild Settings, Backup Policy, and Theme Accessibility

**Files:**
- Rewrite: `app/src/main/java/com/example/screencircle/ui/settings/SettingsFragment.kt`
- Create: `app/src/main/java/com/example/screencircle/ui/settings/SettingsViewModel.kt`
- Create: `app/src/main/java/com/example/screencircle/data/repository/SettingsRepository.kt`
- Create: `app/src/main/java/com/example/screencircle/data/repository/AccountDeletionRepository.kt`
- Modify: `app/src/main/res/layout/fragment_settings.xml`
- Modify: `app/src/main/res/xml/backup_rules.xml`
- Modify: `app/src/main/res/xml/data_extraction_rules.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values-night/colors.xml`
- Create: `app/src/main/res/values-night/themes.xml`
- Modify: `app/src/main/res/values/colors.xml`
- Modify: `app/src/main/res/values/themes.xml`
- Modify: `app/src/main/res/values/strings.xml`
- Create: `app/src/test/java/com/example/screencircle/ui/settings/SettingsViewModelTest.kt`

**Step 1: Write failing settings tests**

Cover profile loading/error, sharing toggle, consent withdrawal, local-data deletion, leave success/failure, logout account cleanup, stale callback suppression, account isolation, interrupted deletion retry, and the rule that owner accounts must transfer/delete owned groups before Firebase Auth deletion. Add a device test that kills/recreates the process after deletion and proves deleted rows do not return or re-upload.

**Step 2: Move Firebase UI operations behind ViewModel/repository**

Settings must not directly call Firebase or capture binding in callbacks.

**Step 3: Add sharing and deletion controls**

Show current sync state, allow pause/resume, and provide confirmed local-history deletion. Implement account deletion as an idempotent, reauthenticated flow: require no owned groups, remove the UID from every group and its mirrors, delete canonical/private/public/reverse-index data, verify those writes, then call Firebase Auth account deletion. If any phase fails, stop before Auth deletion and expose a safe retry.

**Step 4: Minimize backups**

Set `android:allowBackup="false"` if account-scoped cloud backup is not explicitly required, or exclude ScreenCircle preferences and usage database in both backup formats. Add a test/assertion in CI that no broad sharedpref/database include remains.

**Step 5: Complete dark theme and truthful copy**

Remove unsupported battery/data comparisons. Add night palette and use theme attributes in charts/cards.

**Step 6: Verify and commit**

```bash
./gradlew testDebugUnitTest --tests '*SettingsViewModelTest'
./gradlew lintDebug
git add app/src/main app/src/test app/src/androidTest
git commit -m "feat: add private resilient settings experience"
```

---

### Task 16: Complete Android Accessibility and Lifecycle Tests

**Files:**
- Create: `app/src/androidTest/java/com/example/screencircle/ui/AccessibilityTest.kt`
- Modify: relevant layout files
- Modify: relevant UI classes
- Modify: `app/src/androidTest/AndroidManifest.xml` if required

**Step 1: Write failing accessibility tests**

Assert on API 26 and API 35 with font scales 1.0, 1.3, and 2.0:

- all interactive controls meet 48dp targets
- group selector and copy controls have content descriptions
- loading/permission/error states produce one concise announcement
- chart exposes one seven-day text summary
- decorative images are ignored by accessibility services
- primary actions/labels do not clip at tested font scales
- keyboard/D-pad focus order is logical and visible
- reduced-motion preference disables nonessential chart animation
- normal text contrast is at least 4.5:1 and non-text control indicators are at least 3:1

**Step 2: Run tests and inspect failures**

```bash
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.screencircle.ui.AccessibilityTest
```

**Step 3: Fix semantics and layouts**

Use actual Android accessibility attributes, not web ARIA. Add headings, state descriptions, minimum targets, keyboard/D-pad focus, and reduced-motion handling.

**Step 4: Manual TalkBack checklist**

Document and execute a manual smoke test covering Login, permission, Home, Group switch/create/join/leave, owner controls, Settings sharing toggle, and logout.

**Step 5: Verify and commit**

```bash
./gradlew lintDebug testDebugUnitTest
./gradlew connectedDebugAndroidTest
git add app/src/main app/src/androidTest docs/accessibility-smoke-test.md
git commit -m "fix: complete Android accessibility coverage"
```

---

### Task 17: Harden Release Signing, R8, and Configuration Validation

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Create: `scripts/validate-release-config.sh`
- Create: `keystore.properties.example`
- Modify: `.gitignore`
- Modify: `apk_generation_guide.md`

**Step 1: Add validation script**

Validate without printing secret values:

- release Firebase config exists
- package name matches
- signing properties exist when release signing is requested
- keystore file exists
- no known placeholder project/API values are used for production
- mapping output directory is writable

**Step 2: Add external signing configuration**

Read optional environment variables or ignored `keystore.properties`. CI uses `--unsigned` and must produce an unsigned release artifact; production signing uses a separate explicit command and secrets. Never fall back to a debug key. Fail clearly when production signing is requested but incomplete.

**Step 3: Enable R8/resource shrinking**

Start with full mode and library keep rules only where verified. Remove broad package retention unless a minified test proves it necessary.

**Step 4: Build and smoke-test release artifact**

```bash
./scripts/validate-release-config.sh --unsigned
./gradlew assembleRelease
```

Verify the CI artifact is explicitly unsigned and the signed-release command is not part of ordinary CI. In a separately approved signing environment, run the signed command, verify APK metadata/signature with `apkanalyzer` or `apksigner`, and ensure secrets do not appear in source or logs.

**Step 5: Update release guide**

Document debug/signed paths, external secrets, mapping retention, Firebase config, and verification. Never recommend storing production keystores inside the repository.

**Step 6: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro scripts/validate-release-config.sh keystore.properties.example .gitignore apk_generation_guide.md
git commit -m "build: harden release signing and shrinking"
```

---

### Task 18: Add CI, Documentation, and Final Verification

**Files:**
- Create: `.github/workflows/android-ci.yml`
- Create: `docs/architecture.md`
- Create: `docs/privacy.md`
- Create: `docs/release-rollout-runbook.md`
- Rewrite: `README.md`
- Rewrite: `setup_guide.md`
- Modify: `firebase/package.json`

**Step 1: Add CI job**

Use `actions/checkout`, `actions/setup-java` with JDK 17, Android SDK setup, cached Gradle, and cached npm dependencies. Generate `google-services.json` from a sanitized CI fixture or encrypted repository secret. Never print secret values.

Run:

```bash
./gradlew lintDebug testDebugUnitTest assembleDebug assembleRelease
cd firebase && npm ci && npm run test:rules:ci && npm run test:rules:contract && npm run test:migration
```

Run connected tests in a separate emulator job.

**Step 2: Document architecture**

Update the old direct Android → Firebase diagram to the Room ledger, awaited sync, schema v2, and group mirror flow.

**Step 3: Document privacy**

Describe permissions, data fields, visibility, sharing pause, retained pending rows, retention, local/account deletion, backups, invite-code behavior, and Firebase Analytics being removed/disabled by default. Define exactly what happens on consent withdrawal and account switching.

**Step 4: Correct setup and operational claims**

Remove Test Mode, machine-specific paths, unsupported battery comparisons, and incorrect APK paths. Document Firebase schema deployment, migration, rollback, WorkManager approximation, and test commands. The rollout runbook must define rule/schema order, minimum compatible client version, staged cohorts, adoption/error thresholds, migration verification, rollback criteria, monitoring ownership, and support procedure.

**Step 5: Run fresh full verification**

```bash
git diff --check
./gradlew clean lintDebug testDebugUnitTest assembleDebug assembleRelease
cd firebase && npm ci && npm run test:rules:ci && npm run test:rules:contract && npm run test:migration
```

If an emulator is available:

```bash
./gradlew connectedDebugAndroidTest
```

**Step 6: Verify no secrets or generated data are tracked**

```bash
set -euo pipefail
tracked_files="$(git ls-files)"
if grep -E 'google-services\.json$|\.jks$|\.keystore$|keystore\.properties$|service-account.*\.json$|firebase-debug\.log$' <<<"$tracked_files"; then
  echo "Forbidden secret or generated file is tracked" >&2
  exit 1
fi
git status --short
```

**Step 7: Space Bunny review panel**

Dispatch read-only Space Bunny reviewers for:

- screen-time correctness
- Firebase rules/migration
- Android lifecycle/accessibility
- build/release/security
- test adequacy

Consolidate only independently verified findings. Fix all Critical/Major findings and rerun affected tests.

**Step 8: Commit**

```bash
git add .github docs README.md setup_guide.md firebase/package.json firebase/package-lock.json
git commit -m "ci: verify ScreenCircle production hardening"
```

**Step 9: Final report**

Report:

- branch and commit range
- exact verification commands and outcomes
- emulator-connected test status
- migration rehearsal status
- remaining external prerequisites: real Firebase project, production credentials, signing key, physical-device TalkBack test, production migration approval
- no claim of production deployment or migration

---

## Production Execution Gate

Implementation completion does **not** authorize:

- applying the Firebase migration to production
- deploying strict database rules
- uploading signing keys
- publishing to Google Play
- sending production credentials to CI

After all code tasks pass, stop and present the migration/rule deployment checklist for separate explicit approval.
