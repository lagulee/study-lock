using System.Text.Json;
using System.Text.Json.Serialization;

namespace StudyLock;

public sealed class Record
{
    public string Task { get; set; } = "";
    public long Start { get; set; }
    public long End { get; set; }
    public string Method { get; set; } = "";
    public string Note { get; set; } = "";
}

/// <summary>연동 서버로 보낼 동작(이 기기에서 한 집중 시작·대기·해제).</summary>
public sealed class SyncOp
{
    public string Type { get; set; } = "";
    public string? Task { get; set; }
    public int? WaitMinutes { get; set; }
    public string? Method { get; set; }
    public string? Note { get; set; }
    public string? By { get; set; }
}

public sealed class AppData
{
    // 준비
    public string Task { get; set; } = "";
    public int WaitMinutes { get; set; } = 20;
    public List<string> BlockedPrograms { get; set; } = [];
    public List<string> BlockedKeywords { get; set; } = [];
    public bool AutoStart { get; set; }

    // 연동
    public string SyncServer { get; set; } = "";
    public string? SyncCode { get; set; }
    public string DeviceName { get; set; } = "컴퓨터";
    public List<SyncOp> PendingOps { get; set; } = [];

    // 집중 세션
    public bool Active { get; set; }
    public string SessionTask { get; set; } = "";
    public long StartedAt { get; set; }
    public int SessionWaitMinutes { get; set; } = 20;
    public long WaitUntil { get; set; }
    /// <summary>다른 기기에서 시작한 세션이면 그 기기 이름.</summary>
    public string SessionBy { get; set; } = "";

    public List<Record> Records { get; set; } = [];
}

/// <summary>설정·세션·기록을 %AppData%\StudyLock\data.json 에 저장한다. 화면(UI) 스레드에서만 쓴다.</summary>
public sealed class Store
{
    public static readonly int[] WaitChoices = [10, 20, 30, 60];
    public const int MaxRecords = 30;

    internal static readonly JsonSerializerOptions Json = new()
    {
        PropertyNamingPolicy = JsonNamingPolicy.CamelCase,
        DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull,
        WriteIndented = true,
    };

    private readonly string path;
    public AppData D { get; private set; }

    public Store(string path)
    {
        this.path = path;
        D = new AppData();
        try
        {
            if (File.Exists(path)) D = JsonSerializer.Deserialize<AppData>(File.ReadAllText(path), Json) ?? new AppData();
        }
        catch
        {
            // 파일이 깨졌으면 새로 시작(옛 파일은 남겨 둔다)
            try { File.Copy(path, path + ".broken", true); } catch { }
        }
    }

    public static string DefaultPath =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "StudyLock", "data.json");

    public void Save()
    {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        var tmp = path + ".tmp";
        File.WriteAllText(tmp, JsonSerializer.Serialize(D, Json));
        File.Move(tmp, path, true);
    }

    public static long Now() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public bool SyncEnabled => D.SyncCode != null && D.SyncServer.Trim().Length > 0;

    // ---- 세션 ----

    public void StartSession(long now)
    {
        D.Active = true;
        D.SessionTask = D.Task.Trim();
        D.StartedAt = now;
        D.SessionWaitMinutes = D.WaitMinutes;
        D.WaitUntil = 0;
        D.SessionBy = "";
        Queue(new SyncOp { Type = "start", Task = D.SessionTask, WaitMinutes = D.WaitMinutes });
        Save();
    }

    public void StartWait(long now)
    {
        if (!D.Active || D.WaitUntil != 0) return;
        D.WaitUntil = now + D.SessionWaitMinutes * 60_000L;
        Queue(new SyncOp { Type = "wait" });
        Save();
    }

    /// <summary>대기 시간이 다 지났으면 세션을 끝내고 true.</summary>
    public bool FinishWaitIfDone(long now)
    {
        if (D.Active && D.WaitUntil != 0 && now >= D.WaitUntil)
        {
            EndSession("대기 해제", now);
            return true;
        }
        return false;
    }

    public void EndSession(string method, long now, string note = "")
    {
        if (!D.Active) return;
        AddRecord(new Record { Task = D.SessionTask, Start = D.StartedAt, End = now, Method = method, Note = note });
        D.Active = false;
        D.WaitUntil = 0;
        D.SessionBy = "";
        Queue(new SyncOp { Type = "end", Method = method, Note = note });
        Save();
    }

    /// <summary>
    /// 서버 상태를 이 기기에 맞춘다(보낼 동작이 남아 있으면 부르지 않는다). 시각은 이 기기 시계로 바꾼 값.
    /// </summary>
    public void ApplyRemote(Remote r, long now)
    {
        if (r.Active)
        {
            D.Active = true;
            D.SessionTask = r.Task;
            D.StartedAt = r.StartedAt;
            D.SessionWaitMinutes = r.WaitMinutes;
            D.WaitUntil = r.WaitUntil;
            D.SessionBy = r.By == D.DeviceName ? "" : r.By;
            Save();
        }
        else if (D.Active)
        {
            var where = r.EndedBy.Length > 0 && r.EndedBy != D.DeviceName ? $" ({r.EndedBy})" : "";
            var method = (r.EndedMethod.Length > 0 ? r.EndedMethod : "해제") + where;
            AddRecord(new Record { Task = D.SessionTask, Start = D.StartedAt, End = now, Method = method, Note = r.EndedNote });
            D.Active = false;
            D.WaitUntil = 0;
            D.SessionBy = "";
            Save();
        }
    }

    private void Queue(SyncOp op)
    {
        if (!SyncEnabled) return;
        op.By = D.DeviceName;
        D.PendingOps.Add(op);
    }

    private void AddRecord(Record r)
    {
        D.Records.Insert(0, r);
        if (D.Records.Count > MaxRecords) D.Records.RemoveRange(MaxRecords, D.Records.Count - MaxRecords);
    }
}
