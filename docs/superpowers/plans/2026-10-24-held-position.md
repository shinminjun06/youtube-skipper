# Held-position follow-up

Goal: fix the failure shown in323831.png after gesture dispatch, without claiming the returned-to-tray image shows the actual held state.

Evidence: the screenshot reads an empty board, vertical red3 and two red L3 pieces correctly. The failure message covers both PICKED and ALIGN, so the screenshot does not establish which search failed. Current PICKED search forbids any origin above board bottom-0.15cell, while staging finger height implicitly assumes lift around2cells. The native fixture currently uses only1.85cells.

- [x] Reproduce a3.4-cell lifted image from this screenshot with known geometry; assert localization across the board area. Broaden search using strong per-cell changed-center evidence, retaining stale-board and bridge rejections.
- [x] Capture the before/last-held frame and geometry on alignment timeout in memory; distinguish pickup and final alignment in status. Allow user-initiated ZIP export through Android document picker with no added permissions or uploads.
- [ ] Test actual Android controller at1.85,3.4 and4.2cell lift; reject no-pickup and verify diagnostics export. Preserve all earlier native tests.
- [ ] Review and build0.5.2 on the named branch, inspect test evidence, source/hash/signature and provide APK.

Constraints: branch only, no new permissions, no blind release on uncertain localization, no automatic external screenshot transfer. Actual game/device interaction remains unverified; high-lift reproduction is a tested hypothesis, not a proven account of the user's held frame.

Review extension: do not choose between spatially distinct strong matches. Verify a continuous0.7cell pointer probe against before/after candidate centroids and reject ambiguity. The diagnostic includes the pre-probe observation. The native fixture adds a stationary fresh same-shape preview and verifies frozen marker pixels after cancellation.
