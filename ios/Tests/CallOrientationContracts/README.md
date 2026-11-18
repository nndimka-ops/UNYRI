Pure production-policy contracts, with no iOS SDK or network dependency (CallModels.swift needs the history-archive and API model files listed below; CI runs the same command). Run from the repository root on a Mac:

```sh
swiftc ios/UNYRI/Support/PerformanceDiagnostics.swift ios/UNYRI/Support/AppLanguage.swift ios/UNYRI/DirectTransfer/DirectFileHTTPGate.swift ios/UNYRI/Networking/APIClient.swift ios/Tests/Support/DeviceInfo.swift ios/UNYRI/Models/AuthModels.swift ios/UNYRI/Support/HistoryArchive.swift ios/UNYRI/Calls/CallModels.swift ios/UNYRI/Calls/CallDeviceOrientation.swift ios/Tests/CallOrientationContracts/main.swift -o /tmp/call-orientation-contracts
/tmp/call-orientation-contracts
python3 ios/Tests/CallOrientationContracts/run.py
```

The UIKit/CoreMotion implementation is conditionally excluded from the first macOS target. It exercises production hysteresis, both camera tables, tag selection (including immediate physical tags with a portrait-only mask and upside-down phones under landscape UI), compensation, fit/fill and layout. Safe-area proposals include SE 667x375, 6.1-inch 734x372 and Pro Max 814x409. Every interface/tag pair must give the inner renderer the camera picture's aspect while the outer tile follows the interface.

The Python runner compiles six production slices with Foundation-only boundary doubles: scalar layout with minimal non-Equatable geometry; scene lease/portrait exit and coalesced leave requests; the actual off-main sampler, capture leases and one-shot 0.6 s scheduler; the presentation gate with a sheet/alert above the cover; the morph completion callback; and scene masks/controller invalidation. It checks all three iPhone plists and relevant UI wiring (mini-window fill, room compensation, camera-switch ordering, no portrait sheet GeometryReader, no repeating sensor timer or expand polling). It never disables or changes the portable audit's checks.

`python3 ios/Tests/CallOrientationContracts/run.py --generate-only` runs wiring checks and emits all six Swift fixtures without invoking Swift. This is useful on the PC, and **does not establish compilation or contract execution**. CI uses the default mode and must compile and execute every fixture with Swift 6 strict concurrency.

UIKit naming conversion, window/appearance callbacks, rotation lock, actual camera switching, renderer continuity, mirror handedness, pixel identity and CPU still require the simulator/device gates in [IOS_ORIENT_ROUND3_REPORT.md](../../../IOS_ORIENT_ROUND3_REPORT.md).
