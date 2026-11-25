namespace StudyLock;

/// <summary>막을 때 맨 위에 뜨는 잠금 창.</summary>
public sealed class LockForm : Form
{
    private readonly Store store;
    private readonly Action onWait;
    private readonly Label reason = new() { AutoSize = false, Dock = DockStyle.Top, Height = 48, TextAlign = ContentAlignment.MiddleCenter };
    private readonly Label status = new() { AutoSize = false, Dock = DockStyle.Top, Height = 64, TextAlign = ContentAlignment.MiddleCenter };
    private readonly Button waitButton = new() { Dock = DockStyle.Top, Height = 44 };

    public LockForm(Store store, Action onWait)
    {
        this.store = store;
        this.onWait = onWait;
        Text = "공부 잠금";
        Font = new Font("Malgun Gothic", 11f);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        MinimizeBox = false;
        TopMost = true;
        StartPosition = FormStartPosition.CenterScreen;
        ClientSize = new Size(520, 330);
        Padding = new Padding(20);
        ShowInTaskbar = true;

        var title = new Label { Text = "🔒 집중 중", Font = new Font(Font.FontFamily, 22f, FontStyle.Bold), Dock = DockStyle.Top, Height = 56, TextAlign = ContentAlignment.MiddleCenter };
        var hint = new Label
        {
            Text = "할 일을 끝냈으면 폰에서 사진으로 인증하세요. 여기도 같이 풀려요.",
            AutoSize = false, Dock = DockStyle.Top, Height = 44, TextAlign = ContentAlignment.MiddleCenter, ForeColor = Color.DimGray,
        };
        var close = new Button { Text = "닫고 공부하러 가기", Dock = DockStyle.Bottom, Height = 44 };
        close.Click += (_, _) => Hide();
        waitButton.Click += (_, _) => onWait();
        // 아래에서 위 순서로 쌓인다(Dock=Top 은 나중에 넣은 것이 위)
        Controls.Add(waitButton);
        Controls.Add(hint);
        Controls.Add(status);
        Controls.Add(reason);
        Controls.Add(title);
        Controls.Add(close);
    }

    protected override void OnFormClosing(FormClosingEventArgs e)
    {
        // 닫기 버튼은 숨기기만 한다(다시 막을 때 또 띄운다)
        if (e.CloseReason == CloseReason.UserClosing) { e.Cancel = true; Hide(); }
        base.OnFormClosing(e);
    }

    public void ShowBlocked(string why)
    {
        reason.Text = why;
        ShowState(Store.Now());
        if (!Visible) Show();
        WindowState = FormWindowState.Normal;
        Activate();
    }

    public void ShowState(long now)
    {
        var d = store.D;
        if (!d.Active) { if (Visible) Hide(); return; }
        status.Text = $"할 일: {d.SessionTask}\n{Rules.Minutes(now - d.StartedAt)}째 집중 중";
        if (d.WaitUntil == 0)
        {
            waitButton.Enabled = true;
            waitButton.Text = $"⏳ {d.SessionWaitMinutes}분 기다려서 풀기";
        }
        else
        {
            waitButton.Enabled = false;
            waitButton.Text = $"⏳ 대기 해제까지 {Rules.Remain(d.WaitUntil - now)}";
        }
    }
}
