# RKStudio — UI/UX refinement, 26 September 2026

## Product goal
Create a reference MP4 for each audio slice: captured movement plus the matching master audio. The product should make choosing the next slice, recording, reviewing sync, and retrieving the export unambiguous.

## Findings in the previous implementation
- Unmodified Material dark palette; weak visual hierarchy and no consistent studio identity.
- Project cards lacked search; each slice repeated several full-width actions.
- Slice duration remained editable after capture, so existing take indices could point at different audio ranges.
- Import performed file copying and metadata work on the UI thread; failures could leave incomplete database rows.
- Review competed with a permanently expanded global-sync form.
- Export success was only a toast; there was no direct open/share handoff.
- Export state belonged to the screen and could disappear on rotation.
- Recording duration/audio began before CameraX confirmed capture start.
- Cancellation, permission denial, and background interruption needed explicit handling.

## Implemented
- Warm charcoal, lime accent, consistent typography, rounded surfaces and restrained borders.
- Searchable project cards with progress and written status.
- Scrollable creation form, meaningful audio filename, duration presets, sticky primary action and required-input state.
- Immutable slice partition in the queue; next-unrecorded action and All / Pending / Recorded filters.
- Explicit camera permission explanation and Settings recovery.
- Camera-first recording view with FIT_CENTER framing, countdown, timer, cancellation and recording-start timing.
- Review-first layout with global sync in a bottom sheet, 50 ms adjustments, reset and clear project-wide scope.
- Export progress, persistent visible errors, open/share actions and project-specific filenames.
- Import work moved off the main thread with rollback on failure.
- Playback stops on background; capture cancellation rejects stale completion callbacks.
- ViewModel export state survives activity recreation. This is not process-death recovery.
- APK version 1.1.0, versionCode 2; existing database schema and application ID preserved.

## Validation
- Android debug build via GitHub Actions.
- Instrumented UI scenarios at 360 dp width and 130% font size: empty home and creation, populated projects and filtered queue, review/sync, permission explanation.
- Generated screenshots are actual Compose UI from an Android emulator.
- Physical camera, speaker/headphone latency, device rotation during active capture, export interruption, and real downstream AI ingestion still require device acceptance testing.
- Touch controls use Material semantics and minimum 48 dp targets; typography uses sp. Reference: https://developer.android.com/develop/ui/compose/accessibility/api-defaults

## Remaining release gates
P0:
1. Release signing, target-SDK/store-policy review, privacy declarations and distribution setup.
2. Export recovery after process death; durable export state and retry/cleanup for interrupted output.
3. Physical-device media QA: first/middle/last slice, repeated retakes, low storage, background/rotation, permissions, headphones and audio-video alignment.
4. TalkBack, 200% text, landscape, tablets and display-cutout verification.

P1:
1. Real audio waveform with precise scrub/loop controls. Never substitute decorative bars for audio data.
2. Persistent export history tied to take ID and sync settings, with stale-export indication.
3. Project rename/delete/archive, storage usage and safe cleanup.
4. Take history and explicit active-take selection; current behavior uses latest take.
5. Thumbnail previews and optional batch export with queue/retry.

Do not claim universal Flow/Seedance compatibility or “best app of 2026” from UI refinement or compilation alone. Final acceptance is a usable reference MP4 with correct music, range, duration, framing and synchronization.
