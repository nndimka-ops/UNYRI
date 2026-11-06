using System;
using System.Text.RegularExpressions;

namespace CocoAdmin
{
    internal static class AdminConsoleLink
    {
        public static Uri Build(string baseUrl, string handoff)
        {
            Uri origin;
            if (!Uri.TryCreate(baseUrl, UriKind.Absolute, out origin) || origin.Scheme != Uri.UriSchemeHttps ||
                !string.IsNullOrEmpty(origin.UserInfo) || origin.AbsolutePath != "/" ||
                !string.IsNullOrEmpty(origin.Query) || !string.IsNullOrEmpty(origin.Fragment))
                throw new InvalidOperationException("Веб-админка открывается только по HTTPS выбранного сервера.");
            if (handoff == null || !Regex.IsMatch(handoff, "\\A[A-Za-z0-9_-]{43}\\z"))
                throw new InvalidOperationException("Сервер не выдал корректный одноразовый вход. Повторите попытку.");
            // The fragment is not sent in HTTP requests or Referer headers.
            // No URL supplied by the server can change this trusted origin.
            return new Uri(origin, "/administration#handoff=" + handoff);
        }
    }
}
