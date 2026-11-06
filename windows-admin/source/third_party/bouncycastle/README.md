# Bouncy Castle for .NET

The Windows administration console uses the unmodified
`lib/net461/BouncyCastle.Cryptography.dll` from the official NuGet package
**BouncyCastle.Cryptography 2.7.0**. The pinned package and DLL digests are in
[`PROVENANCE.json`](PROVENANCE.json); the restore script verifies both.

The package's original [`LICENSE.md`](LICENSE.md) is preserved verbatim.
Bouncy Castle remains under its own MIT license; the UNYRI AGPL license does
not replace it. Bouncy Castle also documents Apache-licensed modified BZIP2
code. The complete [Apache License 2.0](APACHE-2.0.txt) is included alongside
the package license. This directory covers Bouncy Castle, not an inventory of
all mobile or server dependencies.

Upstream sources and licensing:

- [Official NuGet package](https://www.nuget.org/packages/BouncyCastle.Cryptography/2.7.0)
- [Bouncy Castle C# source](https://github.com/bcgit/bc-csharp)
- [Bouncy Castle license information](https://www.bouncycastle.org/about/license/)
