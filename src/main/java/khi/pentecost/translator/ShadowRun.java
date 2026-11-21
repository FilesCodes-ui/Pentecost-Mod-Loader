package khi.pentecost.translator;

import khi.pentecost.boot.Dirs;
import khi.pentecost.boot.Log;
import khi.pentecost.control.Settings;
import khi.pentecost.core.PentecostRuntime;
import khi.pentecost.mods.ModInfo;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ShadowRun {
    private static volatile boolean started;

    private ShadowRun() {}

    public static void maybeStart() {
        if (started || !Settings.on(Settings.MESSENGER)) {
            return;
        }
        started = true;
        Thread t = new Thread(() -> {
            try {
                List<ModInfo> mods = PentecostRuntime.mods.isEmpty() ? PentecostRuntime.allMods : PentecostRuntime.mods;
                ClassLoader cl = PentecostRuntime.loader != null ? PentecostRuntime.loader : ShadowRun.class.getClassLoader();
                Shadow.Result r = Shadow.analyze(mods, name -> cl.getResource(name + ".class") != null);
                Path file = Dirs.run().resolve("logs").resolve("pentecost-translator-shadow.txt");
                Files.createDirectories(file.getParent());
                Files.writeString(file, Shadow.text(r), StandardCharsets.UTF_8);
                Log.info("Shadow translator read " + r.mods().size() + " mods in " + r.millis() + " ms. Report: " + file);
                khi.pentecost.messenger.ThreadPlan.Plan plan = khi.pentecost.messenger.ThreadPlan.build(mods,
                        khi.pentecost.messenger.ThreadPlan.defaultThreads(), null);
                Path planFile = file.resolveSibling("pentecost-thread-plan.txt");
                Files.writeString(planFile, khi.pentecost.messenger.ThreadPlan.text(plan), StandardCharsets.UTF_8);
                Log.info("Mod thread plan made in " + plan.millis() + " ms. Report: " + planFile);
                if (khi.pentecost.messenger.ModThreads.owners == null) {
                    khi.pentecost.messenger.ModThreads.owners = khi.pentecost.core.ModOwners.build(mods, cl);
                }
                if (khi.pentecost.messenger.ModThreads.shared == null) {
                    khi.pentecost.messenger.ModThreads.shared = new khi.pentecost.messenger.ModThreads(plan, 2000,
                            khi.pentecost.messenger.Messenger.get()::note);
                }
            } catch (Throwable e) {
                Log.error("Shadow translator failed. Nothing in the game was changed: " + e);
            }
        }, "pentecost-shadow-translator");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }
}
