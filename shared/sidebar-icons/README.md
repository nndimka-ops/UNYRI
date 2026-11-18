# Sidebar icon source snapshot

These fourteen SVGs are the actual output of `lucide.createIcons()` from the
official `lucide@1.17.0` UMD distribution. This matches the version supplied to
the approved `coco-sidebar-studio.html` visualization by its saved wrapper:
`runtime-logs/remaining-report-xiaomi-20261028/sidebar-studio-browser.html`.

`manifest.json` records each SVG hash, the pinned bundle URL/hash, and the
official license URL/hash. Source paths were not redrawn or substituted.
The editor's geometry is preserved: viewBox 24, stroke 1.7, round caps/joins.
The sole source-color normalization is `currentColor` to template black.

Generate offline with `node scripts/generate_sidebar_icons.cjs`; verify without
changing application assets with `node scripts/generate_sidebar_icons.cjs --check`.
The development tool needs Playwright and Chrome. Set `COCO_PLAYWRIGHT_PATH` and
`COCO_CHROME_PATH` if they are not at the bundled Windows runtime locations.
Initial extraction can be repeated with `--extract-from <official-1.17.0-umd.js>`;
the full library is not vendored and is not used by either mobile application.

Android receives fourteen 96×96 black-alpha PNGs in `drawable-nodpi`.
iOS receives fourteen template image sets with 48×48 @2x and 72×72 @3x PNGs.
Tint and display size are set by the native sidebar; there is no runtime SVG
parsing, network dependency, image filter, or animation in these assets.

The unmodified upstream ISC notice and the additional Feather MIT notice are in
[`third_party/lucide/LICENSE`](../../third_party/lucide/LICENSE). Both are bundled
in Android assets and the iOS `CocoSidebarLucideLicense` data asset.

Generation/verification hashes, dimensions, nonempty-alpha bounds and a visual
contact sheet are in `runtime-logs/sidebar-icons-20261028/`.
