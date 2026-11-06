import SwiftUI
import PhotosUI
import ImageIO
import UniformTypeIdentifiers

struct AppLanguagePicker: View {
    @AppStorage(UnyriL10n.key) private var language = "system"
    var body: some View {
        Picker("Language", selection: Binding(get: { language }, set: { next in
            guard next != language else { return }
            language = next
            Task { @MainActor in UnyriHaptics.selection() }
        })) {
            Text("System default").tag("system")
            Text("English").tag("en")
            Text("Русский").tag("ru")
            Text("Українська").tag("uk")
        }
        .pickerStyle(.menu)
        .frame(minHeight: 44)
    }
}

struct AlphaAboutView: View {
    @Environment(\.unyriAppColorStyle) private var colorStyle
    var body: some View {
        UnyriSettingsPage("About UNYRI") {
            HStack(spacing: 14) {
                Image("UnyriBrandMark").resizable().renderingMode(.original).scaledToFit()
                    .unyriDecorativeForeground(Color(hex: 0x7BC9EC)).frame(width: 48, height: 48)
                    .background(RoundedRectangle(cornerRadius: 15).fill(colorStyle.decorative(Color(hex: 0x7BC9EC)).opacity(0.12)))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 4) {
                    Text("UNYRI").font(.title2.weight(.semibold)).foregroundColor(UnyriColors.primaryText)
                    Text(AppVersion.display(shortVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
                        build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String))
                        .font(.caption).foregroundColor(UnyriColors.secondaryText)
                }
                Spacer(minLength: 0)
            }.padding(.horizontal, 4)
            UnyriSettingsGroup("Alpha test") {
                VStack(alignment: .leading, spacing: 10) {
                    Text("alpha_description").foregroundColor(UnyriColors.primaryText)
                    Text("alpha_warning").foregroundColor(UnyriColors.secondaryText)
                }.font(.subheadline).fixedSize(horizontal: false, vertical: true).padding(14)
            }
            UnyriSettingsGroup("Your server") {
                Text("alpha_server_description").font(.subheadline).foregroundColor(UnyriColors.secondaryText)
                    .fixedSize(horizontal: false, vertical: true).padding(14).frame(maxWidth: .infinity, alignment: .leading)
            }
            UnyriSettingsGroup {
                NavigationLink { AlphaTermsView() } label: {
                    UnyriSettingsRow(title: UnyriLegalContent.title("terms"), icon: "doc.text", tint: UnyriColors.secondaryText)
                }
                UnyriSettingsDivider()
                NavigationLink { UnyriLegalView(documentID: "privacy") } label: {
                    UnyriSettingsRow(title: UnyriLegalContent.title("privacy"), icon: "hand.raised", tint: UnyriColors.secondaryText)
                }
                UnyriSettingsDivider()
                NavigationLink { UnyriLegalView(documentID: "support") } label: {
                    UnyriSettingsRow(title: UnyriLegalContent.title("support"), icon: "questionmark.circle", tint: UnyriColors.secondaryText)
                }
            }
        }
    }
}
struct AlphaTermsView: View {
    var body: some View { UnyriLegalView(documentID: "terms") }
}

private struct FeedbackScreenshot: Encodable {
    let mimeType = "image/jpeg"
    let dataBase64: String
}
private struct FeedbackRequest: Encodable {
    let id: String
    let description: String
    let platform = "ios"
    let appVersion: String
    let osVersion: String
    let screenshots: [FeedbackScreenshot]
    let performance: PerformanceSnapshot?
}
private struct FeedbackReceipt: Decodable { let id: String }

struct BugReportView: View {
    private let proposedCrash: UnyriCrashRecord?
    @EnvironmentObject private var auth: AuthRepository
    @State private var description = ""
    @State private var screenshots: [Data] = []
    @State private var consent = false
    @State private var picker = false
    @State private var busy = false
    @State private var reportID = UUID().uuidString.lowercased()
    @State private var receipt: String?
    @State private var error = false
    @State private var rateLimited = false
    @State private var picking = false
    @State private var validation: String?
    @State private var attachCrash = true
    @AppStorage(PerformanceRecorder.enabledKey) private var performanceEnabled = false
    @State private var attachPerformance = true
    @State private var capturedPerformance: PerformanceSnapshot?
    @State private var performanceFrozen = false
    @FocusState private var editingDescription: Bool
    @MainActor init(crash: UnyriCrashRecord? = nil) {
        let proposed = crash ?? UnyriCrashDiagnostics.shared.pending
        proposedCrash = proposed
        _description = State(initialValue: proposed == nil ? "" : UnyriL10n.string("The app closed unexpectedly."))
    }
    private var reportDescription: String {
        let text = description.trimmingCharacters(in: .whitespacesAndNewlines)
        guard attachCrash, let proposedCrash else { return text }
        return text + "\n\n" + proposedCrash.reportDescription
    }
    private func userChoice(_ value: Binding<Bool>) -> Binding<Bool> {
        Binding(get: { value.wrappedValue }, set: { next in
            guard next != value.wrappedValue else { return }
            value.wrappedValue = next
            Task { @MainActor in UnyriHaptics.selection() }
        })
    }
    var body: some View {
        UnyriSettingsPage("Report a problem") {
            if let proposedCrash {
                UnyriSettingsGroup("Crash diagnostics") {
                    VStack(alignment: .leading, spacing: 10) {
                        Toggle("Include crash diagnostics", isOn: userChoice($attachCrash))
                        Text("Only the app version and technical stack addresses are included. Messages, files and passwords are not collected.")
                            .font(.caption).foregroundStyle(UnyriColors.secondaryText)
                        DisclosureGroup("Review diagnostics") {
                            Text(proposedCrash.reportDescription).font(.system(size: 11, design: .monospaced))
                                .textSelection(.enabled).frame(maxWidth: .infinity, alignment: .leading)
                        }.font(.subheadline)
                    }.padding(14)
                }
            }
            UnyriSettingsGroup("Describe the problem") {
                VStack(alignment: .leading, spacing: 10) {
                    TextEditor(text: $description).frame(minHeight: 144).focused($editingDescription)
                        .scrollContentBackground(.hidden).padding(8)
                        .background(RoundedRectangle(cornerRadius: 12).fill(Color(hex: 0x242426)))
                        .overlay(RoundedRectangle(cornerRadius: 12).stroke(UnyriColors.outlineSubtle, lineWidth: 1))
                        .onChange(of: description) { value in if value.count > 5000 { description = String(value.prefix(5000)) } }
                    Text("Include the steps, expected result and what happened.").font(.caption).foregroundColor(UnyriColors.secondaryText)
                }.padding(14)
            }
            UnyriSettingsGroup("Screenshots") {
                ForEach(Array(screenshots.enumerated()), id: \.offset) { index, data in
                    HStack(spacing: 12) {
                        if let image = UIImage(data: data) { Image(uiImage: image).resizable().scaledToFit().frame(height: 110).clipShape(RoundedRectangle(cornerRadius: 10)) }
                        Spacer(minLength: 8)
                        Button("Remove", role: .destructive) { screenshots.remove(at: index); reportID = UUID().uuidString.lowercased(); receipt = nil }
                            .frame(minWidth: 44, minHeight: 44)
                    }.padding(14)
                    UnyriSettingsDivider()
                }
                Button { picker = true } label: {
                    UnyriSettingsRow(title: "Choose screenshots (up to 3)", icon: "photo.badge.plus", tint: Color(hex: 0x7BC9EC))
                }.disabled(picking)
                if picking { ProgressView().padding(14) }
            }
            UnyriSettingsGroup("Who receives the report") {
                VStack(alignment: .leading, spacing: 12) {
                    Label { Text(auth.authenticatedServerURL.absoluteString).textSelection(.enabled).fixedSize(horizontal: false, vertical: true) }
                        icon: { Image(systemName: "server.rack").unyriDecorativeForeground(Color(hex: 0x7BC9EC)).accessibilityHidden(true) }
                        .font(.caption).foregroundColor(UnyriColors.secondaryText)
                    Text("feedback_disclosure").font(.footnote).foregroundColor(UnyriColors.secondaryText)
                    Toggle("I agree to send this report and these screenshots", isOn: userChoice($consent)).font(.subheadline).unyriDecorativeTint(Color(hex: 0x83D9B7))
                }.padding(14)
            }
            if performanceEnabled {
                UnyriSettingsGroup("Performance diagnostics") {
                    VStack(alignment: .leading, spacing: 10) {
                        Toggle("Attach performance diagnostics", isOn: userChoice($attachPerformance)).font(.subheadline).unyriDecorativeTint(Color(hex: 0x7BC9EC))
                        Text("Timings and technical events only. No messages, passwords or keys.").font(.caption).foregroundColor(UnyriColors.secondaryText)
                    }.padding(14)
                }
            }
            if error {
                UnyriSettingsGroup {
                    Text(UnyriL10n.string(rateLimited ? "Report limit reached. Your report is still here. Try again later." : "Could not send. Your report is still here. Check the connection and try again."))
                        .font(.footnote).foregroundColor(UnyriColors.warning).padding(14).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if let receipt {
                UnyriSettingsGroup("Report received") {
                    Text(receipt).font(.footnote).foregroundColor(UnyriColors.success).textSelection(.enabled).padding(14).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            if busy { ProgressView().frame(maxWidth: .infinity) }
        }
        .safeAreaInset(edge: .bottom) {
            VStack(spacing: 8) {
                if let validation { Text(UnyriL10n.string(validation)).font(.footnote).foregroundColor(UnyriColors.warning) }
                if receipt != nil { Text("Report received").font(.footnote).foregroundColor(UnyriColors.success) }
                Button("Send report") {
                    editingDescription = false
                    if description.trimmingCharacters(in: .whitespacesAndNewlines).count < 5 {
                        validation = "Describe the problem in at least 5 characters."
                    } else if reportDescription.unicodeScalars.count > 5000 {
                        validation = "Shorten the description or turn off crash diagnostics. The complete report can contain up to 5000 characters."
                    } else if !consent {
                        validation = "Turn on consent above to send your report and screenshots."
                    } else { validation = nil; send() }
                }.unyriProminentAction().frame(maxWidth: .infinity)
                    .disabled(busy || picking || receipt != nil)
                if picking { Text("Preparing screenshots…").font(.footnote).foregroundColor(UnyriColors.secondaryText) }
            }.padding(.horizontal, 16).padding(.vertical, 12).frame(maxWidth: .infinity)
                .background(Color(hex: 0x1B1B1D))
                .overlay(alignment: .top) { Rectangle().fill(UnyriColors.outlineSubtle).frame(height: 0.5) }
        }

        .scrollDismissesKeyboard(.interactively)
        .disabled(busy)
        .toolbar(.hidden, for: .tabBar)
        .preference(key: UnyriRootDockHiddenKey.self, value: true)
        .unyriScreenBackground().navigationTitle("Report a problem")
        .performanceScreen(.report)
        .onChange(of: reportID) { _ in performanceFrozen = false; capturedPerformance = nil }
        .onChange(of: attachPerformance) { _ in reportID = UUID().uuidString.lowercased(); receipt = nil }
        .onChange(of: attachCrash) { _ in reportID = UUID().uuidString.lowercased(); receipt = nil }
        .onChange(of: performanceEnabled) { _ in reportID = UUID().uuidString.lowercased(); receipt = nil }
        .onChange(of: description) { _ in receipt = nil; reportID = UUID().uuidString.lowercased() }
        .sheet(isPresented: $picker) {
            FeedbackPhotoPicker { data, failed in
                screenshots = data; error = failed; picking = false; receipt = nil; reportID = UUID().uuidString.lowercased()
            } onLoading: { picking = true; picker = false }
        }
    }
    private func send() {
        guard !busy, !picking, consent, receipt == nil,
              description.trimmingCharacters(in: .whitespacesAndNewlines).count >= 5, reportDescription.unicodeScalars.count <= 5000 else { return }
        busy = true; error = false; rateLimited = false
        let generation = auth.sessionGeneration
        let server = auth.authenticatedServerURL
        if !performanceFrozen {
            capturedPerformance = performanceEnabled && attachPerformance ? PerformanceRecorder.shared.snapshot() : nil
            performanceFrozen = true
        }
        let crashID = attachCrash ? proposedCrash?.id : nil
        let crashOwner = server.absoluteString + "|" + (auth.currentUser?.id ?? "")
        let request = FeedbackRequest(id: reportID, description: reportDescription, appVersion: AppVersion.display(shortVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String, build: Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String), osVersion: UIDevice.current.systemVersion, screenshots: screenshots.map { FeedbackScreenshot(dataBase64: $0.base64EncodedString()) }, performance: capturedPerformance)
        Task {
            defer { busy = false }
            do {
                let token = try await auth.currentAccessToken()
                guard generation == auth.sessionGeneration, server == auth.authenticatedServerURL else { throw CancellationError() }
                let result = try await APIClient(baseURL: server, preventRedirects: true).post("/feedback", accessToken: token, body: request, as: FeedbackReceipt.self)
                guard generation == auth.sessionGeneration else { return }
                receipt = result.id
                if let crashID { await UnyriCrashDiagnostics.shared.submitted(crashID, owner: crashOwner) }
            } catch {
                if case APIError.http(429) = error { rateLimited = true }
                if case APIError.server(let code, _) = error { rateLimited = code == "rate_limited" }
                self.error = true
            }
        }
    }
}

// A provider may never complete (for example an interrupted iCloud download).
// The deadline and provider race through one gate so neither a stuck Send button
// nor a double continuation resume is possible.
final class FeedbackImportGate: @unchecked Sendable {
    private let lock = NSLock()
    private var completion: ((Data?) -> Void)?
    private var progress: Progress?
    init(_ completion: @escaping (Data?) -> Void) { self.completion = completion }
    func attach(_ progress: Progress) {
        lock.lock()
        let finished = completion == nil
        if !finished { self.progress = progress }
        lock.unlock()
        if finished { progress.cancel() }
    }
    func finish(_ data: Data?) {
        lock.lock()
        let callback = completion
        completion = nil
        let active = progress; progress = nil
        lock.unlock()
        guard let callback else { return }
        if data == nil { active?.cancel() }
        callback(data)
    }
}

/// Downsample from the provider's file URL, never load the original photo as Data.
private struct FeedbackPhotoPicker: UIViewControllerRepresentable {
    let onResult: ([Data], Bool) -> Void
    let onLoading: () -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onResult: onResult, onLoading: onLoading) }
    func makeUIViewController(context: Context) -> PHPickerViewController {
        var configuration = PHPickerConfiguration(); configuration.filter = .images; configuration.selectionLimit = 3
        let picker = PHPickerViewController(configuration: configuration); picker.delegate = context.coordinator; return picker
    }
    func updateUIViewController(_ controller: PHPickerViewController, context: Context) {}
    final class Coordinator: NSObject, PHPickerViewControllerDelegate {
        let onResult: ([Data], Bool) -> Void
        let onLoading: () -> Void
        init(onResult: @escaping ([Data], Bool) -> Void, onLoading: @escaping () -> Void) { self.onResult = onResult; self.onLoading = onLoading }
        func picker(_ picker: PHPickerViewController, didFinishPicking results: [PHPickerResult]) {
            guard !results.isEmpty else { picker.dismiss(animated: true); return }
            onLoading()
            Task { @MainActor in
                var images: [Data] = []; var failed = false
                for result in results {
                    let data: Data? = await withCheckedContinuation { continuation in
                        let gate = FeedbackImportGate { continuation.resume(returning: $0) }
                        DispatchQueue.main.asyncAfter(deadline: .now() + 30) { gate.finish(nil) }
                        let progress = result.itemProvider.loadFileRepresentation(forTypeIdentifier: UTType.image.identifier) { url, _ in
                            guard let url, let source = CGImageSourceCreateWithURL(url as CFURL, [kCGImageSourceShouldCache:false] as CFDictionary),
                                  let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways:true,kCGImageSourceCreateThumbnailWithTransform:true,kCGImageSourceThumbnailMaxPixelSize:1280] as CFDictionary),
                                  let data = UIImage(cgImage: cg).jpegData(compressionQuality: 0.7), data.count <= 1024 * 1024 else { gate.finish(nil); return }
                            gate.finish(data)
                        }
                        gate.attach(progress)
                    }
                    if let data { images.append(data) } else { failed = true }
                }
                onResult(images, failed)
            }
        }
    }
}

// Generated from the same reviewed CONTENT.ru/en.json as the hosted pages.
// Keep bundled text available before registration and while offline.
struct UnyriLegalSection { let title: String; let paragraphs: [String] }
struct UnyriLegalDocument { let title: String; let intro: String; let version: String; let sections: [UnyriLegalSection] }
enum UnyriLegalContent {
    static func title(_ id: String) -> String { document(id).title }
    static var agreement: String { UnyriL10n.language == "ru" ? "Я принимаю условия использования" : "I agree to the Terms of Use" }
    static var registrationNote: String { UnyriL10n.language == "ru" ? "До создания аккаунта прочитайте политику конфиденциальности. Диагностика включается отдельно." : "Read the Privacy Policy before creating an account. Diagnostics are controlled separately." }
    static var serverNote: String { UnyriL10n.language == "ru" ? "Правила и хранение на выбранном сервере определяет его оператор. Проверьте карточку сервера." : "The selected server's operator determines its rules and retention. Check its server card." }
    static var websiteLabel: String { UnyriL10n.language == "ru" ? "Открыть на сайте" : "Open on website" }
    static func document(_ id: String) -> UnyriLegalDocument {
        // RU and EN are reviewed; other language selections use the explicit English fallback.
        let selected = UnyriL10n.language == "ru" ? ru : en
        return selected[id] ?? selected["privacy"]!
    }
    private static let ru: [String: UnyriLegalDocument] = [
        "privacy": UnyriLegalDocument(title: "Конфиденциальность UNYRI", intro: "Здесь описано, какие данные обрабатывает UNYRI, зачем они нужны и как ими управлять. Сквозное шифрование содержимого переписки не означает, что сервер не получает никаких данных.", version: "Обновлено: 4 ноября 2026 года", sections: [
UnyriLegalSection(title: "Кто отвечает за данные", paragraphs: ["Издатель приложения UNYRI и оператор официального сервера UNYRI Cloud — Dmytro Novopolskyi. Контакт по приложению и конфиденциальности: bbdimka@gmail.com.", "Эта политика предназначена для приложения и официального сервера UNYRI Cloud. В UNYRI можно выбрать другой, в том числе собственный сервер. Его оператор отдельно определяет обработку серверных данных, правила и сроки хранения. До входа проверьте адрес и карточку выбранного сервера. Контакт издателя не заменяет контакт оператора стороннего сервера."]),
UnyriLegalSection(title: "Учётная запись и профиль", paragraphs: ["Сервер обрабатывает имя пользователя, идентификатор аккаунта, выбранное имя, описание и аватар, а также сведения об устройствах и сеансах, необходимые для входа, синхронизации и защиты аккаунта. Пароль проверяется с использованием защищённого хеша; его открытое значение не хранится в базе аккаунтов.", "Профиль, аватар, названия и описания комнат могут быть доступны другим людям в соответствии с функцией и её настройками. Не указывайте там сведения, которые не хотите раскрывать."]),
UnyriLegalSection(title: "Переписка, вложения и звонки", paragraphs: ["Содержимое поддерживаемой зашифрованной переписки и её вложений шифруется на устройстве перед отправкой. Устройства участников получают данные для расшифрования. Сервер хранит зашифрованные данные и сведения, необходимые для доставки и синхронизации, включая идентификаторы отправителя и получателя, время, состояние доставки, размер и связь вложения с сообщением.", "Для звонков и комнат сервер обрабатывает сигнализацию, идентификаторы участников, состояние участия и соединения. Сетевому серверу или ретранслятору доступны IP-адреса подключений и технические параметры трафика. При прямом соединении сетевой адрес может быть виден другому участнику. Не все сведения приложения зашифрованы сквозным шифрованием: например, публичный профиль и текст отправленной жалобы доступны оператору сервера.", "Если в вашей сборке доступна прямая передача файлов, сервер может участвовать в согласовании и хранить её состояние, даже когда сам файл передаётся между устройствами. Это не обеспечивает анонимность соединения."]),
UnyriLegalSection(title: "Диагностика, обращения и жалобы", paragraphs: ["При включённой диагностике на выбранный сервер могут отправляться технические коды операций и ошибок, сведения о приложении, ОС и устройстве, времени выполнения, памяти и работе интерфейса. Состав зависит от версии: некоторые установленные сборки также передают показатели свободного места. Эти сведения могут быть связаны с аккаунтом и устройством для разбора проблемы.", "В «Сообщить о проблеме» вы выбираете описание и вложения отчёта, например снимки экрана или диагностические материалы, которые разрешено приложить. Просмотрите их перед отправкой: на снимках могут быть личные данные. Техническая онлайн-диагностика при включённой настройке работает отдельно от ручного отчёта.", "Жалоба на собеседника передаёт выбранному серверу причину и введённое вами описание в читаемом виде. Вся переписка автоматически к жалобе не прикладывается. Письмо на bbdimka@gmail.com получает издатель через почтовую службу; встроенный отчёт получает оператор выбранного сервера."]),
UnyriLegalSection(title: "Зачем данные используются и кому доступны", paragraphs: ["Данные нужны для работы аккаунта, доставки и синхронизации, звонков, защиты от злоупотреблений, выполнения ваших настроек и разбора обращений. К серверным данным могут иметь доступ уполномоченные администраторы и поставщики инфраструктуры в объёме своей работы. Официальный сервер использует Scaleway; условия обработки и субподрядчики опубликованы на https://www.scaleway.com/en/contracts/ и https://www.scaleway.com/en/subprocessorlist/. Сторонний сервер может использовать других поставщиков и находиться в другой стране.", "Для уведомлений Android используется Google Firebase Cloud Messaging. Сервис получает идентификатор установки и токен доставки, а также технические сведения уведомления: например, идентификаторы чата, сообщения или звонка. Обычные push-сообщения не содержат открытый текст личной переписки; отдельное административное уведомление может содержать текст администратора. По правилам Firebase идентификатор установки хранится до запроса его удаления через API; после запроса удаление из рабочих и резервных систем может занять до 180 дней. Удаление аккаунта UNYRI сейчас не отправляет такой запрос на удаление Firebase ID. Подробнее: https://firebase.google.com/support/privacy и https://firebase.google.com/docs/projects/manage-installations.", "Письма на bbdimka@gmail.com обрабатываются почтовой службой Google по её условиям: https://policies.google.com/privacy и https://policies.google.com/technologies/retention. Разрешения камеры, микрофона и медиатеки обрабатывает ОС при использовании соответствующих функций. Получатель видит отправленные ему сведения и может сохранить собственную копию. Эта политика не обещает отсутствие сетевых метаданных или невозможность компрометации устройства либо сервера."]),
UnyriLegalSection(title: "Хранение и удаление", paragraphs: ["Аккаунт, история и активная зашифрованная резервная копия истории хранятся, пока вы их не удалите или не замените по правилам выбранного сервера. На официальном сервере дневные группы технической диагностики истекают примерно в пределах восьми суток, показатели производительности — в пределах суток. Ручные отчёты об ошибках очищаются после 30 суток при успешной работе фоновой задачи, которая проверяет их каждый час.", "У записей административного аудита в базе сейчас нет автоматического срока удаления: они остаются до удаления оператором. Технические журналы контейнеров ограничены размером: до трёх файлов примерно по 10 МБ на контейнер; это не фиксированный срок в днях. Журналы могут содержать идентификаторы аккаунта и устройства, сетевые и технические события. Обычный журнал HTTP-доступа Caddy сейчас выключен; это не отключает остальные журналы системы и провайдеров. У писем поддержки нет установленного приложением автоматического срока: они хранятся в почтовом ящике до удаления.", "Автоматическое резервное копирование инфраструктуры UNYRI в проверенной конфигурации сервера не настроено. Это отличается от зашифрованной резервной копии истории, которую включает пользователь, и не исключает технические записи или копии у инфраструктурных поставщиков. Сроки обработки у поставщиков определяются их условиями. Не следует считать удаление аккаунта мгновенным физическим стиранием каждой копии у всех поставщиков.", "Для удаления аккаунта откройте настройки аккаунта → «Удалить аккаунт», подтвердите пароль и действие. Доступ отзывается, аккаунт и связанная серверная история удаляются. Объекты вложений и аватаров ставятся в очередь очистки не ранее чем через 24 часа и после окончания действующих разрешений доступа; при ошибках очередь повторяет попытки. Медиа резервной копии очищаются отдельной очередью после окончания разрешений доступа. Прямые переписки удаляются у собеседников после синхронизации. Снимки экрана, экспорт и другие независимые копии у людей удалить таким способом нельзя. Удаление приложения само по себе не удаляет аккаунт."]),
UnyriLegalSection(title: "Ваш выбор и обращения", paragraphs: ["В настройках можно управлять доступными разрешениями, диагностикой, показом онлайна и отметок о прочтении, блокировками, кэшем и удалением аккаунта. Доступность отдельных настроек зависит от версии. Отключение диагностики прекращает последующую отправку по этой настройке, но не стирает ранее отправленные данные автоматически.", "Для вопросов о доступе, исправлении, переносимости или удалении данных и отзыва разрешения напишите на bbdimka@gmail.com; для стороннего сервера также обратитесь к его оператору. Возможности и обязательные сроки ответа зависят от применимого закона. Не отправляйте пароль, коды подтверждения, PIN, ключи шифрования или закрытые ключи. Существенные изменения этой политики публикуются с новой датой и должны быть доступны из приложения."])
]),
        "support": UnyriLegalDocument(title: "Поддержка UNYRI", intro: "По вопросам приложения, безопасности и конфиденциальности: bbdimka@gmail.com. Не отправляйте секреты аккаунта.", version: "Обновлено: 4 ноября 2026 года", sections: [
UnyriLegalSection(title: "Сообщить об ошибке", paragraphs: ["Откройте настройки → «Помощь и приложение» → «Сообщить о проблеме». Укажите, что делали, что ожидали и что произошло. Полезны версия и номер сборки, модель телефона, ОС и примерное время события. Скриншоты и диагностику прикладывайте только после просмотра.", "Встроенный отчёт идёт на выбранный сервер. Для письма издателю используйте bbdimka@gmail.com и укажите адрес сервера, если это необходимо для разбора. Не включайте в письмо историю переписки без необходимости."]),
UnyriLegalSection(title: "Нежелательный контакт и опасный контент", paragraphs: ["В меню чата выберите «Заблокировать собеседника» или «Пожаловаться на собеседника». В жалобе опишите нарушение без паролей и ключей. Описание получает оператор выбранного сервера в читаемом виде; личная переписка целиком автоматически не передаётся.", "Для обращения о публичном профиле или комнате укажите сервер и достаточный идентификатор профиля или комнаты в письме. Оператор рассматривает обращение и использует доступные меры ограничения или удаления. При непосредственной угрозе жизни обращайтесь в местные экстренные службы: UNYRI не является экстренной службой."]),
UnyriLegalSection(title: "Данные и удаление аккаунта", paragraphs: ["Удаление доступно внутри приложения: настройки аккаунта → «Удалить аккаунт». Требуется подтверждение намерения и пароля. Для обычного удаления писать в поддержку не нужно. Если вход недоступен, свяжитесь с оператором сервера; издателю можно написать на bbdimka@gmail.com. Независимые копии у других людей не удаляются.", "Перед удалением сохраните нужные данные доступными в вашей версии средствами. Не считайте перенос или резервную копию завершёнными, пока новое устройство не подтвердило восстановление нужной истории."]),
UnyriLegalSection(title: "Подключение и тестовые сборки", paragraphs: ["Проверьте адрес выбранного сервера и его карточку. Для собственного сервера доступность, правила и хранение определяет его оператор. Возможности уведомлений и работы в фоне зависят от ОС, разрешений и конкретной сборки; постоянная работа в фоне и доставка без интернета не гарантируются.", "Устанавливая тестовую сборку, прочитайте её ограничения и сохраните важные данные. Описание проблем по почте и встроенные отчёты помогают разбору, но подтверждённый срок ответа устанавливается оператором отдельно."])
]),
        "terms": UnyriLegalDocument(title: "Условия использования UNYRI", intro: "Эти условия относятся к приложению UNYRI издателя Dmytro Novopolskyi и официальному серверу UNYRI Cloud. Для другого выбранного сервера действуют также правила его оператора, показанные в карточке сервера.", version: "Обновлено: 4 ноября 2026 года", sections: [
UnyriLegalSection(title: "Аккаунт и доступ", paragraphs: ["Используйте приложение, если это разрешено применимым законом и возрастными ограничениями приложения. Не выдавайте себя за другого человека и не получайте доступ к чужим аккаунтам. Берегите пароль, устройства и ключи: не передавайте их поддержке и другим людям.", "Сохраняйте нужные вам копии истории и файлов. Возможности и ограничения тестовых версий могут меняться; приложение и сеть могут работать с ошибками. UNYRI не заменяет экстренную связь."]),
UnyriLegalSection(title: "Запрещённые действия", paragraphs: ["Нельзя распространять материалы сексуальной эксплуатации детей, интимные материалы без согласия, угрозы, травлю, целевую ненависть или дискриминацию, мошенничество, спам, вредоносные программы и иной незаконный контент. Нельзя нарушать чужую приватность и права на материалы или размещать открыто порнографические материалы в публичных профилях и списках комнат.", "Нельзя атаковать сервис или других людей, обходить блокировки, ограничения доступа и лимиты, собирать чужие данные без законного основания, нарушать работу сервера или использовать сервис для незаконных действий. Не публикуйте пароли, ключи и личные сведения других людей."]),
UnyriLegalSection(title: "Профили, комнаты и модерация", paragraphs: ["Вы отвечаете за материалы и сведения, которые отправляете и публикуете. Публикуйте только то, на что у вас есть необходимые права. Другие участники могут сохранить полученную копию. Не раскрывайте секреты в имени, описании, аватаре или названии комнаты.", "О нарушении можно сообщить через меню чата или контакт поддержки. Оператор выбранного сервера рассматривает обращения и может ограничить доступ, приостановить аккаунт и удалить доступные ему публичные материалы в рамках своих правил и функций сервиса. Решения по зашифрованной переписке не требуют автоматической передачи всей переписки оператору. Блокировка собеседника доступна в приложении."]),
UnyriLegalSection(title: "Данные, удаление и выбранный сервер", paragraphs: ["Обработка данных описана в политике конфиденциальности. Прочитайте её до создания аккаунта. Согласие с условиями не является автоматическим согласием на необязательную диагностику.", "Аккаунт можно удалить в настройках аккаунта с подтверждением. Удаление необратимо для серверной истории; физическая очистка объектов выполняется фоновой очередью. Удаление приложения не удаляет аккаунт, а независимые копии у собеседников могут сохраниться. Правила стороннего сервера и его возможности определяет его оператор."]),
UnyriLegalSection(title: "Изменения и контакт", paragraphs: ["Важные изменения условий публикуются с новой датой. Новые обязательства не должны скрываться в техническом обновлении. Если вы не согласны с условиями, прекратите использование и воспользуйтесь удалением аккаунта, сохранив нужные данные заранее.", "По приложению и официальному серверу: bbdimka@gmail.com. Эти условия не ограничивают права, которые нельзя исключить по применимому закону, и не обещают отсутствие ошибок, абсолютную анонимность или неуязвимость сервиса."])
])
    ]
    private static let en: [String: UnyriLegalDocument] = [
        "privacy": UnyriLegalDocument(title: "UNYRI Privacy Policy", intro: "This policy explains the data UNYRI processes, why it is needed, and the controls available to you. End-to-end encryption of conversation content does not mean that a server receives no data.", version: "Updated: 4 November 2026", sections: [
UnyriLegalSection(title: "Who is responsible", paragraphs: ["The UNYRI app publisher and official UNYRI Cloud operator is Dmytro Novopolskyi. Contact for the app and privacy: bbdimka@gmail.com.", "This policy is intended for the app and the official UNYRI Cloud server. UNYRI also lets you choose another server, including one you host yourself. That server's operator separately determines its server-side processing, rules and retention. Check the server address and server card before signing in. The publisher's contact does not replace a third-party server operator's contact."]),
UnyriLegalSection(title: "Account and profile", paragraphs: ["The server processes your username, account identifier, chosen display name, bio and avatar, together with device and session information needed for sign-in, synchronization and account security. Passwords are checked using a protected hash; the account database does not store the plain password.", "Profiles, avatars, room names and room descriptions may be visible to others according to the feature and its settings. Do not place information there that you do not wish to disclose."]),
UnyriLegalSection(title: "Conversations, attachments and calls", paragraphs: ["Content in supported encrypted conversations and their attachments is encrypted on the device before upload. Participating devices receive the information needed to decrypt it. The server stores encrypted data and information needed for delivery and synchronization, including sender and recipient identifiers, timing, delivery state, size, and the association between an attachment and its message.", "For calls and rooms, the server processes signaling, participant identifiers, participation and connection state. A network server or relay can see connecting IP addresses and technical traffic information. A direct connection may reveal a network address to the other participant. Not all app information is end-to-end encrypted: public profiles and submitted report descriptions, for example, are readable by the server operator.", "If your build provides direct file transfer, the server may coordinate it and store transfer state even when the file itself travels between devices. This does not make the connection anonymous."]),
UnyriLegalSection(title: "Diagnostics, support and abuse reports", paragraphs: ["When diagnostics are enabled, technical operation and error codes, app, OS and device information, timings, memory and interface performance information may be sent to the selected server. The fields depend on the version: some installed builds also send available-storage measurements. These records may be linked to your account and device to investigate an issue.", "In Report a problem, you choose the description and report attachments, such as screenshots or permitted diagnostic material. Review them before submission; screenshots may contain personal information. Enabled online technical diagnostics are separate from a manually submitted report.", "Reporting a contact sends the selected server a reason and the description you enter in readable form. Your whole conversation is not automatically attached. Email to bbdimka@gmail.com reaches the publisher through an email service; an in-app report reaches the operator of the selected server."]),
UnyriLegalSection(title: "Purpose and access", paragraphs: ["Data is used to operate accounts, deliver and synchronize content, provide calls, prevent abuse, apply settings and handle support. Authorized administrators and infrastructure providers may access server data as needed for their work. The official server uses Scaleway; its processing terms and subprocessors are published at https://www.scaleway.com/en/contracts/ and https://www.scaleway.com/en/subprocessorlist/. Other servers may use different providers and be located in other countries.", "Android notifications use Google Firebase Cloud Messaging. It receives an installation identifier and delivery token, together with notification routing information such as chat, message or call identifiers. Ordinary pushes do not contain plaintext private messages; a separate administrative notice may contain the administrator’s text. Under Firebase’s published rules, the installation identifier remains until its deletion is requested through the API; removal from live and backup systems may then take up to 180 days. UNYRI account deletion currently does not request deletion of that Firebase ID. See https://firebase.google.com/support/privacy and https://firebase.google.com/docs/projects/manage-installations.", "Email to bbdimka@gmail.com is handled by Google’s mail service under its terms: https://policies.google.com/privacy and https://policies.google.com/technologies/retention. Camera, microphone and photo-library permissions are handled by the OS when you use the relevant features. Recipients see the information you send them and can keep independent copies. This policy does not promise an absence of network metadata or that a device or server cannot be compromised."]),
UnyriLegalSection(title: "Retention and deletion", paragraphs: ["Account data, history and an active encrypted history backup remain until you delete or replace them under the selected server rules. On the official server, daily technical diagnostic groups expire within approximately eight days and performance samples within a day. Manual bug reports are cleaned up after 30 days when the background task succeeds; it checks them hourly.", "Administrative audit records in the database currently have no automatic expiry: they remain until removed by the operator. Container technical logs are limited by size, to three files of approximately 10 MB per container; this is not a fixed number of days. Logs may contain account and device identifiers, network and technical events. Caddy HTTP access logging is currently off; this does not disable other system or provider logs. The app sets no automatic expiry for support email; correspondence remains in the mailbox until removed.", "Automated infrastructure backup is not configured in the checked UNYRI server setup. This differs from a user-enabled encrypted history backup and does not rule out technical records or copies maintained by infrastructure providers. Provider processing periods follow their terms. Account deletion should not be understood as immediate physical erasure of every copy at every provider.", "To delete an account, open account settings → Delete account and confirm your password and choice. Access is revoked and the account and associated server history are removed. Attachment and avatar objects are queued for cleanup no earlier than 24 hours later and after existing access capabilities expire, with retries on failure. Backup media is cleaned up by a separate queue after access capabilities expire. Direct conversations disappear for other participants after synchronization. Screenshots, exports and other independent copies held by people cannot be removed this way. Uninstalling the app does not delete your account."]),
UnyriLegalSection(title: "Choices and requests", paragraphs: ["Settings provide available controls for permissions, diagnostics, presence and read receipts, blocked contacts, cache and account deletion. Individual options depend on the version. Turning diagnostics off stops future submission under that setting; it does not automatically erase records already submitted.", "For questions about access, correction, portability, deletion or withdrawal of permission, contact bbdimka@gmail.com; also contact the operator when using a third-party server. Available rights and required response periods depend on applicable law. Do not send passwords, confirmation codes, PINs, encryption keys or private keys. Material policy changes are published with a new date and must remain accessible from the app."])
]),
        "support": UnyriLegalDocument(title: "UNYRI Support", intro: "For app, security and privacy questions: bbdimka@gmail.com. Do not send account secrets.", version: "Updated: 4 November 2026", sections: [
UnyriLegalSection(title: "Report a problem", paragraphs: ["Open Settings → Help & app → Report a problem. Explain what you did, what you expected, and what happened. Useful details include version and build number, phone model, OS, and approximate event time. Review screenshots and diagnostics before attaching them.", "In-app reports go to the selected server. To email the publisher, use bbdimka@gmail.com and include the server address when needed to investigate. Do not include conversation history unnecessarily."]),
UnyriLegalSection(title: "Unwanted contact and harmful content", paragraphs: ["In the chat menu, choose Block contact or Report contact. Describe the issue without passwords or keys. The selected server's operator receives the description in readable form; your whole private conversation is not automatically sent.", "To report a public profile or room, include the server and enough information to identify the profile or room in an email. The operator reviews the request and uses available restriction or removal controls. For an immediate threat to life, contact local emergency services: UNYRI is not an emergency service."]),
UnyriLegalSection(title: "Data and account deletion", paragraphs: ["Account deletion is available in the app: account settings → Delete account. You confirm your intention and password. Normal deletion does not require contacting support. If sign-in is unavailable, contact the server operator; the publisher can be reached at bbdimka@gmail.com. Independent copies held by others are not removed.", "Save information you need before deletion using the tools available in your version. Do not consider a transfer or backup complete until the new device confirms that the history you need was restored."]),
UnyriLegalSection(title: "Connection and test builds", paragraphs: ["Check the selected server address and its server card. A self-hosted server's availability, rules and retention are determined by its operator. Notification and background behavior depend on the OS, permissions and build; uninterrupted background operation or delivery without internet is not guaranteed.", "Read a test build's limitations and preserve important information before installing it. Email and in-app reports help investigation; any guaranteed response period must be established separately by the operator."])
]),
        "terms": UnyriLegalDocument(title: "UNYRI Terms of Use", intro: "These terms apply to the UNYRI app published by Dmytro Novopolskyi and the official UNYRI Cloud server. Another selected server also has its operator's rules, shown in its server card.", version: "Updated: 4 November 2026", sections: [
UnyriLegalSection(title: "Account and access", paragraphs: ["Use the app only when permitted by applicable law and the app's age restrictions. Do not impersonate another person or access someone else's account. Protect your password, devices and keys; do not give them to support or other people.", "Keep copies of history and files that you need. Test features and limits may change; the app and network can encounter errors. UNYRI is not a substitute for emergency communication."]),
UnyriLegalSection(title: "Prohibited conduct", paragraphs: ["Do not distribute child sexual exploitation material, nonconsensual intimate material, threats, harassment, targeted hate or discrimination, scams, spam, malware, or other illegal content. Do not violate others' privacy or rights in content, or place overtly pornographic material in public profiles and room listings.", "Do not attack the service or other people, evade blocks, access controls or limits, collect others' data without a lawful basis, disrupt servers, or use the service for illegal activity. Do not publish other people's passwords, keys or private information."]),
UnyriLegalSection(title: "Profiles, rooms and moderation", paragraphs: ["You are responsible for what you send and publish. Share only material for which you have the necessary rights. Other participants may keep their own copy. Do not put secrets in a name, bio, avatar or room title.", "Violations can be reported through the chat menu or support contact. The selected server's operator reviews reports and may restrict access, suspend an account or remove public material available to it within its rules and service controls. Action on encrypted conversations does not require automatic disclosure of an entire conversation to the operator. Contact blocking is available in the app."]),
UnyriLegalSection(title: "Data, deletion and the selected server", paragraphs: ["Data processing is described in the Privacy Policy. Read it before creating an account. Agreeing to these terms is not automatic consent to optional diagnostics.", "You can delete your account in account settings after confirmation. Server history deletion is irreversible; physical object cleanup runs through a background queue. Uninstalling the app does not delete the account, and independent copies held by contacts may remain. A third-party server's rules and capabilities are determined by its operator."]),
UnyriLegalSection(title: "Changes and contact", paragraphs: ["Important changes to these terms are published with a new date. New obligations should not be hidden in a technical update. If you disagree with the terms, stop using the service and use account deletion after preserving information you need.", "For the app and official server: bbdimka@gmail.com. These terms do not limit rights that cannot be excluded under applicable law. They do not promise error-free operation, absolute anonymity or an invulnerable service."])
])
    ]
}
struct UnyriLegalView: View {
    let documentID: String
    var body: some View {
        let document = UnyriLegalContent.document(documentID)
        UnyriSettingsPage(document.title) {
            Text(verbatim: document.version).font(.caption).foregroundColor(UnyriColors.secondaryText).padding(.horizontal, 4)
            UnyriSettingsGroup {
                Text(verbatim: document.intro).font(.subheadline).foregroundColor(UnyriColors.primaryText)
                    .fixedSize(horizontal: false, vertical: true).padding(16).textSelection(.enabled)
            }
            ForEach(document.sections.indices, id: \.self) { index in
                let section = document.sections[index]
                UnyriSettingsGroup(section.title) {
                    VStack(alignment: .leading, spacing: 14) {
                        ForEach(section.paragraphs.indices, id: \.self) { i in Text(verbatim: section.paragraphs[i]) }
                    }.font(.subheadline).foregroundColor(UnyriColors.primaryText)
                        .fixedSize(horizontal: false, vertical: true).padding(16).textSelection(.enabled)
                }
            }
            UnyriSettingsGroup {
                if let url = URL(string: "https://8c86b74c-0d0f-4ee0-9b74-bbfcf06d3f9f.pub.instances.scw.cloud/" + documentID) {
                    Link(UnyriLegalContent.websiteLabel, destination: url).padding(14)
                }
                if let contact = URL(string: "mailto:bbdimka@gmail.com") {
                    Link("bbdimka@gmail.com", destination: contact).padding(14)
                }
            }
        }
    }
}
