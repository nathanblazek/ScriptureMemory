using System.Net;
using System.Net.Http.Headers;
using System.Text.Json;

namespace ScriptureMemory.Services;

public class EsvException(string message) : Exception(message);

/// <summary>Minimal client for the ESV API text endpoint (https://api.esv.org/docs/passage-text/).</summary>
public static class EsvClient
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    private const string Options =
        "&include-passage-references=false" +
        "&include-verse-numbers=true" +
        "&include-first-verse-numbers=true" +
        "&include-footnotes=false" +
        "&include-footnote-body=false" +
        "&include-headings=false" +
        "&include-short-copyright=false" +
        "&include-copyright=false" +
        "&include-selahs=true" +
        "&indent-paragraphs=0" +
        "&indent-poetry=false" +
        "&indent-declares=0" +
        "&indent-psalm-doxology=0" +
        "&line-length=0";

    public static async Task<(string Canonical, string Text)> GetPassageAsync(string apiKey, string query, CancellationToken ct = default)
    {
        var url = "https://api.esv.org/v3/passage/text/?q=" + Uri.EscapeDataString(query) + Options;
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Authorization = new AuthenticationHeaderValue("Token", apiKey);

        HttpResponseMessage response;
        try
        {
            response = await Http.SendAsync(request, ct);
        }
        catch (HttpRequestException ex)
        {
            throw new EsvException("Couldn't reach the ESV API. Check your internet connection. (" + ex.Message + ")");
        }
        catch (TaskCanceledException)
        {
            throw new EsvException("The ESV API took too long to respond. Please try again.");
        }

        using (response)
        {
            if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
                throw new EsvException("The ESV API rejected your API key. Check it under \"ESV API key\".");
            if ((int)response.StatusCode == 429)
                throw new EsvException("ESV API rate limit reached. Please wait a bit and try again.");
            if (!response.IsSuccessStatusCode)
                throw new EsvException($"ESV API error {(int)response.StatusCode} ({response.ReasonPhrase}).");

            using var doc = JsonDocument.Parse(await response.Content.ReadAsStringAsync(ct));
            var root = doc.RootElement;
            var canonical = root.TryGetProperty("canonical", out var c) ? c.GetString() ?? "" : "";
            var passages = root.TryGetProperty("passages", out var p) && p.ValueKind == JsonValueKind.Array
                ? p.EnumerateArray().Select(x => x.GetString() ?? "").Where(s => !string.IsNullOrWhiteSpace(s)).ToList()
                : new List<string>();

            if (passages.Count == 0 || string.IsNullOrWhiteSpace(canonical))
                throw new EsvException($"No passage found for \"{query}\". Try a reference like \"John 3:16-18\".");

            return (canonical, string.Join("\n\n", passages).Trim());
        }
    }
}
