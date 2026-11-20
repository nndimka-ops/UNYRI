// Synthetic public test material only. Compile beside the unchanged C# port source.
using System;
using System.IO;
using System.Text;
using System.Security.Cryptography;
using System.Web.Script.Serialization;
using CocoAdmin;
internal static class DeriveCryptoVector {
    static void Main(string[] args) {
        const string prefix = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        var first = prefix.Substring(0,30);
        byte[] hash;
        using(var sha=SHA256.Create()) hash=sha.ComputeHash(Encoding.UTF8.GetBytes("COCO-ADMIN-CHECK-V1\0"+first));
        var paper=first+prefix[hash[0]>>3]+prefix[((hash[0]&7)<<2)|(hash[1]>>6)];
        var pub=CryptoAdmin.PublicKeyBase64(paper);
        var id=Guid.Empty.ToString();
        var message=CryptoAdmin.ProofMessage(id,id,id,id,"unlock",pub,"synthetic-nonce");
        File.WriteAllText(args[0],new JavaScriptSerializer().Serialize(new {
            paper=paper,seed_hex=BitConverter.ToString(CryptoAdmin.SeedFromPaperCode(paper)).Replace("-","").ToLowerInvariant(),
            public_key=pub,message=message,signature=CryptoAdmin.SignBase64(paper,message)
        }),new UTF8Encoding(false));
        Console.WriteLine("Synthetic C# vector derived successfully.");
    }
}
