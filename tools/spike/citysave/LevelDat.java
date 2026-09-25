import java.io.FileInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Prints the level.dat fields the report has to quote: version, name, spawn, cheats, gamerules. */
public final class LevelDat {
    public static void main(String[] args) throws Exception {
        for (String arg : args) {
            try (InputStream in = new FileInputStream(arg)) {
                Map<String, Object> root = NbtReader.compound(NbtReader.readGzip(in));
                Map<String, Object> data = NbtReader.compound(root.get("Data"));
                System.out.println("file=" + arg);
                System.out.println("DataVersion=" + data.get("DataVersion"));
                System.out.println("Version=" + data.get("Version"));
                System.out.println("LevelName=" + data.get("LevelName"));
                System.out.println("LastPlayed=" + data.get("LastPlayed"));
                System.out.println("allowCommands=" + data.get("allowCommands"));
                System.out.println("GameType=" + data.get("GameType"));
                System.out.println("Difficulty=" + data.get("Difficulty"));
                System.out.println("hardcore=" + data.get("hardcore"));
                System.out.println("initialized=" + data.get("initialized"));
                System.out.println("SpawnX/Y/Z=" + data.get("SpawnX") + "," + data.get("SpawnY") + "," + data.get("SpawnZ"));
                System.out.println("SpawnAngle=" + data.get("SpawnAngle"));
                Object gen = data.get("WorldGenSettings");
                if (gen instanceof Map<?, ?> g) {
                    Map<String, Object> gm = NbtReader.compound(g);
                    System.out.println("seed=" + gm.get("seed"));
                    System.out.println("generate_features=" + gm.get("generate_features"));
                    System.out.println("bonus_chest=" + gm.get("bonus_chest"));
                    System.out.println("dimensions=" + String.valueOf(gm.get("dimensions")).substring(0, Math.min(120, String.valueOf(gm.get("dimensions")).length())));
                }
                Object rules = data.get("GameRules");
                if (rules instanceof Map<?, ?> r) {
                    NbtReader.compound(r).forEach((k, v) -> System.out.println("  gamerule " + k + " = " + v));
                }
                System.out.println("DataPacks=" + String.valueOf(data.get("DataPacks")).substring(0, Math.min(200, String.valueOf(data.get("DataPacks")).length())));
                Object ver = data.get("Version");
                if (ver instanceof Map<?, ?> v) {
                    System.out.println("VersionName=" + NbtReader.compound(v).get("Name") + " id=" + NbtReader.compound(v).get("Id"));
                }
                System.out.println("playerData keys present=" + (data.get("Player") != null));
            }
        }
    }
}
