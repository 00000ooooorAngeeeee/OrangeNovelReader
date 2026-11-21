import com.modu.reader.util.TxtParser;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class Miss {
    public static void main(String[] a) throws Exception {
        String raw = TxtParser.decode(Files.readAllBytes(Paths.get(a[0])));
        TxtParser.Parsed p = TxtParser.parse(raw, "x");
        Set<Integer> got = new HashSet<>();
        for (TxtParser.Ch c : p.chapters) got.add(c.num);
        StringBuilder sb = new StringBuilder("missing: ");
        for (int i = 1; i <= 1497; i++) if (!got.contains(i)) sb.append(i).append(' ');
        System.out.println(sb);
    }
}
