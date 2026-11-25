using System.Diagnostics;

namespace StudyLock;

/// <summary>
/// 집중 중일 때 고른 프로그램을 끄고, 브라우저 창 제목에 고른 낱말이 있으면 그 탭을 닫는다.
/// 막을 때마다 Blocked 를 알린다(잠금 창 띄우기).
/// </summary>
public sealed class Blocker(Store store)
{
    public event Action<string>? Blocked;

    private long lastProcessScan;
    private IntPtr lastClosedWindow;
    private long lastClosedAt;

    public void Tick(long now)
    {
        if (!store.D.Active) return;
        if (now - lastProcessScan >= 1_000)
        {
            lastProcessScan = now;
            KillBlockedPrograms();
        }
        CloseBlockedTab(now);
    }

    private void KillBlockedPrograms()
    {
        if (store.D.BlockedPrograms.Count == 0) return;
        var blocked = new HashSet<string>(store.D.BlockedPrograms, StringComparer.OrdinalIgnoreCase);
        string? killed = null;
        foreach (var p in Process.GetProcesses())
        {
            using (p)
            {
                string name;
                try { name = p.ProcessName; } catch { continue; }
                if (!blocked.Contains(name) || Rules.Protected.Contains(name)) continue;
                try
                {
                    p.Kill(entireProcessTree: true);
                    killed = name;
                }
                catch
                {
                    // 관리자 권한으로 돈 프로그램 등은 끌 수 없다
                }
            }
        }
        if (killed != null) Blocked?.Invoke($"'{killed}' 프로그램은 집중하는 동안 막혀 있어요.");
    }

    private void CloseBlockedTab(long now)
    {
        if (store.D.BlockedKeywords.Count == 0) return;
        var hwnd = Native.Foreground();
        if (hwnd == IntPtr.Zero) return;
        if (hwnd == lastClosedWindow && now - lastClosedAt < 1_500) return; // 방금 닫은 창은 잠깐 기다린다
        var proc = Native.ProcessName(hwnd);
        if (!Rules.Browsers.Contains(proc)) return;
        var word = Rules.MatchKeyword(Native.Title(hwnd), store.D.BlockedKeywords);
        if (word == null) return;
        Native.CtrlW();
        lastClosedWindow = hwnd;
        lastClosedAt = now;
        Blocked?.Invoke($"'{word}' 사이트는 집중하는 동안 막혀 있어요. 탭을 닫았어요.");
    }
}
