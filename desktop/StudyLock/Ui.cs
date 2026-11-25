namespace StudyLock;

/// <summary>디자이너 없이 코드로 화면을 쌓는 도우미(세로로 차례차례).</summary>
internal static class Ui
{
    public const int Width = 560;

    public static Label AddText(this FlowLayoutPanel p, string text, float size = 10.5f, bool bold = false, int top = 6, Color? color = null)
    {
        var l = new Label
        {
            Text = text, AutoSize = true, MaximumSize = new Size(Width, 0),
            Font = new Font("Malgun Gothic", size, bold ? FontStyle.Bold : FontStyle.Regular),
            Margin = new Padding(0, top, 0, 0),
            ForeColor = color ?? SystemColors.ControlText,
        };
        p.Controls.Add(l);
        return l;
    }

    public static Button AddButton(this FlowLayoutPanel p, string text, Action onClick, int top = 8, int width = Width)
    {
        var b = new Button { Text = text, Width = width, Height = 38, Margin = new Padding(0, top, 0, 0) };
        b.Click += (_, _) => onClick();
        p.Controls.Add(b);
        return b;
    }

    public static TextBox AddInput(this FlowLayoutPanel p, string value, string placeholder, int top = 4)
    {
        var t = new TextBox { Text = value, PlaceholderText = placeholder, Width = Width, Margin = new Padding(0, top, 0, 0) };
        p.Controls.Add(t);
        return t;
    }

    public static ListBox AddList(this FlowLayoutPanel p, IEnumerable<string> items, int height = 90)
    {
        var l = new ListBox { Width = Width, Height = height, Margin = new Padding(0, 4, 0, 0), IntegralHeight = false };
        foreach (var s in items) l.Items.Add(s);
        p.Controls.Add(l);
        return l;
    }

    /// <summary>버튼 여러 개를 가로로 한 줄에.</summary>
    public static void AddButtons(this FlowLayoutPanel p, params (string text, Action onClick)[] items)
    {
        var row = new FlowLayoutPanel { FlowDirection = FlowDirection.LeftToRight, AutoSize = true, WrapContents = false, Margin = new Padding(0, 6, 0, 0) };
        var w = (Width - 8 * (items.Length - 1)) / items.Length;
        foreach (var (text, onClick) in items)
        {
            var b = new Button { Text = text, Width = w, Height = 36, Margin = new Padding(0, 0, 8, 0) };
            b.Click += (_, _) => onClick();
            row.Controls.Add(b);
        }
        p.Controls.Add(row);
    }

    public static void AddDivider(this FlowLayoutPanel p)
    {
        p.Controls.Add(new Label { AutoSize = false, Width = Width, Height = 1, BackColor = Color.Gainsboro, Margin = new Padding(0, 14, 0, 4) });
    }

    public static string Clock(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime().ToString("M/d HH:mm");
}
