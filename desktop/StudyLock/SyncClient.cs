using System.Net.WebSockets;
using System.Text;

namespace StudyLock;

/// <summary>
/// 연동 서버와 주고받기. 화면(UI) 스레드에서 시작하므로 await 뒤도 UI 스레드에서 이어져 Store 를 안전하게 바꾼다.
/// 소켓으로 다른 기기의 변화를 받고, 이 기기 동작(PendingOps)은 HTTP 로 차례로 보낸다.
/// </summary>
public sealed class SyncClient(Store store)
{
    private static readonly HttpClient Http = new() { Timeout = TimeSpan.FromSeconds(20) };

    public event Action? Changed;
    public bool Connected { get; private set; }
    public IReadOnlyList<string> Devices { get; private set; } = [];
    public string? LastError { get; private set; }

    private CancellationTokenSource? cts;
    private bool flushing;
    private long retryAt;

    /// <summary>연동 코드·서버·이름이 바뀌면 다시 연결한다.</summary>
    public void Restart()
    {
        cts?.Cancel();
        cts = null;
        Connected = false;
        Devices = [];
        if (store.SyncEnabled)
        {
            cts = new CancellationTokenSource();
            _ = RunAsync(store.D.SyncServer, store.D.SyncCode!, store.D.DeviceName, cts.Token);
        }
        Changed?.Invoke();
    }

    private async Task RunAsync(string server, string code, string name, CancellationToken ct)
    {
        var delay = 2_000;
        while (!ct.IsCancellationRequested)
        {
            using var ws = new ClientWebSocket();
            ws.Options.KeepAliveInterval = TimeSpan.FromSeconds(25);
            ws.Options.KeepAliveTimeout = TimeSpan.FromSeconds(20); // 답이 없으면 끊긴 것으로 보고 다시 잇는다
            try
            {
                await ws.ConnectAsync(new Uri(SyncProtocol.WsUrl(server, code, name)), ct);
                delay = 2_000;
                LastError = null;
                _ = FlushAsync();
                var buffer = new byte[16 * 1024];
                using var ms = new MemoryStream();
                while (ws.State == WebSocketState.Open && !ct.IsCancellationRequested)
                {
                    ms.SetLength(0);
                    WebSocketReceiveResult res;
                    do
                    {
                        res = await ws.ReceiveAsync(new ArraySegment<byte>(buffer), ct);
                        if (res.MessageType == WebSocketMessageType.Close) break;
                        ms.Write(buffer, 0, res.Count);
                    } while (!res.EndOfMessage);
                    if (res.MessageType == WebSocketMessageType.Close) break;
                    var text = Encoding.UTF8.GetString(ms.ToArray());
                    if (text == "pong") continue;
                    Connected = true;
                    OnRemote(text);
                }
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                return;
            }
            catch (Exception e)
            {
                LastError = e.Message;
            }
            if (ct.IsCancellationRequested) return;
            Connected = false;
            Changed?.Invoke();
            try { await Task.Delay(delay, ct); } catch { return; }
            delay = Math.Min(delay * 2, 60_000);
        }
    }

    /// <summary>쌓인 동작이 있으면 보낸다. 실패하면 몇 초 뒤 Tick 에서 다시.</summary>
    public void Tick()
    {
        if (!flushing && store.D.PendingOps.Count > 0 && Store.Now() >= retryAt) _ = FlushAsync();
    }

    private async Task FlushAsync()
    {
        if (flushing || !store.SyncEnabled) return;
        flushing = true;
        string? last = null;
        try
        {
            while (store.D.PendingOps.Count > 0 && store.SyncEnabled)
            {
                var op = store.D.PendingOps[0];
                last = await PostAsync(store.D.SyncServer, store.D.SyncCode!, "op", SyncProtocol.OpJson(op));
                if (store.D.PendingOps.Count > 0 && ReferenceEquals(store.D.PendingOps[0], op)) store.D.PendingOps.RemoveAt(0);
                store.Save();
            }
        }
        catch (SyncException e)
        {
            LastError = e.Message;
            if (e.Permanent) { store.D.PendingOps.Clear(); store.Save(); }
            else retryAt = Store.Now() + 5_000;
        }
        finally
        {
            flushing = false;
        }
        if (last != null) OnRemote(last);
    }

    /// <summary>서버 상태: 보낼 동작이 남아 있지 않을 때만 이 기기에 맞춘다(이 기기 동작이 먼저).</summary>
    private void OnRemote(string json)
    {
        Remote? r;
        try { r = SyncProtocol.Parse(json, Store.Now()); } catch { return; }
        if (r == null) return;
        Devices = r.Devices;
        if (!flushing && store.D.PendingOps.Count == 0) store.ApplyRemote(r, Store.Now());
        Changed?.Invoke();
    }

    // ---- HTTP ----

    private static async Task<string> PostAsync(string server, string code, string action, string? body)
    {
        var url = $"{SyncProtocol.NormalizeServer(server)}/g/{code}/{action}";
        try
        {
            using var res = body == null
                ? await Http.GetAsync(url)
                : await Http.PostAsync(url, new StringContent(body, Encoding.UTF8, "application/json"));
            var text = await res.Content.ReadAsStringAsync();
            var err = SyncProtocol.ErrorOf((int)res.StatusCode, text);
            if (err != null) throw err;
            return text;
        }
        catch (SyncException) { throw; }
        catch (Exception)
        {
            throw new SyncException("연동 서버에 연결할 수 없어요. 인터넷이나 서버 주소를 확인해 주세요.");
        }
    }

    /// <summary>새 연동 코드를 만든다.</summary>
    public static async Task<string> CreateGroupAsync(string server)
    {
        try
        {
            using var res = await Http.PostAsync($"{SyncProtocol.NormalizeServer(server)}/api/group", new StringContent(""));
            var text = await res.Content.ReadAsStringAsync();
            var err = SyncProtocol.ErrorOf((int)res.StatusCode, text);
            if (err != null) throw err;
            var code = System.Text.Json.Nodes.JsonNode.Parse(text)?["code"]?.GetValue<string>();
            return code ?? throw new SyncException("연동 서버 답을 읽지 못했어요.");
        }
        catch (SyncException) { throw; }
        catch (Exception)
        {
            throw new SyncException("연동 서버에 연결할 수 없어요. 서버 주소를 확인해 주세요.");
        }
    }

    /// <summary>있는 연동 코드인지 확인한다(없으면 SyncException).</summary>
    public static Task CheckGroupAsync(string server, string code) => PostAsync(server, code, "state", null);
}
