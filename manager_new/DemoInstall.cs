namespace Pentecost.Manager;

public static class DemoInstall
{
    public static async Task Manager(Action<string> say)
    {
        say("Copying the Manager into " + SelfInstall.Target);
        await Task.Delay(700);
        say("Adding shortcuts to the desktop and the Start menu");
        await Task.Delay(500);
        say("Adding Pentecost Manager to the Windows app list");
        await Task.Delay(400);
    }

    public static async Task<string?> Pentecost(bool worlds, Action<string, double> progress)
    {
        const int installerMb = 63;
        progress("Downloading the installer", 0);
        for (int mb = 0; mb <= installerMb; mb++)
        {
            progress($"Downloading the installer, {mb} of {installerMb} MB", 0.6 * mb / installerMb);
            await Task.Delay(140);
        }
        progress("Installing Pentecost", 0.65);
        await Task.Delay(400);

        progress("Installing Pentecost. Copying program files", 0.7);
        await Task.Delay(900);
        progress("Installing Pentecost. Adding Pentecost to the launcher", 0.7);
        await Task.Delay(700);
        foreach (var mod in new[] { "embeddium", "oculus" })
        {
            progress("Installing Pentecost. Getting Embeddium and Oculus: " + mod, 0.7);
            await Task.Delay(600);
        }
        if (worlds)
        {
            var sizes = new[] { 68, 123, 70, 85, 67, 59 };
            for (int i = 0; i < sizes.Length; i++)
            {
                for (int got = 0; got <= sizes[i]; got = Math.Min(sizes[i], got + 17))
                {
                    progress($"Installing Pentecost. Downloading the main menu worlds, World {i + 1} of {sizes.Length}: {got} of {sizes[i]} MB", 0.7);
                    await Task.Delay(110);
                    if (got == sizes[i]) break;
                }
            }
        }
        progress("Done", 1);
        await Task.Delay(300);
        return null;
    }
}
