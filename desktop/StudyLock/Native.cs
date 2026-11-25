using System.Runtime.InteropServices;
using System.Text;

namespace StudyLock;

/// <summary>지금 앞에 있는 창의 제목·프로그램을 알아내고, 브라우저 탭을 닫는 윈도우 기능.</summary>
internal static class Native
{
    [DllImport("user32.dll")] private static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] private static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll")] private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] private static extern void keybd_event(byte vk, byte scan, uint flags, UIntPtr extra);

    private const byte VK_CONTROL = 0x11;
    private const byte VK_W = 0x57;
    private const uint KEYEVENTF_KEYUP = 0x2;

    public static IntPtr Foreground() => GetForegroundWindow();

    public static string Title(IntPtr hWnd)
    {
        var sb = new StringBuilder(512);
        GetWindowText(hWnd, sb, sb.Capacity);
        return sb.ToString();
    }

    /// <summary>창을 띄운 프로그램 이름(소문자, .exe 없이). 알 수 없으면 "".</summary>
    public static string ProcessName(IntPtr hWnd)
    {
        GetWindowThreadProcessId(hWnd, out var pid);
        try
        {
            using var p = System.Diagnostics.Process.GetProcessById((int)pid);
            return p.ProcessName.ToLowerInvariant();
        }
        catch
        {
            return "";
        }
    }

    /// <summary>앞에 있는 창에 Ctrl+W(브라우저: 지금 탭 닫기)를 보낸다.</summary>
    public static void CtrlW()
    {
        keybd_event(VK_CONTROL, 0, 0, UIntPtr.Zero);
        keybd_event(VK_W, 0, 0, UIntPtr.Zero);
        keybd_event(VK_W, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
        keybd_event(VK_CONTROL, 0, KEYEVENTF_KEYUP, UIntPtr.Zero);
    }
}
