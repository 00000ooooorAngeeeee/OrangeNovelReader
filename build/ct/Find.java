import com.modu.reader.epub.ChapterTitles;
import com.modu.reader.epub.EpubBook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class Find {
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8));
        String bt = r.readLine(); boolean rel = "1".equals(r.readLine());
        List<EpubBook.Chapter> list = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\t", -1);
            EpubBook.Chapter c = new EpubBook.Chapter();
            c.index = list.size(); c.path = p[0];
            c.title = p[1].isEmpty() ? null : p[1];
            if (p.length > 2) c.bytes = Long.parseLong(p[2]);
            list.add(c);
        }
        ChapterTitles.normalize(list, bt, rel);
        for (EpubBook.Chapter c : list) {
            if (c.title != null && c.title.contains("总结")) {
                System.out.println(c.body + " | " + c.title + " | vol=" + c.volume);
            }
        }
    }
}
