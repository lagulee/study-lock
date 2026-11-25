namespace StudyLock;

/// <summary>무엇을 막을지 정하는 순수 규칙(테스트 대상).</summary>
public static class Rules
{
    /// <summary>창 제목으로 사이트를 막을 브라우저(프로세스 이름).</summary>
    public static readonly HashSet<string> Browsers = new(StringComparer.OrdinalIgnoreCase)
    {
        "chrome", "msedge", "whale", "firefox", "opera", "opera_gx", "brave", "vivaldi",
    };

    /// <summary>막으면 윈도우가 망가지거나 이 앱 자신인 프로그램. 목록에 넣을 수 없다.</summary>
    public static readonly HashSet<string> Protected = new(StringComparer.OrdinalIgnoreCase)
    {
        "studylock", "explorer", "taskmgr", "system", "idle", "csrss", "winlogon", "wininit", "smss", "services",
        "lsass", "svchost", "dwm", "fontdrvhost", "sihost", "ctfmon", "conhost", "runtimebroker", "searchhost",
        "startmenuexperiencehost", "shellexperiencehost", "applicationframehost", "textinputhost", "securityhealthsystray",
    };

    /// <summary>"C:\Games\game.exe", "Game.EXE", " discord " → "game", "discord". 막을 수 없는 이름이면 null.</summary>
    public static string? NormalizeProgram(string? raw)
    {
        var s = (raw ?? "").Trim().Trim('"');
        if (s.Length == 0) return null;
        s = Path.GetFileName(s);
        if (s.EndsWith(".exe", StringComparison.OrdinalIgnoreCase)) s = s[..^4];
        s = s.Trim().ToLowerInvariant();
        if (s.Length == 0 || Protected.Contains(s)) return null;
        return s;
    }

    /// <summary>창 제목에 들어 있는 첫 낱말(대소문자 무시). 없으면 null.</summary>
    public static string? MatchKeyword(string? title, IEnumerable<string> keywords)
    {
        if (string.IsNullOrWhiteSpace(title)) return null;
        foreach (var k in keywords)
        {
            var w = k.Trim();
            if (w.Length > 0 && title.Contains(w, StringComparison.OrdinalIgnoreCase)) return w;
        }
        return null;
    }

    public static string NormalizeKeyword(string? raw) => (raw ?? "").Trim();

    /// <summary>"12분 30초"</summary>
    public static string Remain(long ms)
    {
        var s = Math.Max(0, ms / 1000);
        return $"{s / 60}분 {s % 60}초";
    }

    /// <summary>"1시간 5분" / "42분"</summary>
    public static string Minutes(long ms)
    {
        var m = Math.Max(0, ms / 60_000);
        return m >= 60 ? $"{m / 60}시간 {m % 60}분" : $"{m}분";
    }
}
