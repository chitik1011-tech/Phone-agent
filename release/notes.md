## Phone Agent 0.1.4 preview

- The chat model picker lists saved models across providers. Import a provider catalog or add an ID once, then browse or search locally. Model selection, output limits and reasoning preferences persist across app launches.
- Choose an available reasoning level and set a token cap before using a model. Direct Claude and DeepSeek workspace tool requests use the compatible defaults. Availability depends on the provider and model.
- Provider connections use a fixed 3-second connection timeout. Streaming responses have a separate read deadline.
- The APK includes v1, v2 and v3 signatures. In-place updates require the same signing certificate. CI previews use a debug certificate unless release signing is configured.
- Provider requests are verified against fixtures. Live accounts and OnePlus ColorOS installation need separate checks.
