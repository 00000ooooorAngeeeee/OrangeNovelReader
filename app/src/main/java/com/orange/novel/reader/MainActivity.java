package com.orange.novel.reader;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.text.InputType;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.novel.reader.db.Book;
import com.orange.novel.reader.db.BookStore;
import java.util.Locale;
import com.orange.novel.reader.ui.CoverLoader;
import com.orange.novel.reader.ui.BookAdapter;
import com.orange.novel.reader.ui.ShelfItem;
import com.orange.novel.reader.util.CoverManager;
import com.orange.novel.reader.util.EpubImporter;
import com.orange.novel.reader.util.TxtImporter;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity implements BookAdapter.Listener {

    private RecyclerView grid;
    private BookAdapter adapter;
    private View emptyView;
    private ActivityResultLauncher<String[]> picker;
    private Dialog importDialog;
    private TextView importText;

    private TextView btnCancelSel, btnSelectAll, shelfTitle, barPinText;
    private ImageButton btnImport, btnViewMode, btnBackAll;
    private View actionBar, barMove, barPin, barRename, barDetail, barDelete;
    private long currentGroup;
    private long lastBackAt;
    private String currentGroupName = "";
    private final List<String[]> groups = new ArrayList<>();
    private static final String PREF = "shelf";
    private ActivityResultLauncher<String> imagePicker;

    private List<Book> books = new ArrayList<>();
    private boolean selecting;
    private final LinkedHashSet<Long> selected = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(false);
        wic.setAppearanceLightNavigationBars(false);
        setContentView(R.layout.activity_main);

        grid = findViewById(R.id.bookGrid);
        emptyView = findViewById(R.id.emptyView);
        shelfTitle = findViewById(R.id.shelfTitle);
        btnCancelSel = findViewById(R.id.btnCancelSel);
        btnSelectAll = findViewById(R.id.btnSelectAll);
        btnViewMode = findViewById(R.id.btnViewMode);
        btnBackAll = findViewById(R.id.btnBackAll);
        actionBar = findViewById(R.id.actionBar);
        barMove = findViewById(R.id.barMove);
        barPin = findViewById(R.id.barPin);
        barRename = findViewById(R.id.barRename);
        barDetail = findViewById(R.id.barDetail);
        barDelete = findViewById(R.id.barDelete);
        barPinText = findViewById(R.id.barPinText);
        btnImport = findViewById(R.id.btnImport);

        adapter = new BookAdapter(this);
        int savedMode = getSharedPreferences(PREF, MODE_PRIVATE)
                .getInt("viewMode", BookAdapter.MODE_GRID);
        adapter.setMode(savedMode);
        applyLayoutManager(savedMode);
        btnViewMode.setImageResource(savedMode == BookAdapter.MODE_LIST
                ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        btnViewMode.setOnClickListener(v -> toggleViewMode());
        grid.setAdapter(adapter);
        applyInsets();

        picker = registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), uris -> {
            if (uris != null && !uris.isEmpty()) importUris(uris);
        });
        imagePicker = registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) loadCoverFromUri(uri);
        });

        View.OnClickListener openPicker = v -> {
            try {
                // 用 */* 而不是 application/epub+zip：很多文件管理器把 epub 报成
                // application/octet-stream，收窄 MIME 会让文件变灰不可选
                picker.launch(new String[]{"*/*"});
            } catch (Exception e) {
                Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_SHORT).show();
            }
        };
        btnImport.setOnClickListener(openPicker);
        findViewById(R.id.btnEmptyImport).setOnClickListener(openPicker);

        barMove.setOnClickListener(v -> showMoveSheet());
        barPin.setOnClickListener(v -> togglePin());
        barRename.setOnClickListener(v -> renameBook());
        barDetail.setOnClickListener(v -> showBookDetail());
        barDelete.setOnClickListener(v -> confirmDeleteSelected());
        btnCancelSel.setOnClickListener(v -> exitSelection());
        btnSelectAll.setOnClickListener(v -> toggleSelectAll());
        btnBackAll.setOnClickListener(v -> {
            currentGroup = 0;
            currentGroupName = "";
            refresh();
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (selecting) {
                    exitSelection();
                    return;
                }
                if (currentGroup != 0) {
                    // 在分组里：先回到书架根，而不是直接退出
                    currentGroup = 0;
                    currentGroupName = "";
                    refresh();
                    return;
                }
                // 书架根：再滑一次才退出
                long now = System.currentTimeMillis();
                if (now - lastBackAt < 2000) {
                    finish();
                    return;
                }
                lastBackAt = now;
                Toast.makeText(MainActivity.this, "再滑动一次返回键退出应用", Toast.LENGTH_SHORT).show();
            }
        });

        handleViewIntent(getIntent(), savedInstanceState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleViewIntent(intent, null);
    }

    private void handleViewIntent(Intent intent, Bundle state) {
        // 重建（如旋转）时不再重复导入
        if (state != null) return;
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return;
        Uri data = intent.getData();
        if (data == null) return;
        setIntent(new Intent());
        importUris(Collections.singletonList(data));
    }

    private void applyInsets() {
        final float d = getResources().getDisplayMetrics().density;
        final View header = findViewById(R.id.header);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(Math.round(20 * d), bars.top + Math.round(8 * d), Math.round(6 * d),
                    Math.round(8 * d));
            grid.setPadding(Math.round(14 * d), Math.round(6 * d), Math.round(14 * d),
                    Math.round(24 * d));
            actionBar.setPadding(0, Math.round(8 * d), 0, bars.bottom + Math.round(4 * d));
            return insets;
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ------------------------------------------------------------- 列表

    private void applyLayoutManager(int mode) {
        if (mode == BookAdapter.MODE_LIST) {
            grid.setLayoutManager(new LinearLayoutManager(this));
        } else {
            grid.setLayoutManager(new GridLayoutManager(this, 3));
        }
    }

    private void toggleViewMode() {
        int next = adapter.getMode() == BookAdapter.MODE_LIST
                ? BookAdapter.MODE_GRID : BookAdapter.MODE_LIST;
        adapter.setMode(next);
        applyLayoutManager(next);
        btnViewMode.setImageResource(next == BookAdapter.MODE_LIST
                ? R.drawable.ic_view_grid : R.drawable.ic_view_list);
        getSharedPreferences(PREF, MODE_PRIVATE).edit().putInt("viewMode", next).apply();
        grid.scrollToPosition(0);
    }

    private void refresh() {
        if (adapter == null) return;
        BookStore store = BookStore.get(this);
        books = store.all(currentGroup);
        if (selecting) {
            // 丢掉已经不存在的选中项
            Set<Long> alive = new HashSet<>();
            for (Book b : books) alive.add(b.id);
            selected.retainAll(alive);
            if (selected.isEmpty()) selecting = false;
        }

        List<ShelfItem> items = new ArrayList<>();
        List<ShelfItem> rest = new ArrayList<>();
        // 置顶永远在最前（分组不会把它顶下去）
        for (Book b : books) {
            ShelfItem it = ShelfItem.book(b);
            if (b.pinned) items.add(it);
            else rest.add(it);
        }
        // 分组与普通书籍一起按「最近活跃」排序；同一时刻书籍优先，分组往下排
        if (currentGroup == 0) {
            java.util.Map<Long, Long> gts = store.groupTimestamps();
            for (String[] g : store.groups()) {
                long id = Long.parseLong(g[0]);
                Long ts = gts.get(id);
                long created = g.length > 2 ? Long.parseLong(g[2]) : 0;
                rest.add(ShelfItem.group(id, g[1], store.countInGroup(id),
                        store.groupCovers(id, 4), ts != null ? ts : created));
            }
        }
        java.util.Collections.sort(rest, (a, b) -> {
            if (a.sortTs != b.sortTs) return Long.compare(b.sortTs, a.sortTs);
            if (a.type != b.type) return a.type == ShelfItem.TYPE_BOOK ? -1 : 1;
            return 0;
        });
        items.addAll(rest);

        adapter.submit(items);
        adapter.setSelection(selecting, selected);

        boolean empty = items.isEmpty();
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (empty) {
            boolean inGroup = currentGroup != 0;
            ((TextView) findViewById(R.id.tvEmptyTitle)).setText(
                    inGroup ? "这个分组还没有书" : "书架空空如也");
            ((TextView) findViewById(R.id.tvEmptyHint)).setText(inGroup
                    ? "长按书籍 → 移动分组，把书整理进来"
                    : "把 EPUB / TXT 文件导入书架，即可开始阅读\n解析章节、目录与插图，自动记住阅读位置");
            findViewById(R.id.btnEmptyImport).setVisibility(inGroup ? View.GONE : View.VISIBLE);
        }
        updateHeader();
    }

    private void setBarEnabled(View v, boolean on) {
        v.setAlpha(on ? 1f : 0.35f);
        v.setEnabled(on);
    }

    private void updateHeader() {
        btnSelectAll.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnCancelSel.setVisibility(selecting ? View.VISIBLE : View.GONE);
        actionBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        btnBackAll.setVisibility(!selecting && currentGroup != 0 ? View.VISIBLE : View.GONE);
        btnViewMode.setVisibility(selecting ? View.GONE : View.VISIBLE);
        btnImport.setVisibility(selecting ? View.GONE : View.VISIBLE);

        if (selecting) {
            shelfTitle.setText("已选 " + selected.size() + " 项");
            shelfTitle.setTextSize(17);
            shelfTitle.setGravity(Gravity.CENTER);
            btnSelectAll.setText(!books.isEmpty() && selected.size() >= books.size() ? "取消全选" : "全选");
            boolean one = selected.size() == 1;
            setBarEnabled(barPin, one);
            setBarEnabled(barRename, one);
            setBarEnabled(barDetail, one);
            setBarEnabled(barMove, !selected.isEmpty());
            Book single = one ? byId(selected.iterator().next()) : null;
            barPinText.setText(single != null && single.pinned ? "取消置顶" : "置顶");
        } else {
            shelfTitle.setText(currentGroup == 0 ? getString(R.string.shelf) : currentGroupName);
            shelfTitle.setTextSize(21);
            shelfTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        }
    }

    // ------------------------------------------------------------- 分组

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void createGroup(final java.util.function.Consumer<Long> after) {
        final EditText et = new EditText(this);
        et.setHint("分组名，如「在读」「已完」");
        et.setTextColor(0xFFE9E9EA);
        et.setHintTextColor(0xFF8C8C94);
        et.setSingleLine(true);
        et.setTextSize(15);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("新建分组")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("创建", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(this, "分组名不能为空", Toast.LENGTH_SHORT).show();
                return;
            }
            if (name.length() > 12) name = name.substring(0, 12);
            long id = BookStore.get(this).addGroup(name);
            dlg.dismiss();
            if (after != null) after.accept(id);
        }));
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    private void showGroupMenu(long id, String name) {
        new AlertDialog.Builder(this)
                .setTitle(name)
                .setItems(new String[]{"重命名", "删除分组"}, (d, which) -> {
                    if (which == 0) renameGroup(id, name);
                    else deleteGroup(id, name);
                })
                .show();
    }

    private void renameGroup(long id, String old) {
        final EditText et = new EditText(this);
        et.setText(old);
        et.setSelection(et.getText().length());
        et.setTextColor(0xFFE9E9EA);
        et.setSingleLine(true);
        et.setTextSize(15);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("重命名分组")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("保存", null)
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String name = et.getText().toString().trim();
            if (name.isEmpty()) return;
            BookStore.get(this).renameGroup(id, name);
            dlg.dismiss();
            if (currentGroup == id) currentGroupName = name;
            refresh();
        }));
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    private void deleteGroup(long id, String name) {
        int n = BookStore.get(this).countInGroup(id);
        new AlertDialog.Builder(this)
                .setTitle("删除分组")
                .setMessage(n > 0
                        ? "「" + name + "」里有 " + n + " 本书，删除分组后这些书会回到书架，不会丢失。"
                        : "确定删除「" + name + "」吗？")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> {
                    BookStore.get(this).deleteGroup(id);
                    if (currentGroup == id) {
                        currentGroup = 0;
                        currentGroupName = "";
                    }
                    refresh();
                })
                .show();
    }

    /** 底部「移动分组」面板：新建分组 + 分组列表（前四本封面拼接） */
    private int navBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : 0;
    }

    private void showMoveSheet() {
        if (selected.isEmpty()) return;
        final Dialog dlg = new Dialog(this);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundResource(R.drawable.bg_sheet);
        root.setPadding(dp(16), dp(6), dp(16), dp(18) + navBarHeight());

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(4), dp(10), dp(4), dp(10));
        TextView title = new TextView(this);
        title.setText("移动至分组");
        title.setTextSize(16);
        title.setTextColor(0xFFF0F0F2);
        title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView newBtn = new TextView(this);
        newBtn.setText("新建分组");
        newBtn.setTextSize(13);
        newBtn.setTextColor(0xFFFF8A5C);
        newBtn.setPadding(dp(8), dp(6), dp(4), dp(6));
        newBtn.setOnClickListener(v -> {
            dlg.dismiss();
            createGroup(gid -> {
                moveSelectedTo(gid);
                refresh();
            });
        });
        head.addView(title);
        head.addView(newBtn);
        root.addView(head);

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(list);
        int rows = Math.max(1, BookStore.get(this).groups().size());
        // 「未分组」一行 56dp + 每个分组 96dp
        int contentH = dp(56) + dp(96) * rows;
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.55f);
        root.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.min(contentH, maxH)));

        // 「未分组」：把书移出分组（也让分组可以被清空）
        LinearLayout ungrouped = new LinearLayout(this);
        ungrouped.setOrientation(LinearLayout.HORIZONTAL);
        ungrouped.setGravity(Gravity.CENTER_VERTICAL);
        ungrouped.setPadding(dp(4), dp(12), dp(4), dp(12));
        ImageView ui = new ImageView(this);
        LinearLayout.LayoutParams uip = new LinearLayout.LayoutParams(dp(28), dp(28));
        uip.rightMargin = dp(14);
        ui.setLayoutParams(uip);
        ui.setImageResource(R.drawable.ic_folder);
        ui.setImageTintList(android.content.res.ColorStateList.valueOf(0xFF8C8C94));
        TextView ut = new TextView(this);
        ut.setText("未分组");
        ut.setTextSize(14);
        ut.setTextColor(0xFFDCDCE0);
        ungrouped.addView(ui);
        ungrouped.addView(ut);
        ungrouped.setOnClickListener(v -> {
            dlg.dismiss();
            moveSelectedTo(0);
            refresh();
        });
        list.addView(ungrouped);

        List<String[]> gs = BookStore.get(this).groups();
        if (gs.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("还没有分组，点右上角「新建分组」");
            t.setTextSize(13);
            t.setTextColor(0xFF8C8C94);
            t.setPadding(dp(4), dp(16), dp(4), dp(16));
            list.addView(t);
        }
        for (String[] g : gs) {
            final long gid = Long.parseLong(g[0]);
            View row = getLayoutInflater().inflate(R.layout.item_group_list, list, false);
            ((TextView) row.findViewById(R.id.title)).setText(g[1]);
            ((TextView) row.findViewById(R.id.progress))
                    .setText("共 " + BookStore.get(this).countInGroup(gid) + " 本");
            List<String> covers = BookStore.get(this).groupCovers(gid, 4);
            int[] ids = {R.id.gc1, R.id.gc2, R.id.gc3, R.id.gc4};
            for (int i = 0; i < ids.length; i++) {
                ImageView iv = row.findViewById(ids[i]);
                if (i < covers.size()) CoverLoader.get().load(iv, covers.get(i));
            }
            row.setOnClickListener(v -> {
                dlg.dismiss();
                moveSelectedTo(gid);
                refresh();
            });
            list.addView(row);
        }

        dlg.setContentView(root);
        android.view.Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
        }
        dlg.show();
    }

    private void moveSelectedTo(long groupId) {
        BookStore store = BookStore.get(this);
        for (Long id : new ArrayList<>(selected)) store.setGroup(id, groupId);
        int n = selected.size();
        exitSelection();
        Toast.makeText(this, "已将 " + n + " 本移入分组", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- 详情页

    private TextView lastDetailValue;

    private View detailRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(9), 0, dp(9));
        TextView k = new TextView(this);
        k.setText(label);
        k.setTextSize(13);
        k.setTextColor(0xFF8C8C94);
        k.setWidth(dp(76));
        TextView v = new TextView(this);
        lastDetailValue = v;
        v.setText(value == null || value.isEmpty() ? "—" : value);
        v.setTextSize(13);
        v.setTextColor(0xFFDCDCE0);
        v.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(k);
        row.addView(v);
        return row;
    }

    private String fmtWords(long w) {
        if (w <= 0) return "—";
        if (w >= 10000) return String.format(Locale.CHINA, "约 %.1f 万字", w / 10000f);
        return "约 " + w + " 字";
    }

    private String fmtSize(long bytes) {
        if (bytes <= 0) return "—";
        if (bytes >= 1024 * 1024) return String.format(Locale.CHINA, "%.1f MB", bytes / 1048576f);
        return Math.max(1, bytes / 1024) + " KB";
    }

    private String groupNameOf(Book b) {
        for (String[] g : BookStore.get(this).groups()) {
            if (Long.parseLong(g[0]) == b.groupId) return g[1];
        }
        return "未分组";
    }

    private void recountChapters(Book b, TextView target) {
        if (b.path == null || target == null) return;
        final File f = new File(b.path);
        if (!f.exists()) return;
        new Thread(() -> {
            int count = 0;
            try {
                com.orange.novel.reader.epub.EpubBook ep = com.orange.novel.reader.epub.EpubBook.open(f);
                int body = 0;
                for (com.orange.novel.reader.epub.EpubBook.Chapter c : ep.chapters) {
                    if (c.body) body++;
                }
                count = Math.max(body, ep.maxChapterNo);
            } catch (Exception ignored) {
                return;
            }
            final int n = count;
            if (n <= 0 || n == b.chapterCount) return;
            BookStore.get(getApplicationContext()).updateChapterCount(b.id, n);
            runOnUiThread(() -> target.setText(n + " 章"));
        }).start();
    }

    private void showBookDetail() {
        final Book b = singleSelected();
        if (b == null) return;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(6), dp(20), dp(4));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(62), dp(84));
        ilp.rightMargin = dp(14);
        iv.setLayoutParams(ilp);
        iv.setBackgroundResource(R.drawable.bg_cover);
        iv.setClipToOutline(true);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        CoverLoader.get().load(iv, b.coverPath);

        LinearLayout right = new LinearLayout(this);
        right.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(b.title);
        t.setTextSize(16);
        t.setTextColor(0xFFF0F0F2);
        t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        t.setMaxLines(2);
        TextView a = new TextView(this);
        a.setText(b.author == null || b.author.isEmpty() ? "未知作者" : b.author);
        a.setTextSize(12);
        a.setTextColor(0xFF9C9CA4);
        a.setPadding(0, dp(6), 0, 0);
        right.addView(t);
        right.addView(a);
        head.addView(iv);
        head.addView(right);
        box.addView(head);

        View line = new View(this);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        llp.topMargin = dp(12);
        llp.bottomMargin = dp(2);
        line.setLayoutParams(llp);
        line.setBackgroundColor(0x1AFFFFFF);
        box.addView(line);

        box.addView(detailRow("字数", fmtWords(b.words > 0 ? b.words : Math.round(b.bytes / 3.5f))));
        box.addView(detailRow("章节数", b.chapterCount > 0 ? b.chapterCount + " 章" : "—"));
        final TextView chapterVal = lastDetailValue;
        recountChapters(b, chapterVal);
        float pct = b.progress * 100f;
        box.addView(detailRow("阅读进度", pct >= 1f
                ? Math.round(pct) + "%" : String.format(Locale.CHINA, "%.1f%%", pct)));
        String readTo = b.chapterTitle == null ? "" : b.chapterTitle.trim();
        if (readTo.isEmpty() && b.chapter > 0) readTo = "第" + (b.chapter + 1) + "章";
        box.addView(detailRow("上次读到", readTo));
        box.addView(detailRow("上次阅读", BookAdapter.ago(b.lastReadAt)));
        box.addView(detailRow("导入时间", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm",
                Locale.CHINA).format(new java.util.Date(b.addedAt))));
        box.addView(detailRow("文件大小", fmtSize(b.bytes)));
        box.addView(detailRow("所属分组", groupNameOf(b)));

        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(box);
        new AlertDialog.Builder(this)
                .setView(sv)
                .setNeutralButton("更换封面", (d, w) -> openCoverMenu())
                .setPositiveButton("关闭", null)
                .show();
    }

    // ------------------------------------------------------------- 多选

    private void enterSelection(Book b) {
        selecting = true;
        selected.clear();
        selected.add(b.id);
        adapter.setSelection(true, selected);
        updateHeader();
    }

    private void exitSelection() {
        if (!selecting && selected.isEmpty()) return;
        selecting = false;
        selected.clear();
        adapter.setSelection(false, selected);
        updateHeader();
    }

    private void toggle(Book b) {
        if (!selected.remove(b.id)) selected.add(b.id);
        if (selected.isEmpty()) {
            exitSelection();
            return;
        }
        adapter.setSelection(true, selected);
        updateHeader();
    }

    private void toggleSelectAll() {
        if (books.isEmpty()) return;
        if (selected.size() >= books.size()) {
            exitSelection();
            return;
        }
        selected.clear();
        for (Book b : books) selected.add(b.id);
        adapter.setSelection(true, selected);
        updateHeader();
    }

    private void confirmDeleteSelected() {
        if (selected.isEmpty()) return;
        final int n = selected.size();
        new AlertDialog.Builder(this)
                .setTitle(R.string.delete_book_title)
                .setMessage("确定删除选中的 " + n + " 本书吗？阅读进度也会一并清除。")
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.delete, (d, w) -> deleteSelected())
                .show();
    }

    private void deleteSelected() {
        BookStore store = BookStore.get(this);
        for (Long id : new ArrayList<>(selected)) {
            Book b = store.get(id);
            store.delete(id);
            if (b != null) EpubImporter.deleteBookFiles(b);
        }
        selected.clear();
        selecting = false;
        refresh();
        Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- 置顶

    private Book byId(long id) {
        for (Book b : books) if (b.id == id) return b;
        return null;
    }

    private void togglePin() {
        if (selected.size() != 1) return;
        Book b = byId(selected.iterator().next());
        if (b == null) return;
        b.pinned = !b.pinned;
        BookStore.get(this).setPinned(b.id, b.pinned);
        exitSelection();
        refresh();
        Toast.makeText(this, b.pinned ? "已置顶" : "已取消置顶", Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------- 重命名

    private void renameBook() {
        final Book b = singleSelected();
        if (b == null) return;
        final float d = getResources().getDisplayMetrics().density;

        final EditText et = new EditText(this);
        et.setText(b.title == null ? "" : b.title);
        et.setSelection(et.getText().length());
        et.setSingleLine(true);
        et.setInputType(InputType.TYPE_CLASS_TEXT);
        et.setTextColor(0xFFE9E9EA);
        et.setHintTextColor(0xFF8C8C94);
        et.setHint("书名");
        et.setTextSize(15);

        LinearLayout box = new LinearLayout(this);
        int pad = Math.round(20 * d);
        box.setPadding(pad, Math.round(8 * d), pad, 0);
        box.addView(et, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("重命名")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("保存", null)
                .create();
        dlg.setOnShowListener(dd -> {
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String name = et.getText().toString().trim();
                if (name.isEmpty()) {
                    Toast.makeText(this, "书名不能为空", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (name.length() > 60) name = name.substring(0, 60);
                if (name.equals(b.title)) {
                    dlg.dismiss();
                    return;
                }
                BookStore.get(this).updateTitle(b.id, name);
                b.title = name;
                dlg.dismiss();
                exitSelection();
                refresh();
                Toast.makeText(this, "已重命名", Toast.LENGTH_SHORT).show();
            });
        });
        dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        dlg.show();
    }

    // ------------------------------------------------------------- 换封面

    private Book singleSelected() {
        if (selected.size() != 1) return null;
        long id = selected.iterator().next();
        for (Book b : books) {
            if (b.id == id) return b;
        }
        return null;
    }

    private void openCoverMenu() {
        final Book b = singleSelected();
        if (b == null) return;
        new AlertDialog.Builder(this)
                .setTitle("更换封面")
                .setItems(new String[]{"从相册选择", "恢复原封面", "恢复默认封面"}, (d, which) -> {
                    if (which == 0) {
                        try {
                            imagePicker.launch("image/*");
                        } catch (Exception e) {
                            Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
                        }
                    } else if (which == 1) {
                        restoreOriginalCover(b);
                    } else {
                        showCoverPreview(b, CoverManager.generate(b.title), "默认封面");
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void loadCoverFromUri(Uri uri) {
        final Book b = singleSelected();
        if (b == null) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            Bitmap raw = null;
            String err = null;
            try {
                raw = CoverManager.decodeUri(app, uri, 1600);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Bitmap fRaw = raw;
            final String fErr = err;
            runOnUiThread(() -> {
                if (fRaw == null) {
                    Toast.makeText(this, fErr == null ? "读取图片失败" : fErr, Toast.LENGTH_LONG).show();
                } else {
                    showCoverPreview(b, fRaw, "相册图片");
                }
            });
        }).start();
    }

    private void restoreOriginalCover(final Book b) {
        new Thread(() -> {
            Bitmap raw = null;
            String err = null;
            try {
                raw = CoverManager.readEpubCover(b);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final Bitmap fRaw = raw;
            final String fErr = err;
            runOnUiThread(() -> {
                if (fRaw == null) {
                    Toast.makeText(this, fErr == null ? "这本书里没有内嵌封面" : fErr, Toast.LENGTH_SHORT).show();
                } else {
                    showCoverPreview(b, fRaw, "原书封面");
                }
            });
        }).start();
    }

    /** 先裁好再给预览，确认后才落盘，避免选错图直接生效 */
    private void showCoverPreview(final Book b, Bitmap raw, String source) {
        if (isFinishing() || isDestroyed() || raw == null) return;
        final Bitmap cover = CoverManager.centerCrop(raw, CoverManager.RATIO);
        final float d = getResources().getDisplayMetrics().density;
        int w = Math.round(150 * d);
        int h = Math.round(w * CoverManager.RATIO);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Math.round(24 * d), 0, Math.round(24 * d), 0);

        ImageView iv = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        iv.setLayoutParams(lp);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundResource(R.drawable.bg_cover);
        iv.setClipToOutline(true);
        iv.setImageBitmap(cover);
        box.addView(iv);

        TextView tip = new TextView(this);
        tip.setText(source + " · 将按 3:4.2 居中裁切");
        tip.setTextColor(0xFF8C8C94);
        tip.setTextSize(11);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp2.gravity = Gravity.CENTER_HORIZONTAL;
        lp2.topMargin = Math.round(10 * d);
        box.addView(tip, lp2);

        final boolean[] used = {false};
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("封面预览")
                .setView(box)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton("使用这张", (dd, ww) -> {
                    used[0] = true;
                    applyCover(b, cover);
                })
                .create();
        dlg.setOnDismissListener(dd -> {
            if (!used[0] && !cover.isRecycled()) cover.recycle();
        });
        dlg.show();
    }

    private void applyCover(final Book b, final Bitmap cover) {
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String path = null;
            String err = null;
            try {
                path = CoverManager.save(app, cover);
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fPath = path;
            final String fErr = err;
            runOnUiThread(() -> {
                if (!cover.isRecycled()) cover.recycle();
                if (fPath == null) {
                    Toast.makeText(this, fErr == null ? "保存封面失败" : fErr, Toast.LENGTH_LONG).show();
                    return;
                }
                String old = b.coverPath;
                BookStore.get(this).updateCover(b.id, fPath);
                b.coverPath = fPath;
                if (old != null && !old.equals(fPath)) new File(old).delete();
                exitSelection();
                refresh();
                Toast.makeText(this, "封面已更新", Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // ------------------------------------------------------------- 导入

    private void importUris(List<Uri> uris) {
        if (selecting) exitSelection();
        showImportDialog(uris.size());
        final int total = uris.size();
        new Thread(() -> {
            int ok = 0;
            String err = null;
            for (int i = 0; i < total; i++) {
                final int idx = i;
                runOnUiThread(() -> {
                    if (importText != null) importText.setText("正在导入 " + (idx + 1) + "/" + total);
                });
                try {
                    Uri u = uris.get(i);
                    if (TxtImporter.isTxt(getApplicationContext(), u)) {
                        TxtImporter.importUri(getApplicationContext(), u);
                    } else {
                        EpubImporter.importUri(getApplicationContext(), u);
                    }
                    ok++;
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }
            final int okF = ok;
            final String errF = err;
            runOnUiThread(() -> {
                dismissImportDialog();
                refresh();
                if (okF == 0) {
                    Toast.makeText(this, errF == null ? "导入失败" : errF, Toast.LENGTH_LONG).show();
                } else if (okF < total) {
                    Toast.makeText(this, "成功导入 " + okF + " 本，" + (total - okF) + " 本失败",
                            Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "已导入 " + okF + " 本书", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }

    private void showImportDialog(int total) {
        dismissImportDialog();
        float d = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.bg_dialog);
        box.setPadding(Math.round(22 * d), Math.round(20 * d), Math.round(22 * d), Math.round(18 * d));

        TextView title = new TextView(this);
        title.setText("导入中");
        title.setTextColor(0xFFEDEDEF);
        title.setTextSize(15);
        box.addView(title);

        importText = new TextView(this);
        importText.setText("正在导入 1/" + total);
        importText.setTextColor(0xFF8C8C94);
        importText.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(8 * d);
        box.addView(importText, lp);

        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        pb.setIndeterminate(true);
        pb.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(0xFFFF6B35));
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.round(3 * d));
        lp2.topMargin = Math.round(16 * d);
        box.addView(pb, lp2);

        importDialog = new Dialog(this);
        importDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        importDialog.setContentView(box);
        importDialog.setCancelable(false);
        Window w = importDialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setDimAmount(0.55f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setLayout(Math.round(272 * d), ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.CENTER);
        }
        try {
            importDialog.show();
        } catch (Exception ignored) {
        }
    }

    private void dismissImportDialog() {
        if (importDialog != null) {
            try {
                importDialog.dismiss();
            } catch (Exception ignored) {
            }
            importDialog = null;
        }
    }

    // ------------------------------------------------------------- 列表回调

    @Override
    public void onOpenBook(Book b) {
        Intent i = new Intent(this, ReaderActivity.class);
        i.putExtra(ReaderActivity.EXTRA_ID, b.id);
        startActivity(i);
    }

    @Override
    public void onOpenGroup(long groupId, String name) {
        exitSelection();
        currentGroup = groupId;
        currentGroupName = name;
        refresh();
        grid.scrollToPosition(0);
    }

    @Override
    public void onLongPressBook(Book b) {
        if (!selecting) {
            enterSelection(b);
        } else {
            toggle(b);
        }
    }

    @Override
    public void onLongPressGroup(long groupId, String name) {
        showGroupMenu(groupId, name);
    }

    @Override
    protected void onDestroy() {
        dismissImportDialog();
        super.onDestroy();
    }
}
