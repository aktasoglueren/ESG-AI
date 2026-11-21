# ESG AI — Hybrid foundation

This branch adds the first secure online-provider building block while preserving the existing on-device LiteRT-LM chat path.

## Current implementation
- Existing local model picker and local text conversation remain in `MainActivity`.
- `HybridAiClient` provides an OpenAI-compatible chat-completions HTTPS client.
- Optional image input is sent as a base64 data URL for providers/models that support vision.
- API keys are encrypted using an AES-GCM key held by Android Keystore.
- Android INTERNET permission is declared.

## Configuration contract
Call `HybridAiClient.saveConfig(context, endpoint, model, apiKey)` after the user enters their own provider details. The endpoint must use HTTPS. Retrieve with `loadConfig(context)`; remove with `clearConfig(context)`.

Default endpoint: `https://api.openai.com/v1/chat/completions`.
The app does not ship with an API key. The user must supply their own provider key and pay any provider charges that apply.

## Important status
This is foundation code, not a completed hybrid user experience yet. The current main screen still routes messages to the local model only. A settings screen, online/local mode selector, image picker and routing integration remain to be implemented. Local image understanding also requires a compatible on-device vision model and runtime; it is not provided by this commit.

## Build check
Run the repository's GitHub Actions workflow on this branch using **Actions → ESG AI Debug APK → Run workflow**, selecting `feature/hybrid-ai-foundation`. Install and test the generated debug APK before merging to `main`.
