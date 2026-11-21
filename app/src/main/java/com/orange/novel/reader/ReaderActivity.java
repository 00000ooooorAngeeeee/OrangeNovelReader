package com.orange.novel.reader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.orange.novel.reader.db.Book;
import com.orange.novel.reader.db.BookStore;
import com.orange.novel.reader.epub.EpubBook;
import com.orange.novel.reader.reader.ReaderView;
import com.orange.novel.reader.epub.ChapterSource;
import com.orange.novel.reader.epub.HtmlBlocks;
import com.orange.novel.reader.ui.ChapterAdapter;
import com.orange.novel.reader.ui.CoverLoader;
import com.orange.novel.reader.ui.SearchAdapter;
import com.orange.novel.reader.ui.FastScrollBar;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ReaderActivity extends AppCompatActivity implements ReaderView.Callback {

    public static final String EXTRA_ID = "book_id";

    private ReaderView reader;
    private View topBar, bottomBar, panelRoot, seekRow;
    private TextView tvBookName, tvChapterName, tvPanelBook, tvPanelAuthor, tvPanelCount;
    private TextView tvSeekChapter, tvBarChapter, tvSeekPercent, tvBattery;
    private android.content.BroadcastReceiver batteryReceiver;
    private ImageView ivPanelCover, ivTabCatalog, ivTabSettings;
    private View panelSheet, catalogPage, detailPage;
    private LinearLayout detailRows;
    private TextView tabDetail, tabCatalog;
    private EditText etSearch;
    private TextView btnSearchAll;
    private SearchAdapter searchAdapter;
    private boolean fullTextMode;
    private boolean catCompact, catReverse;
    private final List<String> volNamesAsc = new ArrayList<>();
    private final List<String> volumeNames = new ArrayList<>();
    private final List<Integer> volumePos = new ArrayList<>();
    private TextView chipCompact, chipVolume, chipOrder;
    private int searchGen;
    private final java.util.List<ChapterAdapter.Entry> allEntries = new ArrayList<>();
    private String searchQuery = "";
    private View btnFontSize;
    private TextView tvFontValue, btnFontMinus, btnFontPlus;
    private SeekBar seekBrightness, seekLine, seekPara;
    private TextView btnFollowSystem, tvLineValue, tvParaValue;
    private View btnSpacing;
    private TextView btnLineMinus, btnLinePlus, btnParaMinus, btnParaPlus;
    private View spacingPanel;
    private ImageView ivSpacingArrow;
    private boolean followSystem;
    private int insetTop;
    private int brightnessPct = 80, linePct = 35, paraPct = 28;
    private LinearLayout fontPanel;
    private SeekBar seekBar, fontSeek;
    private RecyclerView chapterList;
    private ChapterAdapter chapterAdapter;
    private FastScrollBar fastScroll;
    private View selBar;
    private TextView btnSelCopy;
    private String selText = "";
    /** 目录里的正文条目数（与章节编号一致） */
    private int chapterCount;

    private Book book;
    private EpubBook epub;
    private boolean menuShown, panelOpen, seeking, fontPanelOpen;
    private int lastChapter = -1;
    private int fontSp = ReaderView.FONT_DEFAULT;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        wic.setAppearanceLightStatusBars(false);
        wic.setAppearanceLightNavigationBars(false);
        setContentView(R.layout.activity_reader);

        reader = findViewById(R.id.reader);
        topBar = findViewById(R.id.topBar);
        bottomBar = findViewById(R.id.bottomBar);
        panelRoot = findViewById(R.id.panelRoot);
        tvBookName = findViewById(R.id.tvBookName);
        tvChapterName = findViewById(R.id.tvChapterName);
        tvPanelBook = findViewById(R.id.tvPanelBook);
        tvPanelAuthor = findViewById(R.id.tvPanelAuthor);
        tvPanelCount = findViewById(R.id.tvPanelCount);
        ivPanelCover = findViewById(R.id.ivPanelCover);
        ivTabCatalog = findViewById(R.id.ivTabCatalog);
        ivTabSettings = findViewById(R.id.ivTabSettings);
        panelSheet = findViewById(R.id.panelSheet);
        catalogPage = findViewById(R.id.catalogPage);
        detailPage = findViewById(R.id.detailPage);
        detailRows = findViewById(R.id.detailRows);
        chipCompact = findViewById(R.id.chipCompact);
        chipVolume = findViewById(R.id.chipVolume);
        chipOrder = findViewById(R.id.chipOrder);
        tabDetail = findViewById(R.id.tabDetail);
        tabCatalog = findViewById(R.id.tabCatalog);
        seekRow = findViewById(R.id.seekRow);
        tvSeekChapter = findViewById(R.id.tvSeekChapter);
        tvBarChapter = findViewById(R.id.tvBarChapter);
        tvSeekPercent = findViewById(R.id.tvSeekPercent);
        tvBattery = findViewById(R.id.tvBattery);
        registerBattery();
        seekBar = findViewById(R.id.seekBar);
        seekBrightness = findViewById(R.id.seekBrightness);
        btnFollowSystem = findViewById(R.id.btnFollowSystem);
        seekLine = findViewById(R.id.seekLine);
        tvLineValue = findViewById(R.id.tvLineValue);
        seekPara = findViewById(R.id.seekPara);
        tvParaValue = findViewById(R.id.tvParaValue);
        btnLineMinus = findViewById(R.id.btnLineMinus);
        btnLinePlus = findViewById(R.id.btnLinePlus);
        btnParaMinus = findViewById(R.id.btnParaMinus);
        btnParaPlus = findViewById(R.id.btnParaPlus);
        btnSpacing = findViewById(R.id.btnSpacing);
        spacingPanel = findViewById(R.id.spacingPanel);
        ivSpacingArrow = findViewById(R.id.ivSpacingArrow);
        chapterList = findViewById(R.id.chapterList);
        btnFontSize = findViewById(R.id.btnFontSize);
        fontPanel = findViewById(R.id.fontPanel);
        tvFontValue = findViewById(R.id.tvFontValue);
        btnFontMinus = findViewById(R.id.btnFontMinus);
        btnFontPlus = findViewById(R.id.btnFontPlus);
        selBar = findViewById(R.id.selBar);
        btnSelCopy = findViewById(R.id.btnSelCopy);
        fastScroll = findViewById(R.id.fastScroll);

        prefs = getSharedPreferences("reader", MODE_PRIVATE);
        fontSp = Math.max(ReaderView.FONT_MIN,
                Math.min(ReaderView.FONT_MAX, prefs.getInt("font_size", ReaderView.FONT_DEFAULT)));
        tvFontValue.setText(String.valueOf(fontSp));
        reader.setFontSize(fontSp);

        catCompact = prefs.getBoolean("cat_compact", false);
        catReverse = prefs.getBoolean("cat_reverse", false);
        brightnessPct = prefs.getInt("brightness", 80);
        followSystem = prefs.getBoolean("follow_brightness", true);
        linePct = prefs.getInt("line_pct", 35);
        paraPct = prefs.getInt("para_pct", 28);
        seekBrightness.setProgress(brightnessPct);
        seekLine.setProgress(linePct);
        seekPara.setProgress(paraPct);
        reader.setLineMult(1.2f + linePct / 100f * 1.2f);
        reader.setParaMult(0.2f + paraPct / 100f * 1.8f);
        tvLineValue.setText(String.format(Locale.CHINA, "%.1f", 1.2f + linePct / 100f * 1.2f));
        tvParaValue.setText(String.format(Locale.CHINA, "%.1f", 0.2f + paraPct / 100f * 1.8f));
        applyBrightness();

        reader.setCallback(this);

        long id = getIntent().getLongExtra(EXTRA_ID, -1L);
        book = BookStore.get(this).get(id);
        if (book == null) {
            Toast.makeText(this, "书籍不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        tvBookName.setText(book.title);
        BookStore.get(this).touch(book.id);

        applyInsets();
        setupBars();
        getOnBackPressedDispatcher().addCallback(this, new androidx.activity.OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (reader != null && reader.isSelecting()) {
                    reader.clearSelection();
                    return;
                }
                if (fontPanelOpen) {
                    // 从设置面板返回 = 直接回到阅读，不再停留在菜单层
                    toggleFontPanel(false);
                    setMenu(false);
                    return;
                }
                if (panelOpen) {
                    closePanel();
                    return;
                }
                if (menuShown) {
                    setMenu(false);
                    return;
                }
                finish();
            }
        });
        setupChapterList();
        updateChips();
        chapterAdapter.setCompact(catCompact);
        loadBook();
    }

    private void applyInsets() {
        final float d = getResources().getDisplayMetrics().density;
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            topBar.setPadding(Math.round(4 * d), bars.top + Math.round(6 * d), Math.round(16 * d),
                    Math.round(10 * d));
            bottomBar.setPadding(Math.round(20 * d), Math.round(14 * d), Math.round(20 * d),
                    bars.bottom + Math.round(12 * d));
            insetTop = bars.top;
            View info = findViewById(R.id.infoRow);
            if (info != null) {
                info.setPadding(Math.round(20 * d), Math.round(2 * d),
                        Math.round(20 * d), bars.bottom + Math.round(6 * d));
            }
            reader.setInsets(bars.top, bars.bottom);
            return insets;
        });
    }

    private void setPanelWidth(View v, int w) {
        android.view.ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null && lp.width != w) {
            lp.width = w;
            v.setLayoutParams(lp);
        }
    }

    private void setupBars() {
        topBar.setVisibility(View.INVISIBLE);
        bottomBar.setVisibility(View.INVISIBLE);
        panelRoot.setVisibility(View.GONE);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCatalog).setOnClickListener(v -> {
            if (panelOpen) closePanel();
            else openPanel();
        });
        findViewById(R.id.btnPrevChapter).setOnClickListener(v -> reader.jumpChapter(reader.currentChapter() - 1));
        findViewById(R.id.btnNextChapter).setOnClickListener(v -> reader.jumpChapter(reader.currentChapter() + 1));

        btnFontSize.setOnClickListener(v -> toggleFontPanel(!fontPanelOpen));
        btnFontMinus.setOnClickListener(v -> applyFontSize(fontSp - 1));
        btnFontPlus.setOnClickListener(v -> applyFontSize(fontSp + 1));
        seekBrightness.setOnSeekBarChangeListener(new SimpleSeek(v -> {
            brightnessPct = v;
            followSystem = false;
            setFollowUi();
            applyBrightness();
            prefs.edit().putInt("brightness", v).putBoolean("follow_brightness", false).apply();
        }));
        btnFollowSystem.setOnClickListener(v -> {
            boolean on = !followSystem;
            if (on) {
                // 跟随系统时把滑块拉到当前系统亮度，避免再次拖滑块时跳变
                seekBrightness.setProgress(brightnessPct);
            } else {
                brightnessPct = seekBrightness.getProgress();
            }
            followSystem = on;
            setFollowUi();
            applyBrightness();
            prefs.edit().putBoolean("follow_brightness", on)
                    .putInt("brightness", brightnessPct).apply();
        });
        seekLine.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (!fromUser) return;
                linePct = value;
                tvLineValue.setText(String.format(Locale.CHINA, "%.1f", 1.2f + value / 100f * 1.2f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                applyLineSpacing(sb.getProgress());
            }
        });
        seekPara.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (!fromUser) return;
                paraPct = value;
                tvParaValue.setText(String.format(Locale.CHINA, "%.1f", 0.2f + value / 100f * 1.8f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                applyParaSpacing(sb.getProgress());
            }
        });
        btnLineMinus.setOnClickListener(v -> {
            int p = Math.max(0, seekLine.getProgress() - 4);
            seekLine.setProgress(p);
            applyLineSpacing(p);
        });
        btnLinePlus.setOnClickListener(v -> {
            int p = Math.min(100, seekLine.getProgress() + 4);
            seekLine.setProgress(p);
            applyLineSpacing(p);
        });
        btnParaMinus.setOnClickListener(v -> {
            int p = Math.max(0, seekPara.getProgress() - 4);
            seekPara.setProgress(p);
            applyParaSpacing(p);
        });
        btnParaPlus.setOnClickListener(v -> {
            int p = Math.min(100, seekPara.getProgress() + 4);
            seekPara.setProgress(p);
            applyParaSpacing(p);
        });
        btnSpacing.setOnClickListener(v -> {
            boolean open = spacingPanel.getVisibility() != View.VISIBLE;
            spacingPanel.setVisibility(open ? View.VISIBLE : View.GONE);
            ivSpacingArrow.setRotation(open ? 180f : 0f);
        });

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
                if (fromUser) tvSeekPercent.setText(String.format(Locale.US, "%.2f%%", value / 100f));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seeking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seeking = false;
                reader.seekToProgress(sb.getProgress() / 10000f);
            }
        });
    }

    // ------------------------------------------------------------- 字号

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void applyFontSize(int sp) {
        sp = Math.max(ReaderView.FONT_MIN, Math.min(ReaderView.FONT_MAX, sp));
        fontSp = sp;
        tvFontValue.setText(String.valueOf(sp));
        reader.setFontSize(sp);
        prefs.edit().putInt("font_size", sp).apply();
    }

    private void setFollowUi() {
        boolean on = followSystem;
        btnFollowSystem.setText(on ? "跟随系统" : "跟随系统");
        btnFollowSystem.setBackgroundResource(on ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        btnFollowSystem.setTextColor(on ? 0xFFFF8A5C : 0xFFB0B0B8);
        seekBrightness.setEnabled(!on);
        seekBrightness.setAlpha(on ? 0.45f : 1f);
    }

    /** 详情 / 目录 两页切换（同一抽屉，无弹窗） */
    private void showDetailTab(boolean detail) {
        detailPage.setVisibility(detail ? View.VISIBLE : View.GONE);
        catalogPage.setVisibility(detail ? View.GONE : View.VISIBLE);
        tabDetail.setTextColor(detail ? 0xFFFFFFFF : 0xFF9C9CA4);
        tabDetail.setTextSize(detail ? 15 : 14);
        tabDetail.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                detail ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
        tabCatalog.setTextColor(detail ? 0xFF9C9CA4 : 0xFFFFFFFF);
        tabCatalog.setTextSize(detail ? 14 : 15);
        tabCatalog.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,
                detail ? android.graphics.Typeface.NORMAL : android.graphics.Typeface.BOLD));
        if (detail) buildDetailRows();
    }

    /** 「分卷」：列出所有卷，选中后跳到该卷开头 */
    private void showVolumePicker() {
        if (volumeNames.isEmpty()) {
            Toast.makeText(this, "本书没有分卷信息", Toast.LENGTH_SHORT).show();
            return;
        }
        android.widget.PopupMenu pm = new android.widget.PopupMenu(this, chipVolume);
        for (int i = 0; i < volumeNames.size(); i++) pm.getMenu().add(0, i, i, volumeNames.get(i));
        pm.setOnMenuItemClickListener(item -> {
            int which = item.getItemId();
            if (etSearch != null) etSearch.setText("");
            applyFilter();
            int pos = volumePos.get(which);
            RecyclerView.LayoutManager lm = chapterList.getLayoutManager();
            if (lm instanceof LinearLayoutManager) {
                ((LinearLayoutManager) lm).scrollToPositionWithOffset(pos, 0);
            } else if (lm != null) {
                chapterList.scrollToPosition(pos);
            }
            return true;
        });
        pm.show();
    }

    private void applyLineSpacing(int pct) {
        linePct = pct;
        float mult = 1.2f + pct / 100f * 1.2f;
        tvLineValue.setText(String.format(Locale.CHINA, "%.1f", mult));
        prefs.edit().putInt("line_pct", pct).apply();
        reader.setLineMult(mult);
    }

    private void applyParaSpacing(int pct) {
        paraPct = pct;
        float mult = 0.2f + pct / 100f * 1.8f;
        tvParaValue.setText(String.format(Locale.CHINA, "%.1f", mult));
        prefs.edit().putInt("para_pct", pct).apply();
        reader.setParaMult(mult);
    }

    private void updateChips() {
        if (chipCompact != null) chipCompact.setText(catCompact ? "精简" : "详细");
        if (chipOrder != null) chipOrder.setText(catReverse ? "倒序" : "正序");
    }

    private String formatWords(long n) {
        if (n <= 0) return "—";
        return String.format(Locale.CHINA, "约 %.1f 万字", n / 10000f);
    }

    private void buildDetailRows() {
        if (book == null || detailRows == null) return;
        detailRows.removeAllViews();
        String[][] rows = {
                {"字数", formatWords(book.words > 0 ? book.words : Math.round(book.bytes / 3.5f))},
                {"章节数", chapterCount + " 章"},
                {"阅读进度", String.format(Locale.CHINA, "%.1f%%", book.progress * 100f)},
                {"上次读到", book.chapterTitle == null || book.chapterTitle.isEmpty()
                        ? "—" : book.chapterTitle},
                {"导入时间", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                        .format(new java.util.Date(book.addedAt))},
                {"文件大小", book.bytes >= 1048576
                        ? String.format(Locale.CHINA, "%.1f MB", book.bytes / 1048576f)
                        : Math.max(1, book.bytes / 1024) + " KB"}
        };
        for (String[] r : rows) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(dp(20), dp(15), dp(20), dp(15));
            TextView k = new TextView(this);
            k.setText(r[0]);
            k.setTextSize(13);
            k.setTextColor(0xFF8C8C94);
            k.setWidth(dp(76));
            TextView v = new TextView(this);
            v.setText(r[1]);
            v.setTextSize(13);
            v.setTextColor(0xFFDCDCE0);
            v.setLayoutParams(new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(k);
            row.addView(v);
            detailRows.addView(row);

            View line = new View(this);
            line.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
            line.setBackgroundColor(0xFF333333);
            detailRows.addView(line);
        }
    }

    private void registerBattery() {
        batteryReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context c, android.content.Intent it) {
                if (it == null) return;
                int level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                int scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                if (level >= 0 && scale > 0) {
                    tvBattery.setText(Math.round(level * 100f / scale) + "%");
                }
            }
        };
        registerReceiver(batteryReceiver,
                new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED));
    }

    private void applyBrightness() {
        android.view.WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = followSystem ? -1f
                : Math.max(0.05f, Math.min(1f, brightnessPct / 100f));
        getWindow().setAttributes(lp);
    }

    /** 只关心进度变化的 SeekBar 监听，避免每处都写三个空方法 */
    private static class SimpleSeek implements SeekBar.OnSeekBarChangeListener {
        private final java.util.function.IntConsumer onChange;

        SimpleSeek(java.util.function.IntConsumer c) {
            onChange = c;
        }

        @Override
        public void onProgressChanged(SeekBar sb, int value, boolean fromUser) {
            if (fromUser) onChange.accept(value);
        }

        @Override
        public void onStartTrackingTouch(SeekBar sb) {
        }

        @Override
        public void onStopTrackingTouch(SeekBar sb) {
        }
    }

    private void toggleFontPanel(boolean open) {
        if (fontPanelOpen == open) return;
        if (open && panelOpen) closePanel();
        fontPanelOpen = open;
        setTabState(panelOpen);
        if (open) {
            ViewGroup.LayoutParams lp = fontPanel.getLayoutParams();
            View parent = (View) fontPanel.getParent();
            if (lp instanceof FrameLayout.LayoutParams && parent != null) {
                // 直接贴到底栏上沿：用兄弟视图的实际位置算，避免 insets/padding 造成偏差
                ((FrameLayout.LayoutParams) lp).bottomMargin =
                        Math.max(0, parent.getHeight() - bottomBar.getTop() + dp(8));
                fontPanel.setLayoutParams(lp);
            }
            fontPanel.setVisibility(View.VISIBLE);
            fontPanel.setTranslationY(dp(12));
            fontPanel.animate().translationY(0f).setDuration(180).start();
        } else {
            fontPanel.animate().translationY(dp(12)).setDuration(150)
                    .withEndAction(() -> {
                        if (!fontPanelOpen) fontPanel.setVisibility(View.GONE);
                    }).start();
        }
    }

    private void setupChapterList() {
        chapterAdapter = new ChapterAdapter(e -> {
            if (e.chapter < 0) return;   // 分卷标题不可跳转
            reader.clearSelection();
            reader.jumpChapter(e.chapter);
            closePanel();
            setMenu(false);
        });
        chapterList.setLayoutManager(new LinearLayoutManager(this));
        etSearch = findViewById(R.id.etSearch);
        btnSearchAll = findViewById(R.id.btnSearchAll);
        searchAdapter = new SearchAdapter(hit -> {
            reader.jumpTo(hit.chapter, hit.offset);
            closePanel();
            setMenu(false);
        });
        findViewById(R.id.panelScrim).setOnClickListener(v -> closePanel());
        chipCompact.setOnClickListener(v -> {
            android.widget.PopupMenu pm = new android.widget.PopupMenu(this, chipCompact);
            pm.getMenu().add(0, 0, 0, "精简");
            pm.getMenu().add(0, 1, 1, "详细");
            pm.setOnMenuItemClickListener(item -> {
                catCompact = item.getItemId() == 0;
                prefs.edit().putBoolean("cat_compact", catCompact).apply();
                updateChips();
                chapterAdapter.setCompact(catCompact);
                return true;
            });
            pm.show();
        });
        chipVolume.setOnClickListener(v -> showVolumePicker());
        chipOrder.setOnClickListener(v -> {
            catReverse = !catReverse;
            prefs.edit().putBoolean("cat_reverse", catReverse).apply();
            updateChips();
            fillChapters();
        });
        tabDetail.setOnClickListener(v -> showDetailTab(true));
        tabCatalog.setOnClickListener(v -> showDetailTab(false));
        btnSearchAll.setOnClickListener(v -> {
            fullTextMode = !fullTextMode;
            btnSearchAll.setText(fullTextMode ? "搜章节" : "搜全文");
            etSearch.setHint(fullTextMode ? "搜索全书内容" : "搜索章节名 / 章号");
            chapterList.setAdapter(fullTextMode ? searchAdapter : chapterAdapter);
            if (fullTextMode) {
                etSearch.requestFocus();
                applyFilter();
            } else {
                applyFilter();
            }
        });
        etSearch.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                searchQuery = e == null ? "" : e.toString();
                applyFilter();
            }
        });
        chapterList.setAdapter(chapterAdapter);

        fastScroll.attach(chapterList);
        fastScroll.setListener(new FastScrollBar.Listener() {
            @Override
            public void onFastScroll(int position) {
                String label = chapterAdapter.labelAt(position);
                if (!label.isEmpty()) tvPanelCount.setText(label);
            }

            @Override
            public void onFastScrollEnd() {
                if (epub == null) return;
                tvPanelCount.setText("共 " + chapterCount + " 章");
            }
        });

        btnSelCopy.setOnClickListener(v -> copySelection());
    }

    // ------------------------------------------------------------- 复制

    private void copySelection() {
        if (selText == null || selText.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("橘子小说", selText));
        Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        reader.clearSelection();
    }

    private void loadBook() {
        final String path = book.path;
        new Thread(() -> {
            EpubBook ep = null;
            String err = null;
            try {
                ep = EpubBook.open(new File(path));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final EpubBook fep = ep;
            final String ferr = err;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    if (fep != null) fep.close();
                    return;
                }
                if (fep == null) {
                    Toast.makeText(this, ferr == null ? "无法打开这本书" : ferr, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                epub = fep;
                reader.setBook(book, epub);
                fillChapters();
            });
        }).start();
    }

    private void fillChapters() {
        List<ChapterAdapter.Entry> list = new ArrayList<>();
        // 层级（卷/分章缩进）从目录取，但列表以 spine 章节为准：
        // 目录漏收章节、或指向的文件缺失时，拿目录当列表会和正文、编号对不上
        java.util.HashMap<Integer, Integer> levels = new java.util.HashMap<>();
        for (EpubBook.Toc t : epub.toc) {
            if (t.chapter >= 0 && !levels.containsKey(t.chapter)) levels.put(t.chapter, t.level);
        }
        String lastVolume = null;
        int bodyCount = 0;
        volumeNames.clear();
        volumePos.clear();
        for (EpubBook.Chapter c : epub.chapters) {
            if (!c.body) continue;
            // 卷标题单独占一行（chapter = -1，不可点），不计入章节数
            if (c.volume != null && !c.volume.equals(lastVolume)) {
                list.add(new ChapterAdapter.Entry(c.volume, -1, 0));
                volNamesAsc.add(c.volume);
                lastVolume = c.volume;
            }
            Integer lv = levels.get(c.index);
            list.add(new ChapterAdapter.Entry(titleOf(c.title, c.index), c.index,
                    lv == null ? 0 : lv, wordsOf(c.bytes)));
            bodyCount++;
        }
        if (list.isEmpty()) {
            for (EpubBook.Chapter c : epub.chapters) {
                list.add(new ChapterAdapter.Entry(titleOf(c.title, c.index), c.index, 0));
            }
        }
        // 目录里显示的章数要和最后一章的编号对得上（有的书中间缺章，末章是 1497 而实际只有 1495 条）
        chapterCount = Math.max(bodyCount, epub.maxChapterNo);
        if (catReverse) java.util.Collections.reverse(list);
        // 卷名固定按「升序」排列（不随正序/倒序变化），位置再按当前列表查找
        volumeNames.clear();
        volumePos.clear();
        for (String vn : volNamesAsc) {
            for (int vi = 0; vi < list.size(); vi++) {
                ChapterAdapter.Entry ve = list.get(vi);
                if (ve.chapter < 0 && vn.equals(ve.title)) {
                    volumeNames.add(vn);
                    volumePos.add(vi);
                    break;
                }
            }
        }
        allEntries.clear();
        allEntries.addAll(list);
        applyFilter();
        tvPanelCount.setText("共 " + chapterCount + " 章");
        chapterAdapter.setCurrent(reader.currentChapter(), Math.round(book.progress * 100));
    }

    /** 按搜索词过滤目录；命中章节时保留它所属的卷标题 */
    private void applyFilter() {
        String q = searchQuery == null ? "" : searchQuery.trim().toLowerCase(Locale.ROOT);
        if (fullTextMode) {
            runFullTextSearch(q);
            return;
        }
        if (q.isEmpty()) {
            chapterAdapter.setItems(allEntries);
            return;
        }
        List<ChapterAdapter.Entry> out = new ArrayList<>();
        ChapterAdapter.Entry header = null;
        boolean headerAdded = false;
        for (ChapterAdapter.Entry e : allEntries) {
            if (e.chapter < 0) {
                header = e;
                headerAdded = false;
                continue;
            }
            if (e.title == null || !e.title.toLowerCase(Locale.ROOT).contains(q)) continue;
            if (header != null && !headerAdded) {
                out.add(header);
                headerAdded = true;
            }
            out.add(e);
        }
        chapterAdapter.setItems(out);
    }

    /** 全文搜索：后台逐章扫描，命中章节 + 片段 */
    private void runFullTextSearch(final String q) {
        searchGen++;
        final int gen = searchGen;
        if (q.isEmpty()) {
            searchAdapter.submit(new ArrayList<>(), q);
            tvPanelCount.setText("共 " + chapterCount + " 章");
            return;
        }
        tvPanelCount.setText("搜索中…");
        new Thread(() -> {
            final List<SearchAdapter.Hit> out = new ArrayList<>();
            for (int i = 0; i < epub.chapters.size() && gen == searchGen && out.size() < 200; i++) {
                EpubBook.Chapter c = epub.chapters.get(i);
                if (!c.body) continue;
                String html;
                try {
                    html = epub.readText(c.path);
                } catch (Exception e) {
                    continue;
                }
                if (html == null) continue;
                ChapterSource src = ChapterSource.build(html, EpubBook.dirOf(c.path));
                int base = 0;
                for (HtmlBlocks.Para p : src.paras) {
                    if (p.text == null) continue;
                    String t = p.text.toString();
                    int idx = t.toLowerCase(Locale.ROOT).indexOf(q);
                    if (idx >= 0) {
                        out.add(new SearchAdapter.Hit(i, base + idx,
                                titleOf(c.title, i), snippet(t, idx, q.length())));
                        if (out.size() >= 200) break;
                    }
                    base += t.length();
                }
            }
            runOnUiThread(() -> {
                if (gen != searchGen) return;
                searchAdapter.submit(out, q);
                tvPanelCount.setText("找到 " + out.size() + " 处");
            });
        }).start();
    }

    private String snippet(String text, int idx, int len) {
        int from = Math.max(0, idx - 14);
        int to = Math.min(text.length(), idx + len + 30);
        String s = text.substring(from, to).replace("\n", " ");
        return (from > 0 ? "…" : "") + s + (to < text.length() ? "…" : "");
    }

    /** 章节目录里的「约 N 字」：UTF-8 正文约 3.5 字节/字 */
    private String wordsOf(long bytes) {
        long n = Math.round(bytes / 3.5f);
        if (n <= 0) return "";
        return n >= 10000 ? String.format(Locale.CHINA, "约 %.1f 万字", n / 10000f)
                : "约 " + n + " 字";
    }

    private String titleOf(String title, int idx) {
        if (title != null && !title.trim().isEmpty()) return title;
        return "第 " + (idx + 1) + " 章";
    }

    // ------------------------------------------------------------- 菜单与目录

    private void setMenu(boolean show) {
        if (menuShown == show) return;
        menuShown = show;
        reader.setMenuVisible(show);
        findViewById(R.id.infoRow).setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) toggleFontPanel(false);
        if (show) {
            topBar.animate().cancel();
            bottomBar.animate().cancel();
            topBar.setVisibility(View.VISIBLE);
            bottomBar.setVisibility(View.VISIBLE);
            topBar.setAlpha(1f);
            bottomBar.setAlpha(1f);
            topBar.setTranslationY(-topBar.getHeight());
            bottomBar.setTranslationY(bottomBar.getHeight());
            topBar.animate().translationY(0f).setDuration(200).start();
            bottomBar.animate().translationY(0f).setDuration(200).start();
        } else {
            topBar.animate().translationY(-topBar.getHeight()).setDuration(170)
                    .withEndAction(() -> {
                        if (!menuShown) topBar.setVisibility(View.INVISIBLE);
                    }).start();
            bottomBar.animate().translationY(bottomBar.getHeight()).setDuration(170)
                    .withEndAction(() -> {
                        if (!menuShown) bottomBar.setVisibility(View.INVISIBLE);
                    }).start();
        }
    }

    private void openPanel() {
        if (panelOpen) return;
        panelOpen = true;
        toggleFontPanel(false);
        // 整页目录：只保留底部导航条，顶栏让位给目录自己的返回键
        setMenu(false);
        bottomBar.animate().cancel();
        bottomBar.setVisibility(View.VISIBLE);
        bottomBar.setTranslationY(0f);
        panelRoot.setVisibility(View.VISIBLE);
        // 底栏高度要等布局完成才准，否则 padding 为 0 会把导航盖住
        bottomBar.post(() -> {
            if (panelOpen) panelRoot.setPadding(0, insetTop, 0, bottomBar.getHeight());
        });
        panelRoot.setAlpha(0f);
        panelRoot.animate().alpha(1f).setDuration(160).start();
        setTabState(true);
        // 抽屉：顶部留出「状态栏 + 一小段正文」，下方才是面板
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) panelSheet.getLayoutParams();
        mlp.topMargin = insetTop + dp(56);
        panelSheet.setLayoutParams(mlp);
        showDetailTab(false);
        // 目录页里只保留下方导航，滑块行让位给列表
        seekRow.setVisibility(View.GONE);
        findViewById(R.id.infoRow).setVisibility(View.GONE);
        tvPanelBook.setText(book.title);
        tvPanelAuthor.setText(book.author == null || book.author.isEmpty() ? "未知作者" : book.author);
        CoverLoader.get().load(ivPanelCover, book.coverPath);
        int idx = chapterAdapter.indexOfChapter(reader.currentChapter());
        if (idx >= 0) {
            ((LinearLayoutManager) chapterList.getLayoutManager()).scrollToPositionWithOffset(Math.max(0, idx - 3), 0);
        }
    }

    private void closePanel() {
        if (!panelOpen) return;
        panelOpen = false;
        panelRoot.animate().alpha(0f).setDuration(140)
                .withEndAction(() -> panelRoot.setVisibility(View.GONE)).start();
        setTabState(false);
        // 目录是压在阅读态之上的，关掉后要回到「菜单收起」的状态：
        // 顶栏/底栏隐藏、信息行显示（setMenu 有 if(menuShown==show) 守卫，这里直接处理）
        menuShown = false;
        topBar.animate().cancel();
        bottomBar.animate().cancel();
        topBar.setVisibility(View.INVISIBLE);
        bottomBar.setVisibility(View.INVISIBLE);
        reader.setMenuVisible(false);
        seekRow.setVisibility(View.VISIBLE);
        findViewById(R.id.infoRow).setVisibility(View.VISIBLE);
    }

    /** 底部导航：选中项用实心图标 + 高亮，未选中用空心 */
    private void setTabState(boolean catalogOpen) {
        boolean settingsOpen = fontPanelOpen;
        ivTabCatalog.setImageResource(catalogOpen
                ? R.drawable.ic_catalog_filled : R.drawable.ic_catalog_outline);
        ivTabCatalog.setImageTintList(android.content.res.ColorStateList.valueOf(
                catalogOpen ? 0xFFFFFFFF : 0xFF9C9CA4));
        ivTabSettings.setImageResource(settingsOpen
                ? R.drawable.ic_settings_filled : R.drawable.ic_settings_outline);
        ivTabSettings.setImageTintList(android.content.res.ColorStateList.valueOf(
                settingsOpen ? 0xFFFFFFFF : 0xFF9C9CA4));
    }

    // ------------------------------------------------------------- 回调

    @Override
    public void onProgressChanged(int chapter, float progress, String chapterTitle) {
        if (chapterAdapter != null && chapter != lastChapter) {
            lastChapter = chapter;
            chapterAdapter.setCurrent(chapter);
        }
        if (chapterTitle != null && !chapterTitle.isEmpty()) {
            tvChapterName.setText(chapterTitle);
            int sp = chapterTitle.indexOf(' ');
            String no = sp > 0 ? chapterTitle.substring(0, sp) : chapterTitle;
            String name = sp > 0 ? chapterTitle.substring(sp + 1) : "";
            tvSeekChapter.setText(no + "/共" + chapterCount + "章");
            tvBarChapter.setText(name);
        }
        tvSeekPercent.setText(String.format(Locale.US, "%.2f%%", progress * 100f));
        if (!seeking) seekBar.setProgress(Math.round(progress * 10000f));
    }

    @Override
    public void onToggleMenu() {
        if (panelOpen) {
            closePanel();
            return;
        }
        setMenu(!menuShown);
    }

    @Override
    public void onTextSelected(RectF rect, String text) {
        selText = text == null ? "" : text;
        setMenu(false);
        toggleFontPanel(false);
        selBar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int w = selBar.getMeasuredWidth();
        int h = selBar.getMeasuredHeight();
        int screenW = getResources().getDisplayMetrics().widthPixels;
        float x = rect.centerX() - w / 2f;
        x = Math.max(dp(10), Math.min(x, screenW - w - dp(10)));
        float y = rect.top - h - dp(10);
        if (y < insetTop + dp(8)) y = rect.bottom + dp(10);
        selBar.setX(x);
        selBar.setY(y);
        selBar.setVisibility(View.VISIBLE);
    }

    @Override
    public void onSelectionCleared() {
        selBar.setVisibility(View.GONE);
    }

    @Override
    public void onScrollIdle() {
        save();
    }

    private void save() {
        if (book == null || epub == null || reader == null) return;
        int ch = reader.currentChapter();
        int off = reader.currentOffset();
        float p = reader.progress();
        String title = ch >= 0 && ch < epub.chapters.size() ? epub.chapters.get(ch).title : "";
        if (title != null && !title.isEmpty() && !title.equals(book.chapterTitle)) {
            book.chapterTitle = title;
            BookStore.get(this).updateReadChapter(book.id, title);
        }
        if (book.chapter == ch && book.offset == off && Math.abs(book.progress - p) < 0.0001f) return;
        book.chapter = ch;
        book.offset = off;
        book.progress = p;
        BookStore.get(this).saveProgress(book.id, ch, off, p);
    }

    @Override
    protected void onPause() {
        save();
        super.onPause();
    }

    @Override
    public void onBackPressed() {
        // 兼容旧路径：统一走 OnBackPressedDispatcher 的分发逻辑
        getOnBackPressedDispatcher().onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (batteryReceiver != null) {
            try {
                unregisterReceiver(batteryReceiver);
            } catch (Exception ignored) {
            }
        }
        if (reader != null) reader.shutdown();
        if (epub != null) {
            try {
                epub.close();
            } catch (Exception ignored) {
            }
            epub = null;
        }
        super.onDestroy();
    }
}
