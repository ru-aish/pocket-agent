# Personal memory (Android)

Pocket Agent keeps its app-private `personal-memory.db` store in the Android process. An optional Hindsight MCP memory bank adds semantic/temporal retrieval and completed-turn retention without replacing SQLite. Neither mode starts a local vector daemon or additional Android service.

## Design

- **Always-available personal profile:** concise confirmed and inferred preferences, people, active projects, goals, and repeatable procedures are injected into the real Pi system transcript before a user turn. The same mechanism applies to resumed workers and newly spawned workers.
- **Relevant episodic recall:** Android SQLite FTS4 searches localized indexed text; only bounded, relevant results are included in context, and each carries confirmation status, source and update time. No embedding model is needed for normal recall.
- **Explicit writes and corrections:** the model has `memory_save`, `memory_search`, `memory_profile` and `memory_forget` tools. Stable keys update previous facts rather than accumulating duplicates. The model is instructed to save stable facts when directly asked, and to resolve corrections. The original user transcript is retained separately.
- **Automatic observation:** when three successful main user turns accumulate, a separate bounded observer/reflector session reuses the configured inference provider. It gets **only user messages**, never worker tool output or app-controlled content. It extracts at most five non-sensitive facts and stores them as inferred (`confirmed=false`). If inference fails, queued input is retained in the existing runtime checkpoint for a future attempt. Background observation does not block normal user turns or add another local model process.
- **Thread scope:** only main can write or delete shared global memories. Workers can read global memories and their own task-private notes, but cannot see sibling notes or promote their own claims into global facts. The read-only researcher has no memory mutation tool. No worker can bypass inherited tool grants.
- **Provenance:** confirmed memories cannot be automatically overwritten by observer output. Returned source and timestamps are visible for retrieval and corrections. Explicit agent calls are distinguished from automatically inferred records; confidence is not the same as verified completion.
- **Limits:** 5,000 records total, 800 characters each, FTS indexing on Android, 16 profile hits plus search, and 5.5 KB maximum injected recall. No additional SQLite extensions or native libraries.

## Optional Hindsight integration

1. Run or choose a trusted Hindsight service with HTTPS and a **single-bank** MCP URL such as `https://your-memory-host/mcp/pocket-agent/`. The multi-bank `/mcp/` endpoint is deliberately not used.
2. On the phone, open **Settings → Remote MCP servers → Add remote MCP server**. Set the server name to exactly **Hindsight**, enter the bank-scoped URL, and configure bearer-token authentication if required. The Android app generates its own UUID server ID; the integration recognizes the name, not that ID.
3. After reconnecting, new successful main conversations and worker-completion deliveries are retained via Hindsight's `retain` tool. Before every main/worker inference, `recall` contributes a bounded, explicitly untrusted context block alongside local SQLite results. No provider, UI, or new Android networking stack is required.

Capture is opt-in through this named MCP server. New retained events contain the user request/worker terminal report plus the assistant's response (up to 8,000 characters per event), with source and timestamp. Known provider/MCP credential values are redacted, but **this is not a general sensitive-data detector**: do not enable an untrusted memory endpoint. Failed retains remain in a bounded 100-event durable checkpoint queue, retry on future successful connections/turns, and never transfer to a different bank URL. SQLite tools continue to work when Hindsight is offline. Past transcripts are **not automatically backfilled**.

## Security and limitations

No secrets, medical data, private identifiers, political or other sensitive information should be automatically remembered. A model instruction alone is not a complete privacy filter, so a shipping-grade consent/delete-all UI and stricter sensitive-data validation remain necessary. Existing Android app-private filesystem protections apply; the SQLite file and pending Hindsight uploads are not separately Keystore-encrypted. Memory data is treated as untrusted context, not fresh operational evidence or a standing authorization to send messages, mutate apps or share data. **Local** `memory_forget` does not erase separately ingested Hindsight records; use Hindsight document/memory management on the connected bank as well when deleting remote data. An end-to-end forget-both-stores workflow is still needed.

The background observer is a **single model-call observer and reflector**, not a verbatim port of Mastra Observational Memory or Supermemory. Semantic recall requires the optional Hindsight bank; guaranteed cross-lingual accuracy is not established. Full end-to-end quality requires multi-session correction, stale-fact and multilingual datasets; fast FTS lookup is not a quality benchmark.

## Testing

`cd runtime && npm ci && npm test && npm run build` runs real Pi runtime tests. Android instrumentation `dev.pocket.agent.MemoryDeviceTest` checks SQLite persistence, update/delete index synchronization, permission scopes, rejection of untrusted updates to confirmed facts, four concurrent readers and process memory measurement. Run the test against **the exact CI-signed release/test APKs** on `emulator-5580`:

```sh
adb -s emulator-5580 install -r Pocket-Agent-x86_64.apk
adb -s emulator-5580 install -r Pocket-Agent-tests.apk
adb -s emulator-5580 shell am instrument -w -e class dev.pocket.agent.MemoryDeviceTest dev.pocket.agent.test/androidx.test.runner.AndroidJUnitRunner
```

This test creates/deletes an isolated `memory-instrumentation.db` and does not modify existing user memory. Also run `dev.pocket.agent.MemoryLoopDeviceTest`: this invokes the real scripted Pi tool loop to save a synthetic preference, verifies the native SQLite entry, and recalls it on the next main turn. The synthetic record is deleted after the test.

`MemoryStore` uses a separate DB file so no schema changes to `StateStore` or operation receipts are necessary.
