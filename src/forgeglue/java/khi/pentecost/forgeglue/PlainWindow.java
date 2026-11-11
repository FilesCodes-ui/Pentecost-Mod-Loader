package khi.pentecost.forgeglue;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraftforge.fml.loading.ImmediateWindowProvider;
import org.lwjgl.glfw.GLFW;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Forge can show its own early loading window. Pentecost skips it and opens the game window the vanilla way.
 * Forge's game patches still ask this provider for the window and the loading overlay, so it supplies both.
 */
public final class PlainWindow implements ImmediateWindowProvider {
    @Override
    public String name() {
        return "pentecost";
    }

    @Override
    public Runnable initialize(String[] arguments) {
        return () -> { };
    }

    @Override
    public void updateFramebufferSize(IntConsumer width, IntConsumer height) {
    }

    @Override
    public long setupMinecraftWindow(IntSupplier width, IntSupplier height, Supplier<String> title, LongSupplier monitor) {
        return GLFW.glfwCreateWindow(width.getAsInt(), height.getAsInt(), title.get(), monitor.getAsLong(), 0L);
    }

    @Override
    public boolean positionWindow(Optional<Object> monitor, IntConsumer widthSetter, IntConsumer heightSetter,
                                  IntConsumer xSetter, IntConsumer ySetter) {
        return false;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Supplier<T> loadingOverlay(Supplier<?> mc, Supplier<?> ri, Consumer<Optional<Throwable>> ex, boolean fade) {
        return () -> (T) new LoadingOverlay((Minecraft) mc.get(), (ReloadInstance) ri.get(), ex, fade);
    }

    @Override
    public void updateModuleReads(ModuleLayer layer) {
    }

    @Override
    public void periodicTick() {
    }

    @Override
    public String getGLVersion() {
        return "3.2";
    }
}
