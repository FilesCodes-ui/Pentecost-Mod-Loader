package khi.pentecost.loader;

public interface ClassTransformer {
    byte[] transform(String className, byte[] bytes);
}
