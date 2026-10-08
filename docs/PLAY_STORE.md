# Play Store Listing Kit

## Copy (en-US)
**Title (30):** StudioOne: Music Studio
**Short (80):** Record, mix & master songs, beats and podcasts — with real-time collaboration.
**Full description:** see `fastlane/metadata/android/en-US/full_description.txt`
(template below).

```
Turn your phone into a full music studio.

RECORD & PRODUCE
• Low-latency multitrack recording (under 10 ms on supported devices)
• Unlimited-model track counts, punch-in, loop record, count-in
• Piano roll, step sequencer, drum pads, arpeggiator, MPE support
• 25+ studio effects: EQ, compressors, reverbs, delays, amp sims
• Virtual instruments: synths, sampler, drum kits, keys & more
• Master bus with LUFS loudness metering and spectrum analyzer

COLLABORATE & SHARE
• Real-time co-editing with comments on the timeline
• Share links with roles, remix forks, stem packs for collaborators
• Publish to the StudioOne feed or export WAV/MP3/FLAC/AAC

MADE FOR EVERYONE
• Templates for beats, songs, podcasts and live sessions
• TalkBack support, color-blind palettes, large touch targets
• Works offline — your music syncs when you're back

Start free. Upgrade to StudioOne Pro for unlimited projects, premium
sounds, lossless export and 20 GB of cloud storage.
```

## Graphic assets (specs)
* Icon 512×512 (adaptive: cyan waveform glyph on `#0E1116`)
* Feature graphic 1024×500 — "full studio in your pocket" hero shot
* Phone screenshots ×8: 1) arranger w/ waveform clips 2) mixer + LUFS meters
  3) piano roll 4) drum machine pads 5) FX rack 6) loop browser 7) collab
  comments 8) export sheet. Tablet ×4 + foldable ×2 required by listing.
* Promo video 30 s: record → edit → drop loop → publish (no copyrighted music;
  use the Starter Pack).

## Content rating questionnaire (answers)
Music creation app; UGC = yes (audio posts/comments) → moderation: block/report
mechanisms (roadmap fn) documented; ads = yes (free tier); IAP = yes;
sharing = yes. Expected IARC: **Teen** (UGC sharing) — adjust if ads network
changes.

## Data safety form (maps to docs/PRIVACY_POLICY.md §data-map)
Collected: account id/email (auth), audio files (user content, encrypted in
transit, deletable), diagnostics (consent-gated), purchase records.
Not sold. Not shared except processors (Supabase/Firebase/Play).
