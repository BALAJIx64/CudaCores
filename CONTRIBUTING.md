# Contributing to GuardianGrid

Thank you for contributing to GuardianGrid! This repository uses a strict modular ownership model designed to enable four developers to work simultaneously without code conflicts.

---

## 1. Team Ownership & Branching Model

Each team member has sole ownership of their respective subsystem directory and feature branch:

| Contributor | Subsystem | Branch Name | Owned Directories |
| :--- | :--- | :--- | :--- |
| **Member 1** | Audio Intelligence | `feature/audio` | `modules/audio/`, `models/audio/` |
| **Member 2** | Motion Anomaly & Fusion | `feature/motion` | `modules/motion/` |
| **Member 3** | Resilient Communication | `feature/communication` | `modules/communication/` |
| **Member 4** | Core Engine, UI & Integration | `feature/core-ui` | `app/`, `core/models/`, `core/interfaces/`, `core/state/` |

### Invariant Rules
1. **Never commit directly to `main`:** All changes must originate from a feature branch.
2. **Strict Ownership Boundaries:**
   - **Member 1 (`feature/audio`):** Exclusively writes implementations inside `modules/audio/` and places `.tflite` models in `models/audio/`.
   - **Member 2 (`feature/motion`):** Exclusively writes implementations inside `modules/motion/`.
   - **Member 3 (`feature/communication`):** Exclusively writes implementations inside `modules/communication/`.
   - **Member 4 (`feature/core-ui`):** Owns the application entry point, shared core contracts/models, UI, and integration coordination (`app/`, `core/models/`, `core/interfaces/`, `core/state/`).
   - **Shared File Protection:** Members 1, 2, and 3 must not independently modify shared application files (`app/`, `core/`, `build.gradle.kts`, `settings.gradle.kts`). Any modifications to shared contracts in `core/interfaces/` require cross-team agreement and must be coordinated through Member 4.
3. **Never alter remote configuration:** Do not modify git remotes or push unreviewed destructive operations.

---

## 2. Git Workflow

### Step 1: Check Out Your Feature Branch
```bash
git checkout -b feature/<your-module>
```

### Step 2: Implement Against Module Contracts
- Read [docs/MODULE_CONTRACTS.md](docs/MODULE_CONTRACTS.md) thoroughly.
- Implement the exact Kotlin interface methods specified for your module.
- Do not change method signatures without prior agreement.

### Step 3: Run Local Tests
```bash
# Verify that all unit tests and contract tests pass
./gradlew testDebugUnitTest
```

### Step 4: Commit with Conventional Commits
Use standard commit message formats:
- `feat(audio): implement on-device scream detection with TFLite`
- `feat(motion): add 3-axis accelerometer impact threshold calculator`
- `feat(comm): implement Telegram Bot API HTTP alert dispatch`
- `fix(core): resolve countdown timer synchronization in EmergencyCoordinator`
- `test(audio): add unit tests for circular audio buffer dump`

### Step 5: Pull Request
Open a Pull Request into `main`. Ensure:
- The PR description details which technical contribution is implemented.
- Unit tests pass.
- No files outside your assigned directory have been altered.

---

## 3. Code Quality Guidelines

- **Language:** Idiomatic Kotlin (prefer immutable `val`, data classes, coroutine `Flow`).
- **Style:** Follow official Kotlin coding style (`kotlin.code.style=official`).
- **Resource Management:**
  - Always release hardware sensors (`SensorManager.unregisterListener`) and microphone resources (`AudioRecord.release()`) when monitoring stops.
  - Never hold wake locks indefinitely; release immediately upon alarm cancellation or resolution.
- **Privacy First:**
  - Raw audio data must never be transmitted off-device unless a high-confidence distress event is authenticated.
  - Temporary rolling audio buffers must be overwritten in memory and discarded.
