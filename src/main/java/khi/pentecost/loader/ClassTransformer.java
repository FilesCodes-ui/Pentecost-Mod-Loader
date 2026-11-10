package khi.pentecost.loader;

/** Rewrites class bytes before the game defines them. The remapper and Mixin host plug in here. */
public interface ClassTransformer {
    byte[] transform(String className, byte[] bytes);
}
