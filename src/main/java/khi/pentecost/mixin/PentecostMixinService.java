package khi.pentecost.mixin;

import khi.pentecost.loader.GameClassLoader;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleVirtual;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.IClassBytecodeProvider;
import org.spongepowered.asm.service.IClassProvider;
import org.spongepowered.asm.service.IClassTracker;
import org.spongepowered.asm.service.IMixinAuditTrail;
import org.spongepowered.asm.service.IMixinInternal;
import org.spongepowered.asm.service.ITransformerProvider;
import org.spongepowered.asm.service.MixinServiceAbstract;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.List;

/** Connects the Mixin library to the Pentecost class loader. Pentecost is the host. No other loader runs. */
public class PentecostMixinService extends MixinServiceAbstract implements IClassProvider, IClassBytecodeProvider {
    static volatile GameClassLoader loader;
    static volatile IMixinTransformerFactory factory;

    public String getName() {
        return "Pentecost";
    }

    public boolean isValid() {
        return true;
    }

    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_8;
    }

    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory f) {
            factory = f;
        }
        super.offer(internal);
    }

    protected ILogger createLogger(String name) {
        return new MixinLog(name);
    }

    public IClassProvider getClassProvider() {
        return this;
    }

    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    public ITransformerProvider getTransformerProvider() {
        return null;
    }

    public IClassTracker getClassTracker() {
        return null;
    }

    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    public Collection<String> getPlatformAgents() {
        return List.of("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
    }

    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleVirtual(getName());
    }

    public InputStream getResourceAsStream(String name) {
        return loader.getResourceAsStream(name);
    }

    // ---- IClassProvider

    public URL[] getClassPath() {
        return new URL[0];
    }

    public Class<?> findClass(String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }

    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, loader);
    }

    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, PentecostMixinService.class.getClassLoader());
    }

    // ---- IClassBytecodeProvider

    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true, 0);
    }

    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        byte[] bytes = loader.preMixinBytes(name);
        if (bytes == null) {
            throw new ClassNotFoundException(name);
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, readerFlags);
        return node;
    }
}
