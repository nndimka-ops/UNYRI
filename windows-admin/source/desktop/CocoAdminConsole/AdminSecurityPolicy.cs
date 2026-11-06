using System;
using System.Collections.Generic;

namespace CocoAdmin
{
    /// <summary>One policy vocabulary shared by login and the security editor.</summary>
    internal static class AdminSecurityPolicy
    {
        public const string Authenticator = "authenticator";
        public const string PaperKey = "paper_key";
        public const string Both = "both";

        public static bool IsKnown(string value)
        {
            return value == Authenticator || value == PaperKey || value == Both;
        }

        public static string RequireKnown(string value)
        {
            if (!IsKnown(value)) throw new InvalidOperationException("Сервер вернул неизвестный способ защиты. Обновите консоль; вход не был упрощён.");
            return value;
        }

        public static bool UsesAuthenticator(string value) { return RequireKnown(value) != PaperKey; }
        public static bool UsesPaperKey(string value) { return RequireKnown(value) != Authenticator; }

        public static string Label(string value)
        {
            if (value == Authenticator) return "Аутентификатор";
            if (value == PaperKey) return "Бумажный ключ";
            if (value == Both) return "Аутентификатор + бумажный ключ";
            return "Неизвестный способ защиты";
        }

        public static string Description(string value)
        {
            if (value == Authenticator) return "Пароль аккаунта и одноразовый код из приложения-аутентификатора. Бумажный ключ не нужен.";
            if (value == PaperKey) return "Пароль аккаунта и ваш сохранённый 32-символьный ключ. Код аутентификатора не нужен.";
            return "Пароль аккаунта, код аутентификатора и сохранённый бумажный ключ — при каждом новом входе.";
        }

        public static List<AdminPolicyOption> Options()
        {
            return new List<AdminPolicyOption>
            {
                new AdminPolicyOption(Authenticator),
                new AdminPolicyOption(PaperKey),
                new AdminPolicyOption(Both)
            };
        }
    }

    internal sealed class AdminPolicyOption
    {
        public readonly string Value;
        public AdminPolicyOption(string value) { Value = AdminSecurityPolicy.RequireKnown(value); }
        public override string ToString() { return AdminSecurityPolicy.Label(Value); }
    }

    internal sealed class AdminSetupResult
    {
        public string Policy;
        public string QrPng;
        public string Secret;
    }
}
