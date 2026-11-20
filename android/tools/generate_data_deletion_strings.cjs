// Local-only source for the data-deletion strings. No translation service is contacted.
const fs = require('node:fs');
const path = require('node:path');
const keys = ['auth_account_deleted','auth_account_suspended','auth_session_ended','privacy_prevent_screenshots','admin_delete_account_forever','admin_delete_account_detail','admin_delete_account_number','deleted_account'];
const locales = {
  '': ['Account deleted','Account suspended by the server administrator','Session ended','Prevent screenshots','Delete account permanently','Erases the account and its messages on the server and phones.','Enter the public number to confirm','Deleted account'],
  ru: ['Аккаунт удалён','Аккаунт заблокирован администратором сервера','Сеанс завершён','Запретить снимки экрана','Удалить аккаунт навсегда','Удаляет аккаунт и его сообщения на сервере и на телефонах.','Введите публичный номер для подтверждения','Удалённый аккаунт'],
  uk: ['Акаунт видалено','Акаунт заблоковано адміністратором сервера','Сеанс завершено','Заборонити знімки екрана','Видалити акаунт назавжди','Видаляє акаунт і його повідомлення на сервері та телефонах.','Введіть публічний номер для підтвердження','Видалений акаунт'],
  ar: ['تم حذف الحساب','تم حظر الحساب بواسطة مسؤول الخادم','انتهت الجلسة','منع لقطات الشاشة','حذف الحساب نهائيًا','يحذف الحساب ورسائله من الخادم والهواتف.','أدخل الرقم العام للتأكيد','حساب محذوف'],
  bg: ['Акаунтът е изтрит','Акаунтът е блокиран от администратора на сървъра','Сесията приключи','Забрана на екранни снимки','Изтриване на акаунта завинаги','Изтрива акаунта и съобщенията му от сървъра и телефоните.','Въведете публичния номер за потвърждение','Изтрит акаунт'],
  cs: ['Účet byl smazán','Účet byl zablokován správcem serveru','Relace skončila','Zakázat snímky obrazovky','Trvale smazat účet','Smaže účet a jeho zprávy na serveru i telefonech.','Pro potvrzení zadejte veřejné číslo','Smazaný účet'],
  de: ['Konto gelöscht','Konto vom Serveradministrator gesperrt','Sitzung beendet','Screenshots verhindern','Konto endgültig löschen','Löscht das Konto und seine Nachrichten auf dem Server und den Telefonen.','Zur Bestätigung die öffentliche Nummer eingeben','Gelöschtes Konto'],
  el: ['Ο λογαριασμός διαγράφηκε','Ο λογαριασμός αποκλείστηκε από τον διαχειριστή του διακομιστή','Η συνεδρία έληξε','Απαγόρευση στιγμιότυπων οθόνης','Οριστική διαγραφή λογαριασμού','Διαγράφει τον λογαριασμό και τα μηνύματά του στον διακομιστή και στα τηλέφωνα.','Εισαγάγετε τον δημόσιο αριθμό για επιβεβαίωση','Διαγραμμένος λογαριασμός'],
  es: ['Cuenta eliminada','Cuenta bloqueada por el administrador del servidor','Sesión finalizada','Impedir capturas de pantalla','Eliminar cuenta permanentemente','Elimina la cuenta y sus mensajes del servidor y los teléfonos.','Introduce el número público para confirmar','Cuenta eliminada'],
  fr: ['Compte supprimé','Compte bloqué par l’administrateur du serveur','Session terminée','Interdire les captures d’écran','Supprimer définitivement le compte','Supprime le compte et ses messages sur le serveur et les téléphones.','Saisissez le numéro public pour confirmer','Compte supprimé'],
  hu: ['Fiók törölve','A fiókot a szerver rendszergazdája letiltotta','A munkamenet véget ért','Képernyőképek tiltása','Fiók végleges törlése','Törli a fiókot és üzeneteit a szerverről és a telefonokról.','A megerősítéshez adja meg a nyilvános számot','Törölt fiók'],
  it: ['Account eliminato','Account bloccato dall’amministratore del server','Sessione terminata','Impedisci screenshot','Elimina account definitivamente','Elimina l’account e i suoi messaggi dal server e dai telefoni.','Inserisci il numero pubblico per confermare','Account eliminato'],
  ja: ['アカウントが削除されました','サーバー管理者がアカウントを停止しました','セッションが終了しました','スクリーンショットを禁止','アカウントを完全に削除','サーバーと端末上のアカウントおよびメッセージを削除します。','確認のため公開番号を入力','削除されたアカウント'],
  ko: ['계정이 삭제되었습니다','서버 관리자가 계정을 차단했습니다','세션이 종료되었습니다','스크린샷 금지','계정 영구 삭제','서버와 휴대전화에서 계정 및 메시지를 삭제합니다.','확인을 위해 공개 번호를 입력하세요','삭제된 계정'],
  nl: ['Account verwijderd','Account geblokkeerd door de serverbeheerder','Sessie beëindigd','Schermafbeeldingen blokkeren','Account definitief verwijderen','Verwijdert het account en de berichten op de server en telefoons.','Voer het openbare nummer in ter bevestiging','Verwijderd account'],
  pl: ['Konto usunięte','Konto zablokowane przez administratora serwera','Sesja zakończona','Blokuj zrzuty ekranu','Usuń konto na zawsze','Usuwa konto i jego wiadomości z serwera i telefonów.','Wpisz numer publiczny, aby potwierdzić','Usunięte konto'],
  pt: ['Conta eliminada','Conta bloqueada pelo administrador do servidor','Sessão terminada','Impedir capturas de ecrã','Eliminar conta permanentemente','Elimina a conta e as suas mensagens do servidor e dos telemóveis.','Introduza o número público para confirmar','Conta eliminada'],
  ro: ['Cont șters','Cont blocat de administratorul serverului','Sesiune încheiată','Interzice capturile de ecran','Șterge contul definitiv','Șterge contul și mesajele sale de pe server și telefoane.','Introdu numărul public pentru confirmare','Cont șters'],
  tr: ['Hesap silindi','Hesap sunucu yöneticisi tarafından engellendi','Oturum sona erdi','Ekran görüntülerini engelle','Hesabı kalıcı olarak sil','Hesabı ve mesajlarını sunucudan ve telefonlardan siler.','Onaylamak için genel numarayı girin','Silinmiş hesap'],
  'zh-rCN': ['账号已删除','账号已被服务器管理员封禁','会话已结束','禁止截屏','永久删除账号','删除服务器和手机上的账号及其消息。','输入公开号码以确认','已删除的账号'],
};
for (const [locale, values] of Object.entries(locales)) {
  if (values.length !== keys.length) throw Error(`Missing translation: ${locale}`);
  const xml = '<resources>\n' + keys.map((key,i) => `    <string name="${key}">${values[i].replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll("'", "\\'")}</string>`).join('\n') + '\n</resources>\n';
  fs.writeFileSync(path.join(__dirname, '../app/src/main/res', `values${locale ? '-'+locale : ''}`, 'data_deletion.xml'), xml);
}
console.log(`Wrote ${keys.length} deletion strings for ${Object.keys(locales).length} locales`);
