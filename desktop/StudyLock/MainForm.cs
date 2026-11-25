using Microsoft.Win32;

namespace StudyLock;

/// <summary>본 창: 할 일·막을 프로그램·사이트 고르기, 집중 시작, 연동 설정, 기록. 닫으면 알림 영역으로 숨는다.</summary>
public sealed class MainForm : Form
{
    private readonly Store store;
    private readonly SyncClient sync;
    private readonly Blocker blocker;
    private readonly LockForm lockForm;
    private readonly NotifyIcon tray = new();
    private readonly System.Windows.Forms.Timer timer = new() { Interval = 500 };
    private readonly FlowLayoutPanel body = new()
    {
        Dock = DockStyle.Fill, FlowDirection = FlowDirection.TopDown, WrapContents = false, AutoScroll = true, Padding = new Padding(18, 12, 18, 18),
    };

    private Label? live;
    private Label? syncLabel;
    private string drawn = "";
    private bool hideOnStart;
    private bool reallyExit;
    private bool started;
    private bool wasActive;

    public MainForm(Store store, bool startHidden)
    {
        this.store = store;
        hideOnStart = startHidden;
        sync = new SyncClient(store);
        blocker = new Blocker(store);
        lockForm = new LockForm(store, AskWait);

        var version = Application.ProductVersion.Split('+')[0];
        Text = $"공부 잠금 {version}";
        Font = new Font("Malgun Gothic", 10.5f);
        ClientSize = new Size(Ui.Width + 60, 760);
        MinimumSize = new Size(Ui.Width + 60, 400);
        StartPosition = FormStartPosition.CenterScreen;
        Controls.Add(body);

        tray.Icon = SystemIcons.Shield;
        tray.Text = "공부 잠금";
        tray.Visible = true;
        tray.DoubleClick += (_, _) => ShowMain();
        var menu = new ContextMenuStrip();
        menu.Items.Add("열기", null, (_, _) => ShowMain());
        menu.Items.Add("끝내기", null, (_, _) => ExitApp());
        tray.ContextMenuStrip = menu;

        sync.Changed += OnSyncChanged;
        blocker.Blocked += why => lockForm.ShowBlocked(why);
        timer.Tick += (_, _) => OnTick();
        timer.Start();
        wasActive = store.D.Active;
        Render();
    }

    // ---- 창 보이기·숨기기 ----

    protected override void SetVisibleCore(bool value)
    {
        if (hideOnStart)
        {
            hideOnStart = false;
            if (!IsHandleCreated) CreateHandle();
            value = false;
        }
        base.SetVisibleCore(value);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        if (!reallyExit && e.CloseReason == CloseReason.UserClosing)
        {
            e.Cancel = true;
            Hide();
            tray.ShowBalloonTip(3000, "공부 잠금", "알림 영역에서 계속 돌고 있어요. 끄려면 아이콘을 오른쪽 클릭 → 끝내기.", ToolTipIcon.Info);
        }
        base.OnFormClosing(e);
    }

    private void ShowMain()
    {
        Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    private void ExitApp()
    {
        if (store.D.Active)
        {
            MessageBox.Show("집중 중에는 끌 수 없어요.", "공부 잠금");
            return;
        }
        reallyExit = true;
        tray.Visible = false;
        Application.Exit();
    }

    // ---- 주기적으로 ----

    private void OnTick()
    {
        var now = Store.Now();
        if (!started) { started = true; sync.Restart(); }
        store.FinishWaitIfDone(now);
        blocker.Tick(now);
        sync.Tick();
        lockForm.ShowState(now);
        NotifyChange();
        if (Key() != drawn) Render();
        else if (live != null) live.Text = LiveText(now);
    }

    private void OnSyncChanged()
    {
        NotifyChange();
        if (Key() != drawn) Render();
        else if (syncLabel != null) syncLabel.Text = SyncText();
    }

    /// <summary>다른 기기 때문에 잠기거나 풀리면 알려 준다.</summary>
    private void NotifyChange()
    {
        var active = store.D.Active;
        if (active == wasActive) return;
        wasActive = active;
        if (active && store.D.SessionBy.Length > 0)
            tray.ShowBalloonTip(4000, "🔒 집중 시작", $"{store.D.SessionBy}에서 집중을 시작했어요: {store.D.SessionTask}", ToolTipIcon.Info);
        else if (!active)
            tray.ShowBalloonTip(4000, "🔓 잠금 풀림", "집중이 끝나 잠금이 풀렸어요.", ToolTipIcon.Info);
    }

    private string Key() => $"{store.D.Active}|{store.D.WaitUntil}|{store.D.SessionTask}|{store.D.SyncCode}|{store.D.Records.Count}|" +
        $"{store.D.BlockedPrograms.Count}|{store.D.BlockedKeywords.Count}";

    private string LiveText(long now)
    {
        var d = store.D;
        var s = $"시작 {Ui.Clock(d.StartedAt)} · {Rules.Minutes(now - d.StartedAt)} 지남";
        if (d.WaitUntil != 0) s += $"\n⏳ 대기 해제까지 {Rules.Remain(d.WaitUntil - now)}";
        return s;
    }

    private string SyncText() => !store.SyncEnabled ? "" :
        sync.Connected ? $"🔗 연동됨: {string.Join(", ", sync.Devices)}" : "🔗 연동 서버에 연결하는 중…";

    // ---- 화면 ----

    private void Render()
    {
        drawn = Key();
        var scroll = body.VerticalScroll.Value;
        body.SuspendLayout();
        foreach (Control c in body.Controls.Cast<Control>().ToList()) c.Dispose();
        body.Controls.Clear();
        body.AddText("공부 잠금", 20f, bold: true, top: 0);
        body.AddText("할 일을 끝내고 폰에서 사진으로 인증해야 고른 프로그램·사이트가 풀려요.", 10f, color: Color.DimGray);
        syncLabel = store.SyncEnabled ? body.AddText(SyncText(), 10.5f) : null;
        body.AddDivider();
        if (store.D.Active) ActiveSection(); else SetupSection();
        BlockListSection(store.D.Active);
        body.AddDivider();
        SyncSection();
        body.AddDivider();
        OptionsSection();
        RecordsSection();
        body.ResumeLayout();
        try { body.VerticalScroll.Value = Math.Min(scroll, body.VerticalScroll.Maximum); } catch { }
    }

    private void ActiveSection()
    {
        var d = store.D;
        body.AddText("🔒 집중 중", 17f, bold: true);
        body.AddText($"할 일: {d.SessionTask}", 13f);
        if (d.SessionBy.Length > 0) body.AddText($"{d.SessionBy}에서 시작한 집중이에요.", 10.5f);
        live = body.AddText(LiveText(Store.Now()));
        body.AddText("사진 인증은 폰 앱에서 해요. 폰에서 풀면 여기도 같이 풀려요.", 10f, color: Color.DimGray);
        if (d.WaitUntil == 0) body.AddButton($"⏳ {d.SessionWaitMinutes}분 기다려서 풀기", AskWait);
    }

    private void SetupSection()
    {
        live = null;
        body.AddText("1. 할 일", 12f, bold: true);
        var task = body.AddInput(store.D.Task, "예: 수학 문제집 24~27쪽 풀기");
        task.TextChanged += (_, _) => store.D.Task = task.Text;
        task.Leave += (_, _) => store.Save();

        body.AddText("2. 기다려서 풀 때 걸리는 시간", 12f, bold: true, top: 12);
        var wait = new ComboBox { DropDownStyle = ComboBoxStyle.DropDownList, Width = 160 };
        foreach (var m in Store.WaitChoices) wait.Items.Add($"{m}분");
        wait.SelectedIndex = Math.Max(0, Array.IndexOf(Store.WaitChoices, store.D.WaitMinutes));
        wait.SelectedIndexChanged += (_, _) => { store.D.WaitMinutes = Store.WaitChoices[wait.SelectedIndex]; store.Save(); };
        body.Controls.Add(wait);

        body.AddButton("🔒 집중 시작", () =>
        {
            store.D.Task = task.Text;
            if (store.D.Task.Trim().Length == 0) { MessageBox.Show("할 일을 적어 주세요.", "공부 잠금"); return; }
            if (store.D.BlockedPrograms.Count == 0 && store.D.BlockedKeywords.Count == 0)
            {
                MessageBox.Show("아래에서 막을 프로그램이나 사이트를 하나 이상 넣어 주세요.", "공부 잠금");
                return;
            }
            var ok = MessageBox.Show($"할 일: {store.D.Task.Trim()}\n\n고른 프로그램·사이트가 잠겨요. 폰에서 사진으로 인증하거나 {store.D.WaitMinutes}분을 기다려야 풀려요.",
                "집중을 시작할까요?", MessageBoxButtons.OKCancel);
            if (ok != DialogResult.OK) return;
            store.StartSession(Store.Now());
            sync.Tick();
            Render();
        }, top: 14);
    }

    private void BlockListSection(bool locked)
    {
        body.AddText("막을 프로그램", 12f, bold: true, top: 16);
        var programs = body.AddList(store.D.BlockedPrograms);
        body.AddButtons(
            ("켜져 있는 것에서 고르기", PickRunning),
            ("exe 파일 고르기", PickExe),
            (locked ? "(집중 중엔 못 빼요)" : "고른 것 빼기", () => { if (!locked) RemoveSelected(store.D.BlockedPrograms, programs); }));

        body.AddText("막을 사이트", 12f, bold: true, top: 16);
        body.AddText("브라우저(크롬·엣지·웨일 등) 창 제목에 이 낱말이 들어가면 그 탭을 닫아요. 예: YouTube, Instagram, 나무위키", 9.5f, color: Color.DimGray);
        var sites = body.AddList(store.D.BlockedKeywords, 80);
        var word = body.AddInput("", "낱말 입력 후 '추가'");
        body.AddButtons(
            ("추가", () =>
            {
                var w = Rules.NormalizeKeyword(word.Text);
                if (w.Length == 0 || store.D.BlockedKeywords.Contains(w, StringComparer.OrdinalIgnoreCase)) return;
                store.D.BlockedKeywords.Add(w);
                store.Save();
                Render();
            }),
            (locked ? "(집중 중엔 못 빼요)" : "고른 것 빼기", () => { if (!locked) RemoveSelected(store.D.BlockedKeywords, sites); }));
    }

    private void RemoveSelected(List<string> list, ListBox box)
    {
        if (box.SelectedItem is not string s) return;
        list.Remove(s);
        store.Save();
        Render();
    }

    private void PickRunning()
    {
        using var picker = new ProgramPicker(store.D.BlockedPrograms);
        if (picker.ShowDialog(this) != DialogResult.OK) return;
        foreach (var n in picker.Picked) if (!store.D.BlockedPrograms.Contains(n)) store.D.BlockedPrograms.Add(n);
        store.Save();
        Render();
    }

    private void PickExe()
    {
        using var dlg = new OpenFileDialog { Filter = "프로그램 (*.exe)|*.exe", Title = "막을 프로그램 고르기" };
        if (dlg.ShowDialog(this) != DialogResult.OK) return;
        var n = Rules.NormalizeProgram(dlg.FileName);
        if (n == null) { MessageBox.Show("이 프로그램은 막을 수 없어요(윈도우에 꼭 필요한 프로그램).", "공부 잠금"); return; }
        if (!store.D.BlockedPrograms.Contains(n)) store.D.BlockedPrograms.Add(n);
        store.Save();
        Render();
    }

    private void AskWait()
    {
        if (!store.D.Active || store.D.WaitUntil != 0) return;
        var ok = MessageBox.Show($"{store.D.SessionWaitMinutes}분을 기다리면 모든 기기의 잠금이 풀려요. 기다리는 동안에도 계속 막혀요.",
            "기다려서 풀까요?", MessageBoxButtons.OKCancel);
        if (ok != DialogResult.OK) return;
        store.StartWait(Store.Now());
        sync.Tick();
        Render();
    }

    private void SyncSection()
    {
        var locked = store.D.Active;
        body.AddText("다른 기기와 연동", 12f, bold: true);
        body.AddText("폰·탭·컴퓨터에 같은 연동 코드를 넣으면, 한 곳에서 집중을 시작하거나 풀 때 다른 기기도 똑같이 따라가요.", 9.5f, color: Color.DimGray);

        body.AddText("이 기기 이름", 10.5f, bold: true, top: 10);
        var name = body.AddInput(store.D.DeviceName, "예: 컴퓨터, 노트북");
        name.Enabled = !locked;
        body.AddText("연동 서버 주소", 10.5f, bold: true, top: 10);
        var server = body.AddInput(store.D.SyncServer, "예: study-lock-sync.이름.workers.dev");
        server.Enabled = !locked && store.D.SyncCode == null;

        if (store.D.SyncCode is { } code)
        {
            body.AddText($"연동 코드: {SyncProtocol.Pretty(code)}", 15f, bold: true, top: 10);
            body.AddText("폰·탭 앱의 설정에서 이 코드를 넣으면 함께 잠겨요.", 9.5f, color: Color.DimGray);
            if (locked) return;
            body.AddButtons(
                ("이름 저장", () =>
                {
                    var n = name.Text.Trim();
                    if (n.Length == 0) return;
                    store.D.DeviceName = n.Length > 20 ? n[..20] : n;
                    store.Save();
                    sync.Restart();
                }),
                ("연동 끊기", () =>
                {
                    store.D.SyncCode = null;
                    store.D.PendingOps.Clear();
                    store.Save();
                    sync.Restart();
                    Render();
                }));
            return;
        }
        if (locked) return;
        var input = body.AddInput("", "받은 연동 코드 (예: ABCD-EFGH-JKMN)", top: 10);
        body.AddButtons(
            ("이 코드로 연결", async () =>
            {
                var s = SyncProtocol.NormalizeServer(server.Text);
                var c = SyncProtocol.NormalizeCode(input.Text);
                if (s.Length == 0) { MessageBox.Show("연동 서버 주소를 넣어 주세요.", "공부 잠금"); return; }
                if (c == null) { MessageBox.Show("연동 코드는 12글자예요. 다시 확인해 주세요.", "공부 잠금"); return; }
                await Join(s, name.Text, async () => { await SyncClient.CheckGroupAsync(s, c); return c; });
            }),
            ("새 연동 만들기 (첫 기기일 때)", async () =>
            {
                var s = SyncProtocol.NormalizeServer(server.Text);
                if (s.Length == 0) { MessageBox.Show("연동 서버 주소를 넣어 주세요.", "공부 잠금"); return; }
                await Join(s, name.Text, () => SyncClient.CreateGroupAsync(s));
            }));
    }

    private async Task Join(string server, string name, Func<Task<string>> getCode)
    {
        UseWaitCursor = true;
        try
        {
            var code = await getCode();
            store.D.SyncServer = server;
            if (name.Trim().Length > 0) store.D.DeviceName = name.Trim();
            store.D.SyncCode = code;
            store.D.PendingOps.Clear();
            store.Save();
            sync.Restart();
            Render();
        }
        catch (SyncException e)
        {
            MessageBox.Show(e.Message, "공부 잠금");
        }
        finally
        {
            UseWaitCursor = false;
        }
    }

    private void OptionsSection()
    {
        var auto = new CheckBox { Text = "윈도우를 켤 때 자동으로 실행 (알림 영역에 조용히)", AutoSize = true, Checked = store.D.AutoStart, Margin = new Padding(0, 6, 0, 0) };
        auto.Enabled = !store.D.Active;
        auto.CheckedChanged += (_, _) =>
        {
            store.D.AutoStart = auto.Checked;
            store.Save();
            SetAutoStart(auto.Checked);
        };
        body.Controls.Add(auto);
    }

    private static void SetAutoStart(bool on)
    {
        try
        {
            using var key = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run", writable: true);
            if (key == null) return;
            if (on) key.SetValue("StudyLock", $"\"{Environment.ProcessPath}\" --tray");
            else key.DeleteValue("StudyLock", throwOnMissingValue: false);
        }
        catch
        {
            MessageBox.Show("자동 실행을 바꾸지 못했어요.", "공부 잠금");
        }
    }

    private void RecordsSection()
    {
        if (store.D.Records.Count == 0) return;
        body.AddDivider();
        body.AddText("기록", 12f, bold: true);
        foreach (var r in store.D.Records.Take(10))
        {
            var line = $"{Ui.Clock(r.Start)} · {Rules.Minutes(r.End - r.Start)} · {r.Method}\n    {r.Task}";
            if (r.Note.Length > 0) line += $"\n    💬 {r.Note}";
            body.AddText(line, 9.5f);
        }
    }

    protected override void Dispose(bool disposing)
    {
        if (disposing)
        {
            timer.Dispose();
            tray.Dispose();
            lockForm.Dispose();
        }
        base.Dispose(disposing);
    }
}
