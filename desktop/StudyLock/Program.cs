namespace StudyLock;

internal static class Program
{
    [STAThread]
    private static void Main(string[] args)
    {
        using var mutex = new Mutex(true, @"Local\StudyLock.Single", out var first);
        if (!first)
        {
            MessageBox.Show("공부 잠금이 이미 켜져 있어요. 화면 오른쪽 아래 알림 영역의 아이콘을 눌러 주세요.", "공부 잠금");
            return;
        }
        ApplicationConfiguration.Initialize();
        var store = new Store(Store.DefaultPath);
        Application.Run(new MainForm(store, startHidden: args.Contains("--tray")));
    }
}
