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
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private DrawerLayout drawer;
    private TextView overlay;
    private View sortingLayout;
    private RecyclerView recyclerView;
    private List<String[]> channels = new ArrayList<>();
    private int currentIdx = 0;
    private SharedPreferences prefs;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
        if (uri != null) {
            try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
            loadPlaylist(uri);
        }
    });

    private final ActivityResultLauncher<String> fileSaver = registerForActivityResult(new ActivityResultContracts.CreateDocument("audio/x-mpegurl"), uri -> {
        if (uri != null) savePlaylistToFile(uri);
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

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // Кнопки
        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btn_sort).setOnClickListener(v -> openSortMode());
        findViewById(R.id.btn_done_sort).setOnClickListener(v -> closeSortMode());
        findViewById(R.id.btn_save).setOnClickListener(v -> fileSaver.launch("my_playlist.m3u"));

        initGestures(findViewById(R.id.gesture_layer));

        String lastUri = prefs.getString("last_playlist", "");
        if (!lastUri.isEmpty()) loadPlaylist(Uri.parse(lastUri));
        else drawer.openDrawer(GravityCompat.START);
    }

    private void openSortMode() {
        drawer.closeDrawers();
        sortingLayout.setVisibility(View.VISIBLE);
        
        recyclerView.setLayoutManager(new GridLayoutManager(this, 4));
        ChannelAdapter adapter = new ChannelAdapter();
        recyclerView.setAdapter(adapter);

        ItemTouchHelper th = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP|ItemTouchHelper.DOWN|ItemTouchHelper.LEFT|ItemTouchHelper.RIGHT,
                ItemTouchHelper.LEFT|ItemTouchHelper.RIGHT) {
            @Override public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getAbsoluteAdapterPosition();
                int to = target.getAbsoluteAdapterPosition();
                Collections.swap(channels, from, to);
                rv.getAdapter().notifyItemMoved(from, to);
                findViewById(R.id.btn_save).setVisibility(View.VISIBLE);
                return true;
            }
            @Override public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int dir) {
                channels.remove(vh.getAbsoluteAdapterPosition());
                recyclerView.getAdapter().notifyItemRemoved(vh.getAbsoluteAdapterPosition());
                findViewById(R.id.btn_save).setVisibility(View.VISIBLE);
            }
        });
        th.attachToRecyclerView(recyclerView);
    }

    private void closeSortMode() {
        sortingLayout.setVisibility(View.GONE);
        updateListView();
    }

    private void savePlaylistToFile(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             BufferedWriter w = new BufferedWriter(new OutputStreamWriter(os))) {
            w.write("#EXTM3U\n");
            for (String[] ch : channels) { w.write("#EXTINF:-1," + ch[0] + "\n" + ch[1] + "\n"); }
            Toast.makeText(this, "Сохранено!", Toast.LENGTH_SHORT).show();
            findViewById(R.id.btn_save).setVisibility(View.GONE);
        } catch (Exception e) { Toast.makeText(this, "Ошибка записи", Toast.LENGTH_SHORT).show(); }
    }

    private void playChannel(int idx) {
        if (channels.isEmpty()) return;
        currentIdx = (idx + channels.size()) % channels.size();
        player.setMediaItem(MediaItem.fromUri(channels.get(currentIdx)[1]));
        player.prepare(); player.play();
        prefs.edit().putInt("last_idx", currentIdx).apply();
        overlay.setText(channels.get(currentIdx)[0]);
        overlay.setVisibility(View.VISIBLE);
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(() -> overlay.setVisibility(View.GONE), 3000);
    }

    private void loadPlaylist(Uri uri) {
        new Thread(() -> {
            List<String[]> tmp = new ArrayList<>();
            try (InputStream is = (uri.toString().startsWith("http")) ? new java.net.URL(uri.toString()).openStream() : getContentResolver().openInputStream(uri);
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
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
                runOnUiThread(() -> {
                    channels = tmp; updateListView();
                    prefs.edit().putString("last_playlist", uri.toString()).apply();
                    if (!channels.isEmpty()) playChannel(prefs.getInt("last_idx", 0));
                });
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Ошибка M3U", Toast.LENGTH_SHORT).show()); }
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
        class VH extends RecyclerView.ViewHolder { TextView txt; VH(View v) { super(v); txt = (TextView) v; } }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView tv = new TextView(p.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
            tv.setPadding(10, 20, 10, 20); tv.setTextColor(-1); tv.setTextSize(14); tv.setGravity(Gravity.CENTER);
            tv.setBackgroundResource(android.R.drawable.btn_default);
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int p) { h.txt.setText(channels.get(p)[0]); }
        @Override public int getItemCount() { return channels.size(); }
    }

    private void initGestures(View v) {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (channels.isEmpty()) return false;
                if (Math.abs(vY) > Math.abs(vX)) { playChannel(vY > 0 ? currentIdx - 1 : currentIdx + 1); return true; }
                return false;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (e.getX() < (float) playerView.getWidth() / 3) { drawer.openDrawer(GravityCompat.START); return true; }
                return false;
            }
        });
        v.setOnTouchListener((view, event) -> { gd.onTouchEvent(event); return true; });
    }

    private void showSettingsDialog() {
        String[] opt = {"Из буфера", "Выбрать файл"};
        new AlertDialog.Builder(this).setItems(opt, (d, w) -> {
            if (w == 0) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb.hasPrimaryClip()) loadPlaylist(Uri.parse(cb.getPrimaryClip().getItemAt(0).getText().toString().trim()));
            } else filePicker.launch(new String[]{"*/*"});
        }).show();
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            int c = e.getKeyCode();
            if (c == KeyEvent.KEYCODE_DPAD_UP || c == KeyEvent.KEYCODE_CHANNEL_UP || c == KeyEvent.KEYCODE_PAGE_UP) { playChannel(currentIdx - 1); return true; }
            if (c == KeyEvent.KEYCODE_DPAD_DOWN || c == KeyEvent.KEYCODE_CHANNEL_DOWN || c == KeyEvent.KEYCODE_PAGE_DOWN) { playChannel(currentIdx + 1); return true; }
        }
        return super.dispatchKeyEvent(e);
    }

    @Override protected void onResume() { super.onResume(); hideSystemUI(); if (player != null) player.play(); }
    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); if (player != null) player.release(); }
}
