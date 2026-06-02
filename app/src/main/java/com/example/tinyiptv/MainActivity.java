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
    private RecyclerView recyclerView;
    private ChannelAdapter adapter;
    
    private List<String[]> channels = new ArrayList<>();
    private int currentIdx = 0;
    private SharedPreferences prefs;
    private boolean isEditMode = false;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    loadPlaylist(uri);
                }
            });

    private final ActivityResultLauncher<String> fileSaver = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("audio/x-mpegurl"), uri -> {
                if (uri != null) savePlaylistToFile(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        
        // Экран НЕ гаснет
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemUI();

        prefs = getPreferences(MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        drawer = findViewById(R.id.drawer_layout);
        overlay = findViewById(R.id.channel_overlay);
        recyclerView = findViewById(R.id.channel_recycler);

        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        adapter = new ChannelAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // Настройка Drag-and-Drop и удаления
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP | ItemTouchHelper.DOWN | ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView rv, @NonNull RecyclerView.ViewHolder vh, @NonNull RecyclerView.ViewHolder target) {
                int from = vh.getAbsoluteAdapterPosition();
                int to = target.getAbsoluteAdapterPosition();
                Collections.swap(channels, from, to);
                adapter.notifyItemMoved(from, to);
                findViewById(R.id.btn_save).setVisibility(View.VISIBLE);
                return true;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder vh, int direction) {
                int pos = vh.getAbsoluteAdapterPosition();
                channels.remove(pos);
                adapter.notifyItemRemoved(pos);
                findViewById(R.id.btn_save).setVisibility(View.VISIBLE);
            }
        });
        touchHelper.attachToRecyclerView(recyclerView);

        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());
        findViewById(R.id.btn_sort).setOnClickListener(v -> toggleEditMode());
        findViewById(R.id.btn_save).setOnClickListener(v -> fileSaver.launch("edited_playlist.m3u"));

        initGestures(findViewById(R.id.gesture_layer));

        String lastUri = prefs.getString("last_playlist", "");
        if (!lastUri.isEmpty()) loadPlaylist(Uri.parse(lastUri));
        else drawer.openDrawer(GravityCompat.START);
    }

    private void toggleEditMode() {
        isEditMode = !isEditMode;
        if (isEditMode) {
            recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
            Toast.makeText(this, "Режим правки: зажми и тяни канал", Toast.LENGTH_SHORT).show();
        } else {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
        }
        adapter.notifyDataSetChanged();
    }

    private void savePlaylistToFile(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os))) {
            writer.write("#EXTM3U\n");
            for (String[] ch : channels) {
                writer.write("#EXTINF:-1," + ch[0] + "\n");
                writer.write(ch[1] + "\n");
            }
            Toast.makeText(this, "Сохранено!", Toast.LENGTH_SHORT).show();
            findViewById(R.id.btn_save).setVisibility(View.GONE);
        } catch (Exception e) { Toast.makeText(this, "Ошибка записи", Toast.LENGTH_SHORT).show(); }
    }

    private void playChannel(int idx) {
        if (channels.isEmpty()) return;
        currentIdx = idx % channels.size();
        if (currentIdx < 0) currentIdx += channels.size();
        
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
            InputStream is = null;
            try {
                if (uri.toString().startsWith("http")) {
                    java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(uri.toString()).openConnection();
                    c.setConnectTimeout(5000); is = c.getInputStream();
                } else is = getContentResolver().openInputStream(uri);

                if (is != null) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(is));
                    String line, name = "Канал";
                    while ((line = br.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("#EXTINF")) {
                            int comma = line.lastIndexOf(",");
                            if (comma != -1) name = line.substring(comma + 1);
                        } else if (line.startsWith("http") || line.startsWith("rtsp") || line.startsWith("rtmp")) {
                            tmp.add(new String[]{name, line});
                        }
                    }
                    br.close();
                }
                runOnUiThread(() -> {
                    channels = tmp; adapter.notifyDataSetChanged();
                    prefs.edit().putString("last_playlist", uri.toString()).apply();
                    if (!channels.isEmpty()) playChannel(prefs.getInt("last_idx", 0));
                });
            } catch (Exception e) { runOnUiThread(() -> Toast.makeText(this, "Ошибка", Toast.LENGTH_SHORT).show()); }
        }).start();
    }

    private void showSettingsDialog() {
        String[] opts = {"Из буфера", "Выбрать M3U файл"};
        new AlertDialog.Builder(this).setTitle("Плейлист").setItems(opts, (d, w) -> {
            if (w == 0) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cb.hasPrimaryClip()) loadPlaylist(Uri.parse(cb.getPrimaryClip().getItemAt(0).getText().toString().trim()));
            } else filePicker.launch(new String[]{"*/*"});
        }).show();
    }

    private class ChannelAdapter extends RecyclerView.Adapter<ChannelAdapter.VH> {
        class VH extends RecyclerView.ViewHolder { TextView txt; VH(View v) { super(v); txt = (TextView) v; } }
        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            TextView tv = new TextView(p.getContext());
            tv.setLayoutParams(new ViewGroup.LayoutParams(-1, -2));
            tv.setPadding(30, 30, 30, 30); tv.setTextColor(-1); tv.setTextSize(18);
            return new VH(tv);
        }
        @Override public void onBindViewHolder(@NonNull VH h, int p) {
            h.txt.setText(channels.get(p)[0]);
            h.itemView.setOnClickListener(v -> {
                if (!isEditMode) { playChannel(h.getAbsoluteAdapterPosition()); drawer.closeDrawer(GravityCompat.START); }
            });
        }
        @Override public int getItemCount() { return channels.size(); }
    }

    private void initGestures(View v) {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (channels.isEmpty()) return false;
                if (Math.abs(vY) > Math.abs(vX)) {
                    playChannel(vY > 0 ? (currentIdx - 1 + channels.size()) % channels.size() : (currentIdx + 1) % channels.size());
                    return true;
                }
                return false;
            }
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                if (e.getX() < (float) playerView.getWidth() / 3) { drawer.openDrawer(GravityCompat.START); return true; }
                return false;
            }
        });
        v.setOnTouchListener((view, event) -> { gd.onTouchEvent(event); return true; });
    }

    private void hideSystemUI() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    // ТВОИ ЛЮБИМЫЕ КНОПКИ ТУТ (ПОЛНЫЙ СПИСОК)
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            // Вперед
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_CHANNEL_UP || code == KeyEvent.KEYCODE_MEDIA_NEXT || code == KeyEvent.KEYCODE_PAGE_UP) {
                playChannel((currentIdx + 1) % channels.size()); return true;
            }
            // Назад
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_DPAD_LEFT ||
                code == KeyEvent.KEYCODE_CHANNEL_DOWN || code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || code == KeyEvent.KEYCODE_PAGE_DOWN) {
                playChannel((currentIdx - 1 + channels.size()) % channels.size()); return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume() { super.onResume(); hideSystemUI(); if (player != null) player.play(); }
    @Override protected void onStop() { super.onStop(); if (player != null) player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); if (player != null) player.release(); }
}
