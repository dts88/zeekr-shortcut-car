# Recording source groups

Requires the shared exterior-source/mapping foundation. The four recording groups are independent: Surround; dedicated road Front + Rear; Cabin (existing two cabin entries); Manual. Existing enabled choices remain in place; new roles start off. Turning a group on requires assigned IDs and rejects duplicates among enabled sources. Mapping is the same picker/storage as Camera sources, and cancelling does not enable a group.

Road/manual feeds are whole-frame sources with their own stable file suffixes. They are never cut from Surround or given Surround fisheye correction. Existing Surround/cabin/legacy suffixes remain compatible. Playback catalogs prioritize Surround where available and also discover road/cabin/manual-only sessions, with companion files deduplicated for protection, sharing and deletion. Photos include the new source identities.

The default main preview follows enabled recording sources, using a retained texture per source. A separate preview-selection contribution can override that display choice without changing recording. Opening the editor or changing only enabled groups preserves existing preview, photo, recording and lane tuning.

Tests cover all 64 enabled-source combinations, profile round trips, old/new catalogs and separate drives, native group combinations, Cancel, saved-quality preservation, and decoding generated road-only clips with unique companion accounting. Camera/encoder concurrency and every resolution combination remain vehicle checks.
