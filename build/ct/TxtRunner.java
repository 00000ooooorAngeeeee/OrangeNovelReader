import com.modu.reader.util.TxtParser;
import java.io.*;
import java.nio.file.*;

public class TxtRunner {
    public static void main(String[] a) throws Exception {
        byte[] data = Files.readAllBytes(Paths.get(a[0]));
        String raw = TxtParser.decode(data);
        String name = new File(a[0]).getName().replaceAll("\\.[Tt][Xx][Tt]$", "");
        TxtParser.Parsed p = TxtParser.parse(raw, name);
        System.out.println("chars=" + raw.length() + " chapters=" + p.chapters.size());
        int empty = 0, total = 0, maxN = 0;
        for (TxtParser.Ch c : p.chapters) {
            if (c.text == null || c.text.isEmpty()) empty++;
            else total += c.text.length();
            if (c.num > maxN) maxN = c.num;
        }
        System.out.println("empty=" + empty + " textChars=" + total + " maxNum=" + maxN);
        int n = p.chapters.size();
        int[] idx = {0, 1, 2, n / 2, n - 3, n - 2, n - 1};
        for (int i : idx) {
            if (i < 0 || i >= n) continue;
            TxtParser.Ch c = p.chapters.get(i);
            String head = c.text == null ? "" : c.text.substring(0, Math.min(28, c.text.length())).replace("\n", " ");
            System.out.printf("[%d] num=%d len=%d title=%s | %s%n", i, c.num,
                    c.text == null ? 0 : c.text.length(), c.title, head);
        }
    }
}
