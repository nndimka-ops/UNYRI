using System;

namespace CocoAdmin
{
    // A remote API login never grants control of the Docker stack on this PC.
    // Only explicit, known loopback endpoints can expose local maintenance.
    internal static class AdminServerTarget
    {
        public static bool AllowsLocalMaintenance(string baseUrl)
        {
            Uri uri;
            if (!Uri.TryCreate(baseUrl, UriKind.Absolute, out uri) || !uri.IsLoopback ||
                !string.IsNullOrEmpty(uri.UserInfo) || !string.IsNullOrEmpty(uri.Query) ||
                !string.IsNullOrEmpty(uri.Fragment) || uri.AbsolutePath != "/") return false;
            return (uri.Scheme == Uri.UriSchemeHttp && uri.Port == 8080) ||
                (uri.Scheme == Uri.UriSchemeHttps && uri.Port == 8443);
        }

        public static string Display(string baseUrl)
        {
            Uri uri;
            return Uri.TryCreate(baseUrl, UriKind.Absolute, out uri)
                ? uri.GetLeftPart(UriPartial.Authority) : "Сервер не выбран";
        }
    }
}
