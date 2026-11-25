using System.Diagnostics;

namespace StudyLock;

/// <summary>지금 켜져 있는 프로그램(창이 있는 것) 중에서 막을 것을 고르는 창.</summary>
public sealed class ProgramPicker : Form
{
    private readonly CheckedListBox list = new() { Dock = DockStyle.Fill, CheckOnClick = true, IntegralHeight = false };
    private readonly List<string> names = [];

    public IReadOnlyList<string> Picked { get; private set; } = [];

    public ProgramPicker(IEnumerable<string> already)
    {
        Text = "막을 프로그램 고르기";
        Font = new Font("Malgun Gothic", 10.5f);
        StartPosition = FormStartPosition.CenterParent;
        ClientSize = new Size(460, 520);
        Padding = new Padding(12);

        var set = new HashSet<string>(already, StringComparer.OrdinalIgnoreCase);
        var rows = new SortedDictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var p in Process.GetProcesses())
        {
            using (p)
            {
                try
                {
                    if (p.MainWindowHandle == IntPtr.Zero) continue;
                    var name = Rules.NormalizeProgram(p.ProcessName);
                    if (name == null || set.Contains(name) || rows.ContainsKey(name)) continue;
                    var title = p.MainWindowTitle;
                    rows[name] = string.IsNullOrWhiteSpace(title) ? name : $"{name}   ({Trim(title)})";
                }
                catch { }
            }
        }
        foreach (var (name, label) in rows)
        {
            names.Add(name);
            list.Items.Add(label);
        }

        var top = new Label
        {
            Text = "지금 켜져 있는 프로그램이에요. 막을 것을 체크하세요.\n목록에 없으면 그 프로그램을 먼저 켜 두거나 'exe 파일 고르기'를 쓰세요.",
            Dock = DockStyle.Top, Height = 52,
        };
        var ok = new Button { Text = "추가", Dock = DockStyle.Bottom, Height = 40 };
        ok.Click += (_, _) =>
        {
            Picked = list.CheckedIndices.Cast<int>().Select(i => names[i]).ToList();
            DialogResult = DialogResult.OK;
        };
        Controls.Add(list);
        Controls.Add(top);
        Controls.Add(ok);
    }

    private static string Trim(string s) => s.Length > 30 ? s[..30] + "…" : s;
}
