using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using ScriptureMemory.Models;

namespace ScriptureMemory.Services;

/// <summary>Persists collections and settings to %LOCALAPPDATA%\ScriptureMemory\data.json.</summary>
public static class DataStore
{
    private static readonly JsonSerializerOptions JsonOptions = new() { WriteIndented = true };

    public static string Folder { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "ScriptureMemory");

    private static string FilePath => Path.Combine(Folder, "data.json");

    public static AppData Load()
    {
        try
        {
            if (File.Exists(FilePath))
                return JsonSerializer.Deserialize<AppData>(File.ReadAllText(FilePath), JsonOptions) ?? new AppData();
        }
        catch (Exception)
        {
            // Keep the unreadable file around rather than silently overwriting the user's data.
            try { File.Copy(FilePath, FilePath + $".corrupt-{DateTime.Now:yyyyMMddHHmmss}", true); } catch { }
        }
        return new AppData();
    }

    public static void Save(AppData data)
    {
        Directory.CreateDirectory(Folder);
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(data, JsonOptions));
        File.Move(tmp, FilePath, overwrite: true);
    }

    public static string GetApiKey(AppData data)
    {
        if (string.IsNullOrEmpty(data.EsvApiKeyProtected)) return "";
        try
        {
            var bytes = ProtectedData.Unprotect(Convert.FromBase64String(data.EsvApiKeyProtected), null, DataProtectionScope.CurrentUser);
            return Encoding.UTF8.GetString(bytes);
        }
        catch (Exception)
        {
            return "";
        }
    }

    public static void SetApiKey(AppData data, string key)
    {
        key = key.Trim();
        data.EsvApiKeyProtected = key.Length == 0
            ? null
            : Convert.ToBase64String(ProtectedData.Protect(Encoding.UTF8.GetBytes(key), null, DataProtectionScope.CurrentUser));
    }
}
