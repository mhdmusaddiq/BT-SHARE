# Member 2 — Three-phone static relay experiment R1/R2

This independent `RelayDemoActivity` is in the existing Android `:app` module, with a second launcher icon **BT-SHARE Relay Demo**. Your old successful B0 text and two-phone PCM audio test entry point stays unchanged.

## Setup exactly

1. All 3 phones install the same APK and pair A↔B and B↔C in Android Settings.
2. Phone C: open **BT-SHARE Relay Demo** → `C — LISTEN AS RECEIVER`.
3. Phone B: select paired C → `B — RELAY: CONNECT TO C + LISTEN FOR A`.
4. Phone A: select paired B → `A — HOST: CONNECT TO B`.
5. On A send text, confirm C receives and A gets **end-to-end ACK via B**.
6. On A Record 5s Voice, Play Local, SEND AUDIO. C should complete `160000` bytes / `40` chunks and play through its speaker. B logs forwarding.
7. Copy all 3 logs, write measured evidence in `evidence/bluetooth/RELAY_R1_R2_TEST_EVIDENCE.md`.

This is STATIC two-hop RFCOMM routing with PCM clip playback, not automatic mesh, Opus, live audio, or auto-failover. No testing on 3 real phones was performed when this file was authored. The new v2 UUID/wire protocol is NOT interoperable with B0 socket sessions; open the new Relay Demo on all three phones.
