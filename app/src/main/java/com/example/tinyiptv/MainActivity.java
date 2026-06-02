package com.example.tinyiptv;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.recyclerview.widget.*;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.source.DefaultMediaSourceFactory;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private DrawerLayout drawer;
    private TextView overlay, statusText;
    private View sortingLayout, statusLayout;
    private ProgressBar progressBar;
    private RecyclerView recyclerView;
    private List<String[]> channels = new ArrayList<>();
    private int currentIdx = 0;
    private SharedPreferences prefs;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36";

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            resetCache();
            loadPlaylist(uri);
        }
    });

    private final ActivityResultLauncher<String> fileSaver = registerForActivityResult(new ActivityResultContracts.CreateDocument("audio/x-mpegurl"), uri -> {
        if (uri != null) savePlaylistToUri(uri);
    });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        prefs = getPreferences(MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        drawer = findViewById(R.id.drawer_layout);
        overlay = findViewById(R.id.channel_overlay);
        sortingLayout = findViewById(R.id.sorting_layout);
        recyclerView = findViewById(R.id.channel_recycler_full);
        statusLayout = findViewById(R.id.status_layout);
        statusText = findViewById(R.id.status_text);
        progressBar = findViewById(R.id.progress_bar);

        // ФИКС ФОКУСА: заставляем плеер ловить кнопки сразу
        playerView.setFocusable(true);
        playerView.setFocusableInTouchMode(true);
        playerView.requestFocus();

        DefaultHttpDataSource.Factory httpFactory = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setAllowCrossProtocolRedirects(true);
        DefaultMediaSourceFactory msFactory = new DefaultMediaSourceFactory(this)
                .setDataSourceFactory(httpFactory);

        player = new ExoPlayer.Builder(this).setMediaSourceFactory(msFactory).build();
        playerView.setPlayer(player);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_BUFFERING) showStatus("Буферизация...", true);
                else if (state == Player.STATE_READY) hideStatus();
                else if (state == Player.STATE_ENDED) showStatus("Поток завершен", false);
            }
            @Override
            public void onPlayerError(PlaybackException e) {
                showStatus("Источник недоступен (Error " + e.errorCode + ")", false);
            }
        });

        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btn_sort).setOnClickListener(v -> openSortMode());
        findViewById(R.id.btn_done_sort).setOnClickListener(v -> closeSortMode());
        findViewById(R.id.btn_save).setOnClickListener(v -> fileSaver.launch("my_sorted_list.m3u"));

        initGestures(findViewById(R.id.gesture_layer));

        File cacheFile = new File(getFilesDir(), "cache.m3u");
        if (prefs.getBoolean("use_cache", false) && cacheFile.exists()) {
            loadPlaylist(Uri.fromFile(cacheFile));
        } else {
            String lastUri = prefs.getString("last_playlist", "");
            if (!lastUri.isEmpty()) loadPlaylist(Uri.parse(lastUri));
            else drawer.openDrawer(GravityCompat.START);
        }
    }

    private void showStatus(String text, boolean showProgress) {
        statusLayout.setVisibility(View.VISIBLE);
        statusText.setText(text);
        progressBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
    }

    private void hideStatus() { statusLayout.setVisibility(View.GONE); }

    private void openSortMode() {
        drawer.closeDrawers();
        sortingLayout.setVisibility(View.VISIBLE);
        recyclerView.setLayoutManager(new GridLayoutManager(this, 4));
        recyclerView.setAdapter(new ChannelAdapter());
        ItemTouchHelper th = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP|ItemTouchHelper.DOWN|ItemTouchHelper.LEFT|ItemTouchHelper.RIGHT, 0) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getAbsoluteAdapterPosition(), to = target.getAbsoluteAdapterPosition();
                if (from < to) { for (int i = from; i < to; i++) Collections.swap(channels, i, i + 1); } 
                else { for (int i = from; i > to; i--) Collections.swap(channels, i, i - 1); }
                rv.getAdapter().notifyItemMoved(from, to);
                findViewById(R.id.btn_save).setVisibility(View.VISIBLE);
                autoSaveCache();
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {}
        });
        th.attachToRecyclerView(recyclerView);
    }

    private void closeSortMode() {
        sortingLayout.setVisibility(View.GONE);
        String currentUrl = (channels.size() > currentIdx) ? channels.get(currentIdx)[1] : "";
        updateListView();
        if (!currentUrl.isEmpty()) {
            for (int i = 0; i < channels.size(); i++) { if (channels.get(i)[1].equals(currentUrl)) { currentIdx = i; break; } }
        }
    }

    private void autoSaveCache() {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(openFileOutput("cache.m3u", MODE_PRIVATE)))) {
            w.write("#EXTM3U\n");
            for (String[] ch : channels) w.write("#EXTINF:-1," + ch[0] + "\n" + ch[1] + "\n");
            prefs.edit().putBoolean("use_cache", true).apply();
        } catch (Exception ignored) {}
    }

    private void resetCache() {
        prefs.edit().putBoolean("use_cache", false).apply();
        new File(getFilesDir(), "cache.m3u").delete();
    }

    private void savePlaylistToUri(Uri uri) {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(getContentResolver().openOutputStream(uri)))) {
            w.write("#EXTM3U\n");
            for (String[] ch : channels) w.write("#EXTINF:-1," + ch[0] + "\n" + ch[1] + "\n");
            Toast.makeText(this, "Сохранено!", Toast.LENGTH_SHORT).show();
            findViewById(R.id.btn_save).setVisibility(View.GONE);
        } catch (Exception e) { Toast.makeText(this, "Ошибка записи", Toast.LENGTH_SHORT).show(); }
    }

    private void playChannel(int idx) {
        if (channels.isEmpty()) return;
        hideStatus();
        currentIdx = (idx + channels.size()) % channels.size();
        player.setMediaItem(MediaItem.fromUri(channels.get(currentIdx)[1]));
        player.prepare(); 
        player.play();
        prefs.edit().putInt("last_idx", currentIdx).apply();
        overlay.setText(channels.get(currentIdx)[0]);
        overlay.setVisibility(View.VISIBLE);
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(() -> overlay.setVisibility(View.GONE), 3000);
    }

    private void loadPlaylist(Uri uri) {
        new Thread(() -> {
            List<String[]> tmp = new ArrayList<>();
            InputStream is = null;
            try {
                if (uri.toString().startsWith("http")) {
                    HttpURLConnection c = (HttpURLConnection) new URL(uri.toString()).openConnection();
                    c.setRequestProperty("User-Agent", UA);
                    c.setConnectTimeout(5000); is = c.getInputStream();
                } else is = getContentResolver().openInputStream(uri);

                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    String line, name = "Канал";
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("#EXTINF")) {
                            int c = line.lastIndexOf(",");
                            if (c != -1) name = line.substring(c + 1);
                        } else if (line.startsWith("http") || line.startsWith("rtsp") || line.startsWith("rtmp")) {
                            tmp.add(new String[]{name, line});
                        }
                    }
                    br.close();
                }
                runOnUiThread(() -> {
                    channels = tmp; updateListView();
                    if (!uri.toString().contains("cache.m3u")) prefs.edit().putString("last_playlist", uri.toString()).apply();
                    if (!channels.isEmpty()) playChannel(prefs.getInt("last_idx", 0));
                });
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Ошибка M3U", Toast.LENGTH_SHORT).show()); }
            finally { try { if (is != null) is.close(); } catch (Exception ignored) {} }
        }).start();
    }

    private void updateListView() {
        ListView lv = findViewById(R.id.channel_list);
        List<String> names = new ArrayList<>();
        for (String[] c : channels) names.add(c[0]);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener((p, v, pos, id) -> { playChannel(pos); drawer.closeDrawers(); });
    }

    private class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {
        class VH extends RecyclerView.ViewHolder { TextView txt; View del, up; VH(View v) { super(v); 
            txt = v.findViewById(android.R.id.text1); del = v.findViewById(android.R.id.closeButton); up = v.findViewById(android.R.id.copy); 
        } }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            FrameLayout l = new FrameLayout(p.getContext()); l.setLayoutParams(new ViewGroup.LayoutParams(-1, 150)); l.setPadding(5,5,5,5);
            TextView tv = new TextView(p.getContext()); tv.setId(android.R.id.text1); tv.setLayoutParams(new FrameLayout.LayoutParams(-1,-1));
            tv.setGravity(Gravity.CENTER); tv.setTextColor(-1); tv.setTextSize(12); tv.setBackgroundResource(android.R.drawable.btn_default); l.addView(tv);
            TextView d = new TextView(p.getContext()); d.setId(android.R.id.closeButton); FrameLayout.LayoutParams dp = new FrameLayout.LayoutParams(70,70);
            dp.gravity = Gravity.TOP|Gravity.END; d.setLayoutParams(dp); d.setText("X"); d.setGravity(Gravity.CENTER); d.setTextColor(0xFFFF0000);
            d.setTextSize(16); d.setTypeface(null, android.graphics.Typeface.BOLD); d.setBackgroundColor(0x44000000); l.addView(d);
            TextView u = new TextView(p.getContext()); u.setId(android.R.id.copy); FrameLayout.LayoutParams up = new FrameLayout.LayoutParams(70,70);
            up.gravity = Gravity.TOP|Gravity.START; u.setLayoutParams(up); u.setText("↑"); u.setGravity(Gravity.CENTER); u.setTextColor(0xFF00FF00);
            u.setTextSize(22); u.setTypeface(null, android.graphics.Typeface.BOLD); u.setBackgroundColor(0x44000000); l.addView(u);
            return new VH(l);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int p) {
            h.txt.setText(channels.get(p)[0]);
            h.del.setOnClickListener(v -> { int pos = h.getAbsoluteAdapterPosition(); channels.remove(pos); notifyItemRemoved(pos); autoSaveCache(); });
            h.up.setOnClickListener(v -> { int pos = h.getAbsoluteAdapterPosition(); if (pos > 0) {
                String[] ch = channels.remove(pos); channels.add(0, ch); notifyItemMoved(pos, 0); notifyItemRangeChanged(0, pos+1); autoSaveCache();
            } });
        }
        @Override public int getItemCount() { return channels.size(); }
    }

    private void initGestures(View v) {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (channels.isEmpty()) return false;
                if (Math.abs(vY) > Math.abs(vX)) { if (vY > 0) playChannel(currentIdx - 1); else playChannel(currentIdx + 1); return true; }
                return false;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { if (e.getX() < (float) playerView.getWidth() / 3) { drawer.openDrawer(GravityCompat.START); return true; } return false; }
        });
        v.setOnTouchListener((view, event) -> { gd.onTouchEvent(event); return true; });
    }

    private void showSettingsDialog() {
        String[] opt = {"Из буфера", "Выбрать M3U файл"};
        new AlertDialog.Builder(this).setItems(opt, (d, w) -> {
            if (w == 0) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb.hasPrimaryClip()) { resetCache(); loadPlaylist(Uri.parse(cb.getPrimaryClip().getItemAt(0).getText().toString().trim())); }
            } else filePicker.launch(new String[]{"*/*"});
        }).show();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            // ФИКС КНОПОК: проверяем, что плейлист загружен
            if (channels.isEmpty()) return super.dispatchKeyEvent(e);

            int c = e.getKeyCode();
            if (c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_DPAD_RIGHT || c == KeyEvent.KEYCODE_CHANNEL_UP || c == KeyEvent.KEYCODE_MEDIA_NEXT || c == KeyEvent.KEYCODE_PAGE_UP) { playChannel(currentIdx + 1); return true; }
            if (c == KeyEvent.KEYCODE_DPAD_DOWN || c == KeyEvent.KEYCODE_DPAD_LEFT || c == KeyEvent.KEYCODE_CHANNEL_DOWN || c == KeyEvent.KEYCODE_MEDIA_PREVIOUS || c == KeyEvent.KEYCODE_PAGE_DOWN) { playChannel(currentIdx - 1); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onResume() { 
        super.onResume(); 
        hideSystemUI(); 
        // Принудительный фокус на плеер при возврате
        playerView.requestFocus();
        if (channels != null && !channels.isEmpty()) playChannel(currentIdx);
    }
    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); if (player != null) player.release(); }
}
