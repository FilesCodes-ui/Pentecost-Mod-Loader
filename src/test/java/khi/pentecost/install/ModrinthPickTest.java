package khi.pentecost.install;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ModrinthPickTest {
    private static final String JSON = """
            [
              {"version_type":"beta","files":[{"primary":true,"filename":"oculus-mc1.20.1-2.0.0-beta.jar","url":"https://cdn.modrinth.com/data/x/b.jar","hashes":{"sha1":"bbbb"}}],"dependencies":[]},
              {"version_type":"release","files":[
                 {"primary":false,"filename":"oculus-sources.jar","url":"https://cdn.modrinth.com/data/x/s.jar","hashes":{"sha1":"ssss"}},
                 {"primary":true,"filename":"oculus-mc1.20.1-1.7.0.jar","url":"https://cdn.modrinth.com/data/x/a.jar","hashes":{"sha1":"aaaa"}}],
               "dependencies":[{"project_id":"emb123","dependency_type":"required"},{"project_id":"opt456","dependency_type":"optional"},{"project_id":null,"dependency_type":"required"}]}
            ]
            """;

    @Test
    void picksNewestReleasePrimaryFileAndRequiredDependencies() throws IOException {
        ModrinthPick p = ModrinthPick.of(JSON);
        assertEquals("oculus-mc1.20.1-1.7.0.jar", p.filename());
        assertEquals("https://cdn.modrinth.com/data/x/a.jar", p.url());
        assertEquals("aaaa", p.sha1());
        assertEquals(java.util.List.of("emb123"), p.required());
    }

    @Test
    void fallsBackToTheFirstBuildWhenThereIsNoRelease() throws IOException {
        String only = "[{\"version_type\":\"beta\",\"files\":[{\"primary\":true,\"filename\":\"a-1.jar\",\"url\":\"https://cdn.modrinth.com/a.jar\",\"hashes\":{\"sha1\":\"1\"}}]}]";
        assertEquals("a-1.jar", ModrinthPick.of(only).filename());
    }

    @Test
    void refusesEmptyListsAndUnsafeNames() {
        assertThrows(IOException.class, () -> ModrinthPick.of("[]"));
        assertThrows(IOException.class, () -> ModrinthPick.of("not json"));
        assertThrows(IOException.class, () -> ModrinthPick.safeJarName("..\\evil.jar"));
        assertThrows(IOException.class, () -> ModrinthPick.safeJarName("a/b.jar"));
    }

    @Test
    void baseNameDropsTheVersion() {
        assertEquals("embeddium", ModrinthPick.baseName("embeddium-0.3.31+mc1.20.1.jar"));
        assertEquals("embeddium", ModrinthPick.baseName("Embeddium-0.2.jar"));
        assertEquals("oculus", ModrinthPick.baseName("oculus-mc1.20.1-1.7.0.jar"));
    }
}
