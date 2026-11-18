Run the Foundation-only routing contract on a Mac or a host with Swift installed:

```sh
python3 ios/Tests/ChatComposerPasteContracts/run.py
```

It covers advertised image/movie/video/plain-text types, conforming subtypes,
GIF preference over still representations, mixed text/media/URL providers,
owned-original storage plans, source limits, stable clipboard identities and
deterministic cancellation of concurrent/duplicate import tasks. It does not compile UIKit.
The same contract is included in `.github/workflows/ios-build.yml`.
`WorkspaceContracts` additionally exercises real file/store recovery: release a
provider copy, clear the tray journal, reopen the account, run the launch sweep,
and retain the queued GIF/original until its persisted batch is retired.

Mac/device verification (required before release):

1. Build the iOS target with the iOS 16 deployment target. Check both
   `paste(itemProviders:)` and `paste(_:)` on `ChatComposerInputView`.
2. On an iPhone, copy HEIC/JPEG/PNG from Photos and an animated GIF from Safari
   or Files. Paste through both the keyboard's “Photo — Paste from UNYRI”
   suggestion and the edit menu. Check the tray above the input, the loading
   placeholder and the GIF file route; the received GIF must still animate.
3. Copy a video. Check standard/high/original quality choices and an input
   video between 100 and 500 MB (normal video preparation), plus a source over
   500 MB (existing size feedback). Original files retain the existing direct
   transfer offer/limits. Paste must never send automatically.
4. Paste clipboard items containing media, a URL-only item and plain text, including one
   provider that advertises both representations. Check insertion at a caret
   and over a selection, plain-text styling, undo and saved draft updates.
   Pure text, including copied rich text, must behave as before.
5. Repeat paste before and after loading: one tray item per clipboard media
   item, with the existing duplicate feedback. Fill 9 slots and paste 2 new
   items, then try a full tray: the existing all-or-nothing limit message must
   appear and the text portion must still paste. Pending imports count as slots.
6. Trigger paste during voice/video recording or startup, editing, a blocked
   chat, draft restoration and automatic direct sending. Check visible existing
   error/feedback presentation, with no silent media loss. A normal ongoing
   send may accept Add items for the next explicit Send, as before.
7. Paste a slow video twice, then clear the tray or switch accounts: its original
   provider operation must cancel even after the duplicate completes. Queue a
   pasted original/GIF for direct sending, switch accounts and return: the source
   must still exist and resume. Remove a loading tile, switch chats, and repeat with
   a provider that fails to load. No late tile resurrection or foreign-account
   file adoption; temporary files are released and other imported items remain.
8. Add a caption and tap Send: tray and text use the existing send flow. With
   VoiceOver and Russian selected, confirm “Добавлено: N”; also check English
   and Ukrainian feedback.
