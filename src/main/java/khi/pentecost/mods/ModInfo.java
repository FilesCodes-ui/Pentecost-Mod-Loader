package khi.pentecost.mods;

import java.nio.file.Path;
import java.util.List;

/** One mod, in the same shape whatever loader it was written for. */
public record ModInfo(String id, String name, String version, LoaderKind loader, Path jar,
                      List<String> mainEntrypoints, List<String> clientEntrypoints, List<String> depends) {

    public String label() {
        return name + " " + version + " (" + loader.label + ", id " + id + ")";
    }
}
