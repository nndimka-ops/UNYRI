# UNYRI for Android

UNYRI is in active development. This directory contains the native Android app.
Package names, existing storage and cryptographic formats retain their technical identifiers so existing installations remain compatible.

The approved app icon is documented in [the brand approval](../design/branding/unyri/APPROVED.md).
To reproduce only the Android PNG assets, install Python 3 and Pillow 12.3.0, then run from the repository root:

```powershell
python design/branding/unyri/export_android_icons.py
```

The exporter verifies the approved master hash and writes only its listed Android PNG resources.
It does not build, install, sign or publish the app. Existing Gradle commands and artifact names are unchanged.
Adaptive layers keep the complete glyph within the 66dp safe circle; themed and notification assets use white RGB with transparent alpha.
