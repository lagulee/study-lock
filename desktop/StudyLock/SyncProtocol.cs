using System.Text.Json;
using System.Text.Json.Nodes;

namespace StudyLock;

/// <summary>서버 상태를 이 기기 시계 기준으로 바꾼 것.</summary>
public sealed record Remote(
    long Rev, bool Active, string Task, long StartedAt, int WaitMinutes, long WaitUntil, string By,
    string EndedMethod, string EndedBy, string EndedNote, IReadOnlyList<string> Devices);

public sealed class SyncException(string message, bool permanent = false) : Exception(message)
{
    public bool Permanent { get; } = permanent;
}

/// <summary>연동 서버 형식 읽기·쓰기(순수 함수, 테스트 대상).</summary>
public static class SyncProtocol
{
    private const string CodeAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    /// <summary>"study-lock-sync.abc.workers.dev" 도 https:// 를 붙이고 끝의 / 를 뗀다.</summary>
    public static string NormalizeServer(string? raw)
    {
        var s = (raw ?? "").Trim().TrimEnd('/');
        if (s.Length == 0) return s;
        if (!s.StartsWith("http://", StringComparison.OrdinalIgnoreCase) && !s.StartsWith("https://", StringComparison.OrdinalIgnoreCase))
            s = "https://" + s;
        return s;
    }

    public static string? NormalizeCode(string? raw)
    {
        var s = new string((raw ?? "").ToUpperInvariant().Where(c => c != ' ' && c != '-').ToArray());
        return s.Length == 12 && s.All(c => CodeAlphabet.Contains(c)) ? s : null;
    }

    public static string Pretty(string code) => string.Join("-", Enumerable.Range(0, (code.Length + 3) / 4)
        .Select(i => code.Substring(i * 4, Math.Min(4, code.Length - i * 4))));

    public static string WsUrl(string server, string code, string name)
    {
        var b = NormalizeServer(server);
        b = b.StartsWith("https://", StringComparison.OrdinalIgnoreCase) ? "wss://" + b[8..] : "ws://" + b[7..];
        return $"{b}/g/{code}/ws?name={Uri.EscapeDataString(name)}";
    }

    /// <summary>{ type: "state", state, devices, now } 를 읽는다. state 가 없으면 null.</summary>
    public static Remote? Parse(string json, long localNow)
    {
        var msg = JsonNode.Parse(json) as JsonObject;
        if (msg?["state"] is not JsonObject s) return null;
        var offset = (msg["now"]?.GetValue<long>() ?? localNow) - localNow;
        long Local(long t) => t == 0 ? 0 : t - offset;
        long L(string k) => s[k] is JsonValue v && v.TryGetValue<long>(out var x) ? x : 0;
        string S(JsonObject? o, string k) => o?[k] is JsonValue v && v.TryGetValue<string>(out var x) ? x : "";
        var ended = s["ended"] as JsonObject;
        var devices = (msg["devices"] as JsonArray)?.Select(d => d?.GetValue<string>() ?? "").ToList() ?? [];
        return new Remote(
            L("rev"), s["active"]?.GetValue<bool>() ?? false, S(s, "task"), Local(L("startedAt")), (int)(L("waitMinutes") == 0 ? 20 : L("waitMinutes")),
            Local(L("waitUntil")), S(s, "by"), S(ended, "method"), S(ended, "by"), S(ended, "note"), devices);
    }

    public static string OpJson(SyncOp op) => JsonSerializer.Serialize(op, Store.Json);

    /// <summary>오류 응답을 알기 쉬운 말로. 성공이면 null.</summary>
    public static SyncException? ErrorOf(int status, string body)
    {
        if (status is >= 200 and < 300) return null;
        string err = "";
        try { err = (JsonNode.Parse(body) as JsonObject)?["error"]?.GetValue<string>() ?? ""; } catch { }
        return (status, err) switch
        {
            (404, "noGroup") => new SyncException("없는 연동 코드예요. 코드를 다시 확인해 주세요.", true),
            (400, "badCode") => new SyncException("연동 코드 형식이 틀렸어요.", true),
            _ => new SyncException($"연동 서버 오류예요({status}). 서버 주소를 확인해 주세요."),
        };
    }
}
