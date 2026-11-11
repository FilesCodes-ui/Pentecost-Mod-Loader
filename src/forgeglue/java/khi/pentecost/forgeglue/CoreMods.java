package khi.pentecost.forgeglue;

import cpw.mods.modlauncher.api.ITransformer;
import cpw.mods.modlauncher.api.ITransformerActivity;
import cpw.mods.modlauncher.api.ITransformerVotingContext;
import khi.pentecost.adapter.forge.ForgeRedirects;
import net.minecraftforge.coremod.CoreModProvider;
import net.minecraftforge.fml.loading.moddiscovery.ModFile;
import net.minecraftforge.forgespi.coremod.ICoreModFile;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Forge mods, and Forge itself, ship "coremods": small scripts that rewrite game classes as they load.
 * Forge's launcher runs them. Pentecost is the launcher here, so Pentecost runs them:
 * it hands every mod's scripts to Forge's own script engine and applies the results in its class loader.
 */
public final class CoreMods {
    private static volatile Map<String, Set<ITransformer<?>>> byClass = Map.of();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private static final cpw.mods.modlauncher.api.TypesafeMap BOARD = new cpw.mods.modlauncher.api.TypesafeMap();

    private CoreMods() {}

    /** Landing point for Forge code that asks its launcher for the shared settings board. */
    public static cpw.mods.modlauncher.api.TypesafeMap blackboard(cpw.mods.modlauncher.Launcher launcher) {
        return launcher != null ? launcher.blackboard() : BOARD;
    }

    /** Reads the scripts of every mod file. Called once, before the game starts. */
    static void load(List<ModFile> files) {
        try {
            CoreModProvider provider = new CoreModProvider();
            int scripts = 0;
            for (ModFile f : files) {
                for (ICoreModFile script : f.getCoreMods()) {
                    provider.addCoreMod(script);
                    scripts++;
                }
            }
            Map<String, Set<ITransformer<?>>> map = new HashMap<>();
            for (ITransformer<?> t : provider.getCoreModTransformers()) {
                for (ITransformer.Target target : t.targets()) {
                    map.computeIfAbsent(target.getClassName().replace('/', '.'), k -> new LinkedHashSet<>()).add(t);
                }
            }
            byClass = map;
            System.out.println("[Pentecost] Coremods: " + scripts + " script(s) rewrite " + map.size() + " class(es)");
        } catch (Throwable e) {
            System.out.println("[Pentecost] Coremods could not load: " + e);
            e.printStackTrace(System.out);
        }
    }

    static boolean has(String className) {
        return byClass.containsKey(className);
    }

    /** Runs every script that targets this class. A script that fails is reported once and skipped. */
    @SuppressWarnings("unchecked")
    static ClassNode apply(String className, ClassNode node) {
        Set<ITransformer<?>> list = byClass.get(className);
        if (list == null) {
            return node;
        }
        Context ctx = new Context(className);
        for (ITransformer<?> t : list) {
            try {
                for (ITransformer.Target target : new ArrayList<>(t.targets())) {
                    if (!target.getClassName().replace('/', '.').equals(className)) {
                        continue;
                    }
                    String kind = target.getTargetType().name();
                    // Scripts name members the way Forge's own game build does. The game here uses readable names.
                    String member = target.getElementName() == null ? null
                            : ForgeRedirects.mapSrgName(target.getElementName());
                    if (kind.equals("METHOD")) {
                        for (int i = 0; i < node.methods.size(); i++) {
                            MethodNode m = node.methods.get(i);
                            if (m.name.equals(member) && m.desc.equals(target.getElementDescriptor())) {
                                node.methods.set(i, ((ITransformer<MethodNode>) t).transform(m, ctx));
                            }
                        }
                    } else if (kind.equals("FIELD")) {
                        for (int i = 0; i < node.fields.size(); i++) {
                            FieldNode f = node.fields.get(i);
                            if (f.name.equals(member)) {
                                node.fields.set(i, ((ITransformer<FieldNode>) t).transform(f, ctx));
                            }
                        }
                    } else {
                        node = ((ITransformer<ClassNode>) t).transform(node, ctx);
                    }
                }
            } catch (Throwable e) {
                if (FAILED.add(className + "|" + t)) {
                    System.out.println("[Pentecost] A coremod failed on " + className + " and was skipped: " + e);
                }
            }
        }
        return node;
    }

    /** What a script may ask about the class it is rewriting. Scripts here only need the name. */
    private record Context(String className) implements ITransformerVotingContext {
        @Override
        public String getClassName() {
            return className;
        }

        @Override
        public boolean doesClassExist() {
            return true;
        }

        @Override
        public byte[] getInitialClassSha256() {
            return new byte[0];
        }

        @Override
        public List<ITransformerActivity> getAuditActivities() {
            return List.of();
        }

        @Override
        public String getReason() {
            return "classloading";
        }

        @Override
        public boolean applyFieldPredicate(FieldPredicate p) {
            return true;
        }

        @Override
        public boolean applyMethodPredicate(MethodPredicate p) {
            return true;
        }

        @Override
        public boolean applyClassPredicate(ClassPredicate p) {
            return true;
        }

        @Override
        public boolean applyInstructionPredicate(InsnPredicate p) {
            return true;
        }
    }
}
