namespace Pentecost.Manager;

public static class Anim
{
    public static bool Sizing => false;

    public static void Add(EventHandler h) => CompositionTarget.Rendering += h;

    public static void Remove(EventHandler h) => CompositionTarget.Rendering -= h;
}
