# UNYRI Admin Console 0.3.16 alpha

The [source](source/README_SOURCE.md) includes the exact 40 packaged source/build/
license/metadata files corresponding to the unsigned Windows alpha. Use 64-bit
Windows and .NET Framework 4.8. From `windows-admin/source`, the retained build
entry point is `desktop/Build-CocoAdmin.ps1`; the legacy filename is intentional.
The pinned Bouncy Castle restore/build recipe and notices are included.

The portable ZIP is a separately packaged release asset, rather than EXE/DLLs
in this Git tree. [Release contents](RELEASE_CONTENTS.json) records the exact
source/portable filenames, sizes and SHA-256 values. Keep its EXE, config and
Bouncy Castle DLL together. No administrator login is needed to compile.

This alpha is packaged for remote administration over HTTPS. Optional local
start/stop/backup/restore/reset/HTTPS and AI controls require helpers and a matching
local setup that are not included in the portable package or this source subset.
They are not qualified by this package. See the [server guide](../docs/SELF_HOSTING.md).
The package does not claim a fresh owner login or live moderation/recovery test.

Application source retains AGPL-3.0 and the unmodified dependency retains its
own license/notices. No credentials, DPAPI stores or server configuration secrets
are included.
