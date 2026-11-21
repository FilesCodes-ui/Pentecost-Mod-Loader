using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;

namespace Pentecost.Manager;

public static class DevAccess
{
    const string Owner = "808323d1022140ccb69bf8bcd0d16112";
    static readonly TimeSpan Recheck = TimeSpan.FromMinutes(10);

    static X509Certificate2? publicCert;
    static DateTime checkedAt = DateTime.MinValue;
    static bool held;

    static X509Certificate2? PublicCert()
    {
        if (publicCert != null) return publicCert;
        try
        {
            using var s = typeof(DevAccess).Assembly.GetManifestResourceStream("dev-key.cer");
            if (s == null) return null;
            using var ms = new MemoryStream();
            s.CopyTo(ms);
            publicCert = X509CertificateLoader.LoadCertificate(ms.ToArray());
        }
        catch (Exception ex) { AppLog.Write("dev key public half: " + ex.Message); }
        return publicCert;
    }

    public static bool Held()
    {
        if (DateTime.UtcNow - checkedAt < Recheck) return held;
        checkedAt = DateTime.UtcNow;
        held = false;
        var pub = PublicCert();
        if (pub == null) return false;
        try
        {
            using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
            store.Open(OpenFlags.ReadOnly | OpenFlags.OpenExistingOnly);
            var want = pub.GetPublicKey();
            foreach (var c in store.Certificates)
            {
                if (!c.GetPublicKey().AsSpan().SequenceEqual(want) || !c.HasPrivateKey) continue;
                using var priv = c.GetRSAPrivateKey();
                using var check = pub.GetRSAPublicKey();
                if (priv == null || check == null) break;
                var challenge = RandomNumberGenerator.GetBytes(32);
                var sig = priv.SignData(challenge, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                held = check.VerifyData(challenge, sig, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                break;
            }
        }
        catch (Exception ex) { AppLog.Write("dev key: " + ex.Message); }
        return held;
    }

    public static byte[]? Sign(byte[] data)
    {
        var pub = PublicCert();
        if (pub == null) return null;
        try
        {
            using var store = new X509Store(StoreName.My, StoreLocation.CurrentUser);
            store.Open(OpenFlags.ReadOnly | OpenFlags.OpenExistingOnly);
            var want = pub.GetPublicKey();
            foreach (var c in store.Certificates)
            {
                if (!c.GetPublicKey().AsSpan().SequenceEqual(want) || !c.HasPrivateKey) continue;
                using var priv = c.GetRSAPrivateKey();
                return priv?.SignData(data, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
            }
        }
        catch (Exception ex) { AppLog.Write("dev key sign: " + ex.Message); }
        return null;
    }

    public static bool Unlocked =>
        string.Equals(Paths.AccountUuid()?.Replace("-", ""), Owner, StringComparison.OrdinalIgnoreCase) && Held();
}
