using System.IO;
using System.Text.Json.Nodes;

namespace Pentecost.Manager;

public static class DevInbox
{
    public static string Dir => Directory.CreateDirectory(Path.Combine(Paths.Data, "dev-inbox")).FullName;

    public static void NoteExe()
    {
        try { File.WriteAllText(Path.Combine(Paths.Data, "manager-path.txt"), Environment.ProcessPath ?? ""); } catch { }
    }

    public static async Task<string> Run()
    {
        if (!DevAccess.Unlocked) return "error: The developer key is not on this computer.";
        Feed.LoadCached();
        await Feed.Refresh();
        var files = Directory.GetFiles(Dir, "*.json").OrderBy(f => f).ToList();
        if (files.Count == 0) return "ok: nothing to do";
        var draft = (JsonObject)Feed.Current.DeepClone();
        draft["mods"] ??= new JsonObject();
        draft["posts"] ??= new JsonArray();
        draft["kill"] ??= new JsonArray();
        var applied = new List<string>();
        var said = new List<string>();
        foreach (var f in files)
        {
            JsonObject? o;
            try { o = JsonNode.Parse(File.ReadAllText(f)) as JsonObject; }
            catch { o = null; }
            if (o == null) { applied.Add(f); continue; }
            var op = o["op"]?.ToString();
            if (op is "addCape" or "removeCape")
            {
                var id = (o["uuid"]?.ToString() ?? "").Replace("-", "").ToLowerInvariant();
                if (System.Text.RegularExpressions.Regex.IsMatch(id, "^[0-9a-f]{32}$"))
                {
                    if (draft["capes"] is not JsonArray capes) { capes = new JsonArray(); draft["capes"] = capes; }
                    foreach (var hit in capes.Where(x => x?.ToString() == id).ToList()) capes.Remove(hit);
                    if (op == "addCape") capes.Add(id);
                    said.Add((op == "addCape" ? "cape on " : "cape off ") + id);
                }
                applied.Add(f);
                continue;
            }
            var tex = o["texture"]?.ToString() ?? "";
            var name = (o["name"]?.ToString() ?? "").Trim();
            if (!System.Text.RegularExpressions.Regex.IsMatch(tex, "^[0-9a-f]{20,80}$")) { applied.Add(f); continue; }
            if (draft["skins"] is not JsonArray arr) { arr = new JsonArray(); draft["skins"] = arr; }
            foreach (var hit in arr.OfType<JsonObject>().Where(x => x["texture"]?.ToString() == tex).ToList()) arr.Remove(hit);
            if (op == "addPick")
            {
                arr.Add(new JsonObject { ["name"] = name.Length == 0 ? "Pick" : name[..Math.Min(40, name.Length)], ["texture"] = tex });
                said.Add("added " + name);
            }
            else if (op == "removePick") said.Add("removed " + name);
            applied.Add(f);
        }
        if (said.Count == 0)
        {
            foreach (var f in applied) try { File.Delete(f); } catch { }
            return "ok: nothing to do";
        }
        var err = await Feed.Publish(draft, _ => { });
        if (err != null) return "error: " + err;
        foreach (var f in applied) try { File.Delete(f); } catch { }
        return "ok: " + string.Join(", ", said);
    }
}
