package com.kooo.evcam.zeekr;

import java.util.*;

/** Preserve surround-led recordings and discover recordings made without surround. */
public final class RecordingCatalog {
    private RecordingCatalog() { }
    public static List<RecordingTimeline.Session> build(Map<String, List<RecordingTimeline.Source>> bySlot) {
        List<RecordingTimeline.Session> sessions = new ArrayList<>();
        LinkedHashSet<String> order = new LinkedHashSet<>(Arrays.asList("surround", "roadfront", "roadrear", "cabinfront", "cabinrear", "manual"));
        order.addAll(new TreeSet<>(bySlot.keySet()));
        for (String slot : order) {
            List<RecordingTimeline.Source> input = bySlot.get(slot);
            if (input == null) continue;
            List<RecordingTimeline.Source> uncovered = new ArrayList<>();
            for (RecordingTimeline.Source source : input) {
                boolean covered = false;
                for (RecordingTimeline.Session session : sessions) {
                    if (!sameDirectory(source.path, session.segments.get(0).path)) continue;
                    if (source.startEpochMs >= session.startEpochMs - RecordingTimeline.DEFAULT_MAX_GAP_MS
                            && source.startEpochMs + source.durationMs <= session.endEpochMs() + RecordingTimeline.DEFAULT_MAX_GAP_MS) {
                        covered = true; break;
                    }
                }
                if (!covered) uncovered.add(source);
            }
            Map<String, List<RecordingTimeline.Source>> directories = new LinkedHashMap<>();
            for (RecordingTimeline.Source source : uncovered)
                directories.computeIfAbsent(new java.io.File(source.path).getParent(), ignored -> new ArrayList<>()).add(source);
            for (List<RecordingTimeline.Source> directory : directories.values()) sessions.addAll(RecordingTimeline.build(directory));
        }
        sessions.sort(Comparator.comparingLong(s -> s.startEpochMs));
        return sessions;
    }
    /** Exact primary files belong to their own session; companions to the closest same-folder session. */
    public static List<LaneTrack> owned(List<RecordingTimeline.Source> sources, List<RecordingTimeline.Session> sessions, long gap) {
        List<List<RecordingTimeline.Source>> buckets = new ArrayList<>();
        Map<String, Integer> primary = new HashMap<>();
        for (int i = 0; i < sessions.size(); i++) {
            buckets.add(new ArrayList<>());
            for (RecordingTimeline.Segment segment : sessions.get(i).segments) primary.put(segment.path, i);
        }
        for (RecordingTimeline.Source source : sources) {
            Integer owner = primary.get(source.path);
            if (owner == null) {
                long best = Long.MIN_VALUE;
                for (int i = 0; i < sessions.size(); i++) {
                    RecordingTimeline.Session session = sessions.get(i);
                    if (!sameDirectory(source.path, session.segments.get(0).path)) continue;
                    long overlap = Math.min(source.startEpochMs + source.durationMs, session.endEpochMs())
                            - Math.max(source.startEpochMs, session.startEpochMs);
                    if (overlap < -gap) continue;
                    if (overlap > best) { best = overlap; owner = i; }
                }
            }
            if (owner != null) buckets.get(owner).add(source);
        }
        List<LaneTrack> out = new ArrayList<>(); for (List<RecordingTimeline.Source> bucket : buckets) out.add(LaneTrack.of(bucket));
        return out;
    }
    public static LaneTrack shown(List<RecordingTimeline.Source> sources, RecordingTimeline.Session session) {
        List<RecordingTimeline.Source> same = new ArrayList<>();
        for (RecordingTimeline.Source source : sources)
            if (sameDirectory(source.path, session.segments.get(0).path)) same.add(source);
        return LaneTrack.of(same).shownIn(session);
    }
    private static boolean sameDirectory(String a, String b) {
        return Objects.equals(new java.io.File(a).getParent(), new java.io.File(b).getParent());
    }
    public static String primarySlot(RecordingTimeline.Session session) {
        return session.segments.isEmpty() ? "surround" : com.kooo.evcam.camera.CameraSlots.canonical(
                RecordingTimeline.parseCameraSlot(new java.io.File(session.segments.get(0).path).getName()));
    }
}
