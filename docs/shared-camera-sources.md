# Shared camera-source mapping

Recording → Camera sources and Developer → Camera mapping share the same page and storage. Each role has three distinct choices: Automatic (no explicit preference), Unassigned (empty assignment), or a retained Camera2 ID. Reset clears explicit source intent only, after confirmation. It retains recording switches, quality, lane geometry and detected/automatic information.

Active legacy developer overrides migrate once. Inactive developer overrides stay inactive; removing a new choice cannot resurrect an old override. A temporarily hidden explicit ID remains assigned and is passed to the existing scheduler, rather than silently shifting another source into its place.

Surround detection and existing cabin ordering remain upstream's defaults. Dedicated road Front/Rear and Manual roles start disabled and have no guessed automatic IDs. They are backend foundations for the recording-group and preview-selector follow-ups. This change does not claim that an enumerated camera is physically a front/rear/cabin camera, that every combination can open concurrently, or that a numeric 009 mapping applies to a 7X.

Initialized camera membership is published as an immutable snapshot. Availability and contention checks inspect that complete inventory, including exterior/manual sources, and the snapshot is cleared on release. Duplicate IDs create only one camera instance; the picker rejects collisions with active assignments.

Mappings and enabled-source changes apply on pipeline reopen. No mapping action interrupts an active recording. Vehicle-specific exports and physical camera verification remain separate research tools.

Validation: JVM migration, explicit/Unassigned/temporarily absent assignment and pipeline-shape regressions; native picker Cancel/Automatic/reset/recreation, preference preservation, complete inventory ownership and release cleanup. Native tests run on a disposable API 32 emulator and do not establish hardware concurrency or vehicle compatibility.
