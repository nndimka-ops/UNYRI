//
//  LibSignalProbe.swift
//  UNYRI
//
//  Stage 3B-E2E-B compile/link probe.
//
//  The sole purpose of this file is to prove that the UNYRI iOS target can
//  compile and link against the official LibSignalClient module. No Signal
//  crypto logic lives here yet.
//

import LibSignalClient

/// Compile/link probe only. Instantiating crypto objects is intentionally
/// avoided in this stage.
enum LibSignalProbe {
    /// The name of the imported module, proving the import resolved.
    static let moduleName = "LibSignalClient"
}
