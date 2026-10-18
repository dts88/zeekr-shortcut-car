# Independent preview layout

Requires the shared exterior-source/mapping foundation (#52), and can be reviewed without the recording-group UI. Recording → Preview layout and the editor button use one saved, confirmed choice. Follow recording sources remains the default; named layouts include Surround, dedicated Front + Rear, each dedicated road feed, Cabin and Surround + Front + Rear. Cancel retains the previous choice.

The pipeline shape is the union of recording and preview needs, plus the retained Surround source for overlays. Recording/photo eligibility and visible-preview eligibility are separate scheduler facts. Hidden recorders continue to be requested; a preview-only camera does not become a photo or recording source. Closing/backgrounding preview withdraws that demand through the existing scheduler, retaining factory priority and screen-off rules. No camera ID is inferred from its number or substituted with a Surround crop.

Choosing a layout does not save/normalize a profile. Existing quality, image geometry, enabled groups and explicit source choices remain unchanged. New choices take effect on pipeline reopen. This contribution does not establish hardware concurrency, road orientation, 7X mapping, HUD compatibility or calibration.

Validation includes all 64 recording combinations across seven preview modes, scheduler preview/photo/record isolation, and five native API 32 tests for the picker, Cancel, persistence, visible layout construction and retained recording selections. Native tests use controlled emulator IDs. A debug-only microphone declaration lets the existing upstream MainActivity startup gate proceed in that test fixture; it is absent from release builds. Open PR #28 removes that unrelated gate.
