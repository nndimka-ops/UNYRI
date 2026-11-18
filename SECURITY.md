English · [Русский](#русский) · [Українська](#українська)

# Security policy

UNYRI is open source on purpose: anyone can read how it protects messages, and anyone can check that it does. Security never depends on the code being secret. It depends on end-to-end encryption on the phones, on the apps not trusting the server more than necessary, and on fixing problems quickly.

## Reporting a vulnerability

- Please report privately through GitHub: the **Security** tab → **Report a vulnerability**. Do not open a public issue for a vulnerability.
- Describe what you found, how to reproduce it, the version (app build or server release) and what an attacker could do with it.
- We confirm receipt within 3 days, tell you our assessment within 7 days, and aim to ship a fix for critical problems within 14 days. We credit you in the release notes if you want.

## Rules for research

- Test only against your own server (the self-hosting guide shows how to run one) and your own accounts and devices.
- Do not access other people's data, do not degrade services for others (no denial-of-service), and do not use social engineering.
- Good-faith research that follows these rules is welcome; we will not take action against you for it.

## Scope

In scope: the server in `server/`, the iOS and Android apps, the self-hosting scripts and the release tooling. Out of scope: servers run by other people (their operators are responsible for them), and issues that need a compromised phone.

---

## Русский

UNYRI открыт специально: любой может прочитать, как он защищает сообщения, и проверить это. Защита никогда не держится на секретности кода. Она держится на сквозном шифровании на телефонах, на том, что приложения не доверяют серверу больше необходимого, и на быстрых исправлениях.

**Как сообщить об уязвимости:** только закрыто через GitHub — вкладка **Security** → **Report a vulnerability**. Не создавайте открытый issue. Опишите, что нашли, как повторить, версию (сборка приложения или выпуск сервера) и что может сделать злоумышленник. Подтвердим получение за 3 дня, оценку дадим за 7 дней, критические проблемы стараемся исправить за 14 дней. По желанию укажем вас в описании выпуска.

**Правила:** проверяйте только свой сервер (как его поднять — в инструкции) и свои аккаунты и устройства; не трогайте чужие данные, не мешайте работе сервисов (никаких DoS), без социальной инженерии. Добросовестные исследования по этим правилам приветствуются, и претензий к вам не будет.

**Область:** сервер в `server/`, приложения iOS и Android, скрипты своего сервера и инструменты выпуска. Вне области: серверы других людей (за них отвечают их владельцы) и проблемы, для которых нужен уже взломанный телефон.

## Українська

UNYRI відкритий навмисно: будь-хто може прочитати, як він захищає повідомлення, і перевірити це. Захист ніколи не тримається на секретності коду. Він тримається на наскрізному шифруванні на телефонах, на тому, що застосунки не довіряють серверу більше потрібного, і на швидких виправленнях.

**Як повідомити про вразливість:** лише закрито через GitHub — вкладка **Security** → **Report a vulnerability**. Не створюйте відкритий issue. Опишіть, що знайшли, як відтворити, версію (збірка застосунку чи випуск сервера) і що може зробити зловмисник. Підтвердимо отримання за 3 дні, оцінку надамо за 7 днів, критичні проблеми намагаємося виправити за 14 днів. За бажанням зазначимо вас в описі випуску.

**Правила:** перевіряйте лише свій сервер (як його підняти — в інструкції) та свої акаунти й пристрої; не чіпайте чужі дані, не заважайте роботі сервісів (жодних DoS), без соціальної інженерії. Сумлінні дослідження за цими правилами вітаються, і претензій до вас не буде.

**Межі:** сервер у `server/`, застосунки iOS і Android, скрипти свого сервера та інструменти випуску. Поза межами: сервери інших людей (за них відповідають їхні власники) і проблеми, для яких потрібен уже зламаний телефон.
