# Architecture notes (for whoever extends this app)

Kotlin + Jetpack Compose (Material 3), single module `app`, package `com.myassistant.app`.
CI (`.github/workflows/build.yml`) builds a signed release APK on every push to `main` and publishes it as a GitHub Release.
If a build fails, the log is force-pushed to the `ci-logs` branch as `last-build.log`.

## Extension points
- **New tool** → implement `tools/Tool` in one file, then add it to the list in `AppGraph` (`AssistantApp.kt`).
  Set `requiresConfirmation = true` for anything that sends, deletes, pays or posts.
- **New provider kind** → implement `ai/LlmProvider`, add a `ProviderType` and a branch in `ProviderFactory`.
  Any OpenAI-compatible service needs no code: add it from the Settings screen (or a preset in `ProviderPresets`).
- **Memory backend** → implement `memory/MemoryStore` (e.g. on-device embeddings) and swap it in `AppGraph`.

## Conventions
- `core/ChatMessage` is the provider-neutral message format; providers translate to/from it.
- `ProviderRouter` tries enabled providers in order and falls back on any error.
- Secrets go through `security/SecretStore` (Android Keystore AES-GCM), never plain prefs.
- UI is Arabic and forced RTL; keep strings short and Egyptian-friendly.
- The signing key in `app/signing/` must stay the same, or users can't update without losing data.
