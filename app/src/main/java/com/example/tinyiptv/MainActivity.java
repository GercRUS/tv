package com.example.tinyiptv;

import android.content.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import com.google.android.exoplayer2.*;
import com.google.android.exoplayer2.ui.StyledPlayerView;
import java.io.*;
import java.util.*;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private StyledPlayerView playerView;
    private DrawerLayout drawer;
    private TextView overlay;
    private List<String[]> channels = new ArrayList<>();
    private int currentIdx = 0;
    private SharedPreferences prefs;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());

    private final ActivityResultLauncher<String[]> filePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    try { getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    loadPlaylist(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supportRequestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        prefs = getPreferences(MODE_PRIVATE);
        playerView = findViewById(R.id.player_view);
        drawer = findViewById(R.id.drawer_layout);
        overlay = findViewById(R.id.channel_overlay);
        
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        findViewById(R.id.btn_settings).setOnClickListener(v -> showSettingsDialog());

        // Вешаем жесты на прозрачный слой
        View gestureLayer = findViewById(R.id.gesture_layer);
        initGestures(gestureLayer);

        String lastUri = prefs.getString("last_playlist", "");
        if (!lastUri.isEmpty()) {
            loadPlaylist(Uri.parse(lastUri));
        } else {
            drawer.openDrawer(GravityCompat.START);
        }
    }

    private void initGestures(View view) {
        GestureDetector gd = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float vX, float vY) {
                if (channels.isEmpty()) return false;
                // Свайп вертикальный
                if (Math.abs(vY) > Math.abs(vX)) {
                    if (vY > 0) playChannel((currentIdx - 1 + channels.size()) % channels.size());
                    else playChannel((currentIdx + 1) % channels.size());
                    return true;
                }
                return false;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                // Тап в левой трети экрана
                if (e.getX() < (float) playerView.getWidth() / 3) {
                    drawer.openDrawer(GravityCompat.START);
                    return true;
                }
                return false;
            }
        });

        view.setOnTouchListener((v, event) -> {
            gd.onTouchEvent(event);
            return true;
        });
    }

    private void playChannel(int idx) {
        if (channels.isEmpty()) return;
        currentIdx = idx % channels.size();
        String name = channels.get(currentIdx)[0];
        String url = channels.get(currentIdx)[1];

        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
        
        prefs.edit().putInt("last_idx", currentIdx).apply();
        
        // Показываем название канала
        overlay.setText(name);
        overlay.setVisibility(View.VISIBLE);
        hideHandler.removeCallbacksAndMessages(null);
        hideHandler.postDelayed(() -> overlay.setVisibility(View.GONE), 3000);
    }

    private void showSettingsDialog() {
        String[] options = {"Вставить из буфера", "Ввести URL", "Выбрать файл M3U"};
        new AlertDialog.Builder(this).setTitle("Плейлист")
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cb.hasPrimaryClip()) loadPlaylist(Uri.parse(cb.getPrimaryClip().getItemAt(0).getText().toString().trim()));
                } else if (which == 1) {
                    EditText input = new EditText(this);
                    new AlertDialog.Builder(this).setTitle("URL").setView(input)
                        .setPositiveButton("OK", (d, w) -> loadPlaylist(Uri.parse(input.getText().toString().trim()))).show();
                } else if (which == 2) {
                    filePicker.launch(new String[]{"*/*"});
                }
            }).show();
    }

    private void loadPlaylist(Uri uri) {
        new Thread(() -> {
            List<String[]> tmp = new ArrayList<>();
            try (InputStream is = getContentResolver().openInputStream(uri);
                 BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
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
                runOnUiThread(() -> {
                    channels = tmp;
                    updateList();
                    prefs.edit().putString("last_playlist", uri.toString()).apply();
                    if (!channels.isEmpty()) playChannel(prefs.getInt("last_idx", 0));
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "Ошибка M3U", Toast.LENGTH_SHORT).show());
            }
        }).start();
    }

    private void updateList() {
        ListView lv = findViewById(R.id.channel_list);
        List<String> names = new ArrayList<>();
        for (String[] c : channels) names.add(c[0]);
        lv.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
        lv.setOnItemClickListener((p, v, pos, id) -> { playChannel(pos); drawer.closeDrawer(GravityCompat.START); });
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }

@Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();

            // Кнопки "Вперед" / "Следующий"
            if (code == KeyEvent.KEYCODE_DPAD_UP || 
                code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_CHANNEL_UP || 
                code == KeyEvent.KEYCODE_MEDIA_NEXT || 
                code == KeyEvent.KEYCODE_PAGE_UP) {
                
                playChannel((currentIdx + 1) % channels.size());
                return true; 
            }

            // Кнопки "Назад" / "Предыдущий"
            if (code == KeyEvent.KEYCODE_DPAD_DOWN || 
                code == KeyEvent.KEYCODE_DPAD_LEFT ||
                code == KeyEvent.KEYCODE_CHANNEL_DOWN || 
                code == KeyEvent.KEYCODE_MEDIA_PREVIOUS || 
                code == KeyEvent.KEYCODE_PAGE_DOWN) {
                
                playChannel((currentIdx - 1 + channels.size()) % channels.size());
                return true;
            }
        }
        // Если это кнопка громкости или любая другая — отдаем её системе (стандартное поведение)
        return super.dispatchKeyEvent(event);
    }

    @Override protected void onResume() { super.onResume(); hideSystemUI(); player.play(); }
    @Override protected void onStop() { super.onStop(); player.pause(); }
    @Override protected void onDestroy() { super.onDestroy(); player.release(); }
}
