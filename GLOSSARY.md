# OpenChamber PTT Voice Cockpit

Push-to-talk voice orchestration between a mobile operator wearing Bluetooth audio gear or a smartwatch and remote OpenChamber coding sessions.

## Session & Turn Lifecycle

**Arm Epoch**:
A continuous period during which the operator has armed push-to-talk for hands-free listening and capture on a bound session.
_Avoid_: Connection session, service binding

**Auto-Speak Watermark**:
The boundary message in a session separating historical replies that existed before the current Arm Epoch from newly completed replies eligible for automatic readback.
_Avoid_: Sync flag, unread cursor

**Reply Cursor**:
The durable per-session bookmark of the latest assistant reply the operator has heard or marked read.
_Avoid_: Scroll position, desktop read state

**Prompt Turn Settlement**:
The outcome of reconciling an operator-submitted voice prompt against session history to distinguish an still-running turn, transient persistence lag, a silent completion, or a reply ready to speak.
_Avoid_: Idle event, turn completion flag

**Staged Draft**:
A durable, editable per-session prompt assembled from one or more voice bursts or an undone Quick Turn before submission to the session.
_Avoid_: Pending transcript, scratch buffer

**Quick Turn**:
A single-utterance voice prompt that enters a brief undo grace window before being routed or sent to the bound session.
_Avoid_: Instant send, raw dictation

## Speech & Interruption

**Audible Commitment**:
The point at which an assistant reply has played aloud long enough that an intentional User Barge-In treats the reply as heard rather than unplayed.
_Avoid_: Playback started, synthesis complete

**User Barge-In**:
An explicit operator gesture stopping active speech playback and pausing automatic readback until a newer reply arrives or manual readback is requested.
_Avoid_: SCO drop, audio focus loss, cancellation

**Transport Interruption**:
An involuntary loss of the playback audio route or system audio focus that halts active speech and pauses automatic readback without marking the interrupted reply as heard.
_Avoid_: Barge-in, user stop

**Voice Role**:
The persona category assigned to spoken output—distinguishing the remote Main Agent, the local Cockpit Operator, and short Vocal Cues.
_Avoid_: Speaker channel, voice slot

## Capture & Hardware Surfaces

**Capture Transport**:
The bidirectional microphone routing regime active strictly while recording an utterance—either the default device input, a pinned Bluetooth voice link, or a per-utterance local Telecom call.
_Avoid_: Persistent Bluetooth pin, playback route

**Playback Focus**:
Transient media audio ownership acquired strictly while speaking replies or draft previews so output stays on stereo media routing without opening a microphone link.
_Avoid_: SCO pin, call mode

**Recording Stop Surface**:
An ephemeral hardware or wearable control—such as screen-off phone volume keys, headset call hangup, or a smartwatch notification action—active strictly while an utterance is recording.
_Avoid_: Global hotkey, persistent ongoing notification
