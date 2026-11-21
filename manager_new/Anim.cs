using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Windows.Interop;
using System.Windows.Threading;

namespace Pentecost.Manager;

public static class Anim
{
    const int EnterSizeMove = 0x0231, ExitSizeMove = 0x0232;
    static readonly HashSet<EventHandler> subs = new();
    static readonly Stopwatch clock = Stopwatch.StartNew();
    static readonly AutoResetEvent wake = new(false);
    static Dispatcher? ui;
    static Thread? thread;
    static volatile bool running, active = true;
    static int pending;
    static bool fineTimer;

    [DllImport("winmm.dll")] static extern uint timeBeginPeriod(uint ms);
    [DllImport("winmm.dll")] static extern uint timeEndPeriod(uint ms);

    public static bool Sizing { get; private set; }
    public static int Fps { get; set; } = 60;
    public static Func<bool>? Paused;

    public static void Add(EventHandler h)
    {
        ui ??= Application.Current?.Dispatcher ?? Dispatcher.CurrentDispatcher;
        subs.Add(h);
        Update();
    }

    public static void Remove(EventHandler h) { subs.Remove(h); Update(); }

    static void Update()
    {
        var run = subs.Count > 0 && !Sizing;
        if (run == running) return;
        running = run;
        if (run && !fineTimer) { timeBeginPeriod(1); fineTimer = true; }
        if (!run && fineTimer) { timeEndPeriod(1); fineTimer = false; }
        if (run && thread == null)
        {
            thread = new Thread(Loop) { IsBackground = true, Name = "Anim", Priority = ThreadPriority.AboveNormal };
            thread.Start();
        }
        wake.Set();
    }

    static void Loop()
    {
        var next = clock.Elapsed.TotalSeconds;
        while (true)
        {
            if (!running)
            {
                wake.WaitOne();
                next = clock.Elapsed.TotalSeconds;
                continue;
            }
            var gap = active ? 1.0 / Math.Clamp(Fps, 15, 240) : 1.0 / 20;
            next += gap;
            var now = clock.Elapsed.TotalSeconds;
            if (next < now - gap) next = now;
            var wait = next - now;
            if (wait > 0.0015) wake.WaitOne(TimeSpan.FromSeconds(wait - 0.001));
            while (clock.Elapsed.TotalSeconds < next) Thread.SpinWait(40);
            if (!running || Interlocked.Exchange(ref pending, 1) == 1) continue;
            ui?.BeginInvoke(DispatcherPriority.Render, Tick);
        }
    }

    static void Tick()
    {
        Interlocked.Exchange(ref pending, 0);
        if (!running || Paused?.Invoke() == true) return;
        foreach (var h in subs.ToArray()) h(null, EventArgs.Empty);
    }

    public static void Hook(Window w)
    {
        if (PresentationSource.FromVisual(w) is HwndSource src)
            src.AddHook((IntPtr hwnd, int msg, IntPtr wp, IntPtr lp, ref bool handled) =>
            {
                if (msg == EnterSizeMove) { Sizing = true; Update(); }
                else if (msg == ExitSizeMove)
                {
                    Sizing = false;
                    Update();
                    foreach (var h in subs.ToArray()) h(null, EventArgs.Empty);
                }
                return IntPtr.Zero;
            });
        w.Activated += (_, _) => { active = true; wake.Set(); };
        w.Deactivated += (_, _) => { active = false; };
        w.StateChanged += (_, _) =>
        {
            Sizing = w.WindowState == WindowState.Minimized;
            Update();
        };
    }
}
