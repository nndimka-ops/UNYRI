# Local modifications

- First-party UNYRI 0.3.15/build15: APNs/PushKit client lifecycle, the accepted
  chat-composer type boundary hotfix, ledger relocation protection, localization
  and iPad orientation metadata/runtime mask. Packaging changes no app code.
- Signal wrapper: 125 files, 124 byte-identical to Signal v0.86.5; the sole
  Package.swift change and full upstream source are retained under
  `third_party/libsignal`.
- LiveKit Swift: exact upstream base 43c1c0870e51eedbfd95b20b1b5c43a767886786.
  The actual 346-file local package has 1 byte-exact file, 335 files with only
  CRLF/LF differences, 7 files with content changes, and 3 local provenance/patch
  files. The full comparison and normalized content patch are under
  `third_party/upstream-source`. This classification preserves every local byte;
  it does not silently normalize the shipped source. The original historical
  `COCO-E2EE.patch` name and durable/wire compatibility literals remain intact.
- Packaging: root build/notices/provenance documents and pinned upstream source
  archives. SOURCE_SELECTION.json lists every exact production input and every
  excluded QA/historical file. Excluded fixtures are not missing app code, and
  their private runtime evidence is not represented as public test execution.
