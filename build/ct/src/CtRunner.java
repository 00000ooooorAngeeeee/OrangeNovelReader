import com.modu.reader.epub.ChapterTitles;
import com.modu.reader.epub.EpubBook;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class CtRunner {
    public static void main(String[] a) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(a[0]), StandardCharsets.UTF_8));
        String bookTitle = r.readLine();
        boolean reliable = "1".equals(r.readLine());
        List<EpubBook.Chapter> list = new ArrayList<>();
        String line;
        while ((line = r.readLine()) != null) {
            String[] p = line.split("\t", -1);
            EpubBook.Chapter c = new EpubBook.Chapter();
            c.index = list.size();
            c.path = p[0];
            c.title = p[1].isEmpty() ? null : p[1];
            if (p.length > 2) c.bytes = Long.parseLong(p[2]);
            list.add(c);
        }
        ChapterTitles.normalize(list, bookTitle, reliable);
        int body = 0, vols = 0;
        String vol = null;
        for (EpubBook.Chapter c : list) {
            if (c.volume != null && !c.volume.equals(vol)) { vols++; vol = c.volume; }
            if (c.body) body++;
        }
        System.out.println("body=" + body + " volumes=" + vols);
        vol = null;
        int shown = 0;
        for (EpubBook.Chapter c : list) {
            if (c.volume != null && !c.volume.equals(vol)) {
                vol = c.volume;
                System.out.println("  【" + vol + "】");
                shown = 0;
            }
            if (c.body && shown < 3) {
                System.out.println("     [" + c.index + "] " + c.title);
                shown++;
            }
        }
    }
}
