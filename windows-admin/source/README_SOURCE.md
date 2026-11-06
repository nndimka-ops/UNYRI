# UNYRI Admin Console 0.3.16 source

This asset contains the exact 37 selected source/build/license inputs for the
matching unsigned Windows binary, including all 25 compiled C# files. It also
contains this guide, a release binding and a file manifest. It includes no
optional local-stack/AI helper scripts, credentials, runtime logs or user stores.

Use 64-bit Windows and .NET Framework 4.8 with its C# compiler. From the extracted
source root run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File desktop\Build-CocoAdmin.ps1
```

The unchanged restore helper obtains Bouncy Castle 2.7.0 via its fixed public
HTTPS NuGet URL and verifies package/DLL hashes before use. An offline build
requires the exact pinned package first:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File desktop\Restore-Dependencies.ps1 -PackagePath C:\Downloads\BouncyCastle.Cryptography.2.7.0.nupkg
```

Keep the generated EXE, config and BouncyCastle.Cryptography.dll together.
No administrator login is needed to compile. This packaging task did not
recompile the application or certify bit-identical compiler reproduction;
its 37 selected inputs are byte-exact to the actual build inputs and notices.
The five assembly metadata attributes do not rename the existing persistence
namespace.

The binary contains optional local controls. Their six PowerShell helpers,
AI observer, matching development Compose/TLS/env setup and external tools are
not provided here. Use the public self-hosting guide separately; neither this
asset nor the current public server/iOS checkout is a complete Windows local
control installation. See RELEASE_SOURCE_BINDING.json for the precise scope.

Application source is AGPL-3.0 (LICENSE/COPYRIGHT). Bouncy Castle is an unmodified
MIT dependency with additional Apache notices under third_party/bouncycastle.
The upstream repository/package/version/hash recipe is retained there.
